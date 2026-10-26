package core;

import collections.HashTable;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import llm.OllamaWorker;
import pool.ThreadPoolInterface;
import pool.WorkerThreadPool;

public class Indexing
{
    public static final int MAX_THREADS = 256;
    public static final int TOP_K = 50;
    private static final double ORIGINAL_WEIGHT = 1.0;
    private static final double SYNONYM_WEIGHT = 0.5;
    private static final int SNIPPET_RADIUS = 80;

    public record IndexResponse(
            String status,
            @JsonProperty("documents_indexed") int documentsIndexed,
            @JsonProperty("time_ms") long timeMs) {}

    public record StatsResponse(
            @JsonProperty("documents") int documents,
            @JsonProperty("index_build_time_ms") long indexBuildTimeMs,
            @JsonProperty("threads_used") int threadsUsed) {}

    public record SearchItem(
            @JsonProperty("doc_id") String docId,
            @JsonProperty("score") double score,
            @JsonProperty("snippet") String snippet) {}

    public record SearchResult(
            @JsonProperty("results") List<SearchItem> results, @JsonProperty("time_ms") long time) {}

    private record IndexState(
            HashTable table, Map<String, Path> paths, int documents, long buildTimeMs, int threads) {}

    private final int tableSize;
    private final OllamaWorker ollama;
    private final AtomicBoolean building = new AtomicBoolean(false);
    private volatile IndexState state;

    public Indexing(int size)
    {
        this(size, null);
    }

    public Indexing(int size, OllamaWorker ollama)
    {
        if (size < 1)
        {
            throw new IllegalArgumentException("Invalid arg");
        }
        this.tableSize = size;
        this.ollama = ollama;
        this.state = new IndexState(new HashTable(size), Map.of(), 0, 0, 0);
    }

    public IndexResponse buildIndex(List<Path> listOfDocs, int threadsCount)
    {
        if (listOfDocs == null) {
            throw new IllegalArgumentException("List of documents is null");
        }
        if (threadsCount < 1 || threadsCount > MAX_THREADS)
        {
            throw new IllegalArgumentException("threads must be between 1 and " + MAX_THREADS);
        }
        if (!building.compareAndSet(false, true))
        {
            throw new IllegalStateException("Indexing is already in progress");
        }

        try
        {
            long start = System.nanoTime();
            HashTable newTable = new HashTable(tableSize);
            Map<String, Path> newPaths = new ConcurrentHashMap<>();
            AtomicInteger docCounts = new AtomicInteger(0);

            ThreadPoolInterface threadPool = new WorkerThreadPool(threadsCount);
            try
            {
                for (Path file : listOfDocs)
                {
                    threadPool.runTask(() -> indexFile(file, newTable, newPaths, docCounts));
                }
            } finally
            {
                threadPool.stopPool();
                threadPool.waitTermination();
            }

            long elapsedMs = (System.nanoTime() - start) / 1_000_000;
            int documents = docCounts.get();
            state = new IndexState(newTable, newPaths, documents, elapsedMs, threadsCount);
            return new IndexResponse("ok", documents, elapsedMs);
        } finally {
            building.set(false);
        }
    }

    private void indexFile(
            Path file, HashTable target, Map<String, Path> paths, AtomicInteger docCounts)
    {
        try {
            Map<String, Integer> local = Tokenizer.countWords(readText(file));
            String docId = file.getFileName().toString();

            for (Map.Entry<String, Integer> entry : local.entrySet())
            {
                target.addWord(entry.getKey(), docId, entry.getValue());
            }
            paths.put(docId, file);
            docCounts.incrementAndGet();
        } catch (IOException e) {
            System.err.println("Error while reading " + file + ": " + e.getMessage());
        }
    }

    private static String readText(Path file) throws IOException
    {
        return new String(Files.readAllBytes(file), StandardCharsets.ISO_8859_1);
    }

    public StatsResponse getStats()
    {
        IndexState current = state;
        return new StatsResponse(current.documents(), current.buildTimeMs(), current.threads());
    }

    public SearchResult search(List<String> words, String mode)
    {
        if (words == null || mode == null || words.isEmpty() || mode.isEmpty())
        {
            return new SearchResult(Collections.emptyList(), 0);
        }

        List<String> original = Tokenizer.distinctTerms(words);
        List<String> synonyms = Collections.emptyList();
        if ("ollama".equals(mode) && ollama != null && !original.isEmpty())
        {
            synonyms = ollama.getWords(original);
        }
        return searchWithExpansion(original, synonyms);
    }

    public SearchResult searchWithExpansion(List<String> original, List<String> synonyms)
    {
        long start = System.nanoTime();
        IndexState snapshot = state;

        Map<String, Double> weights = new LinkedHashMap<>();
        for (String term : Tokenizer.distinctTerms(original))
        {
            weights.put(term, ORIGINAL_WEIGHT);
        }
        for (String term : Tokenizer.distinctTerms(synonyms))
        {
            weights.putIfAbsent(term, SYNONYM_WEIGHT);
        }

        Map<String, Double> scores = calculateScore(snapshot.table(), weights);

        List<Map.Entry<String, Double>> top =
                scores.entrySet().stream()
                        .sorted(
                                Map.Entry.<String, Double>comparingByValue(Comparator.reverseOrder())
                                        .thenComparing(Map.Entry.comparingByKey()))
                        .limit(TOP_K)
                        .toList();

        List<SearchItem> items = new ArrayList<>(top.size());
        for (Map.Entry<String, Double> entry : top)
        {
            String snippet = buildSnippet(snapshot.paths().get(entry.getKey()), weights.keySet());
            items.add(new SearchItem(entry.getKey(), entry.getValue(), snippet));
        }

        long elapsedMs = (System.nanoTime() - start) / 1_000_000;
        return new SearchResult(items, elapsedMs);
    }

    private Map<String, Double> calculateScore(HashTable table, Map<String, Double> weights)
    {
        Map<String, Double> scores = new HashMap<>();
        for (Map.Entry<String, Double> term : weights.entrySet())
        {
            for (Map.Entry<String, Integer> freq : table.search(term.getKey()).entrySet())
            {
                scores.merge(freq.getKey(), freq.getValue() * term.getValue(), Double::sum);
            }
        }
        return scores;
    }

    private String buildSnippet(Path path, Iterable<String> terms)
    {
        if (path == null) {
            return "";
        }
        try {
            String text = readText(path);
            String lower = text.toLowerCase(Locale.ROOT);

            int position = -1;
            int termLength = 0;
            for (String term : terms) {
                int index = lower.indexOf(term);
                if (index >= 0 && (position < 0 || index < position))
                {
                    position = index;
                    termLength = term.length();
                }
            }

            if (position < 0)
            {
                position = 0;
            }
            int from = Math.max(0, position - SNIPPET_RADIUS);
            int to = Math.min(text.length(), position + termLength + SNIPPET_RADIUS);

            String fragment = text.substring(from, to).replaceAll("\\s+", " ").trim();
            return (from > 0 ? "..." : "") + fragment + (to < text.length() ? "..." : "");
        } catch (IOException e) {
            return "";
        }
    }

    HashTable getTable() {
        return state.table();
    }
}