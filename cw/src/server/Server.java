package server;

import io.javalin.http.staticfiles.Location;
import com.fasterxml.jackson.annotation.JsonProperty;
import core.Indexing;
import core.Tokenizer;
import io.javalin.Javalin;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import llm.OllamaCli;
import llm.OllamaWorker;

public class Server {
    private static final int DEFAULT_THREADS = 4;
    private static final Set<String> SUPPORTED_MODES = Set.of("keyword", "ollama");

    private final int port;
    private final Path dataDir;
    private final Javalin server;
    private final OllamaCli ollamaCli;
    private final OllamaWorker ollamaWorker;
    private Indexing indexTable;

    public Server() {
        Map<String, String> env = System.getenv();
        this.port = Integer.parseInt(env.getOrDefault("PORT", "8080"));
        this.dataDir = Path.of(env.getOrDefault("DATA_DIR", "data"));
        String ollamaUrl = env.getOrDefault("OLLAMA_URL", "http://localhost:11434");
        String ollamaModel = env.getOrDefault("OLLAMA_MODEL", "llama3.2:1b");

        this.server = Javalin.create(config ->
                config.staticFiles.add("/public", Location.CLASSPATH)
        );
        this.ollamaCli = new OllamaCli(ollamaUrl, ollamaModel);
        this.ollamaWorker = new OllamaWorker(ollamaCli);
        this.indexTable = new Indexing(100_000, ollamaWorker);

        configErrors();
        configRoutes();
    }

    public record ConfigThread(@JsonProperty("threads") Integer threads) {}

    public record ConfigSearch(
            @JsonProperty("query") String words, @JsonProperty("mode") String mode) {}

    public record WsSearchRequest(@JsonProperty("query") String query) {}

    private static Map<String, String> error(String message) {
        return Map.of("status", "error", "message", String.valueOf(message));
    }

    private void configErrors() {
        server.exception(
                IllegalArgumentException.class,
                (e, ctx) -> {
                    System.err.println("Invalid argument: " + e.getMessage());
                    ctx.status(400).json(error(e.getMessage()));
                });
        server.exception(
                IllegalStateException.class,
                (e, ctx) -> {
                    System.err.println("Conflict: " + e.getMessage());
                    ctx.status(409).json(error(e.getMessage()));
                });
        server.exception(
                IOException.class,
                (e, ctx) -> {
                    System.err.println("I/O error: " + e.getMessage());
                    ctx.status(500).json(error(e.getMessage()));
                });
    }

    private void configRoutes() {
        server.get(
                "/api/health",
                ctx -> ctx.json(Map.of("status", "ok", "ollama", ollamaCli.isAlive())));

        server.post(
                "/api/index",
                ctx -> {
                    int threads = DEFAULT_THREADS;
                    if (!ctx.body().isBlank()) {
                        ConfigThread config = ctx.bodyAsClass(ConfigThread.class);
                        if (config.threads() != null) {
                            threads = config.threads();
                        }
                    }

                    if (threads < 1 || threads > 256)
                    {
                        throw new IllegalArgumentException("threads must be between 1 and 256");
                    }

                    try (Stream<Path> walk = Files.walk(dataDir)) {
                        List<Path> files = walk.filter(Files::isRegularFile).toList();
                        this.indexTable = new Indexing(100_000, ollamaWorker);
                        ctx.status(200).json(indexTable.buildIndex(files, threads));
                    }
                });

        server.post(
                "/api/search",
                ctx -> {
                    ConfigSearch request = ctx.bodyAsClass(ConfigSearch.class);

                    if (request.words() == null || request.mode() == null) {
                        throw new IllegalArgumentException("Fields 'query' and 'mode' are required");
                    }
                    if (!SUPPORTED_MODES.contains(request.mode())) {
                        throw new IllegalArgumentException("Unsupported mode: " + request.mode());
                    }

                    List<String> terms = Tokenizer.tokenize(request.words());
                    if (terms.isEmpty()) {
                        throw new IllegalArgumentException("Query has no searchable words");
                    }

                    ctx.status(200).json(indexTable.search(terms, request.mode()));
                });

        server.get("/api/stats", ctx -> ctx.status(200).json(indexTable.getStats()));

        server.ws(
                "/ws/search",
                ws -> {
                    ws.onMessage(
                            ctx -> {
                                try {
                                    WsSearchRequest request = ctx.messageAsClass(WsSearchRequest.class);
                                    List<String> originalTerms = Tokenizer.tokenize(request.query());

                                    if (originalTerms.isEmpty()) {
                                        ctx.send(
                                                Map.of("stage", "error", "data", Map.of("message", "query is required")));
                                        return;
                                    }

                                    List<String> synonyms = ollamaWorker.getWords(originalTerms);
                                    ctx.send(
                                            Map.of(
                                                    "stage", "query_expansion",
                                                    "data", Map.of("original", request.query(), "expanded_terms", synonyms)));

                                    Indexing.SearchResult result =
                                            indexTable.searchWithExpansion(originalTerms, synonyms);
                                    ctx.send(
                                            Map.of(
                                                    "stage", "keyword_search",
                                                    "data",
                                                    Map.of("results", result.results(), "time_ms", result.time())));

                                    ctx.send(Map.of("stage", "done"));
                                } catch (Exception e) {
                                    System.err.println("WebSocket request failed: " + e.getMessage());
                                    ctx.send(
                                            Map.of(
                                                    "stage", "error",
                                                    "data", Map.of("message", "Invalid request: " + e.getMessage())));
                                }
                            });

                    ws.onError(ctx -> System.err.println("WebSocket error: " + ctx.error()));
                });
    }

    public void startServer() {
        server.start(port);
    }
}