package client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.ConnectException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class SearchCli
{
    private static final int EXIT_OK = 0;
    private static final int EXIT_ERROR = 1;
    private static final int EXIT_USAGE = 2;
    private static final int EXIT_UNREACHABLE = 3;
    private static final Set<String> MODES = Set.of("keyword", "ollama");
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private SearchCli() {}

    public static void main(String[] args)
    {
        System.exit(run(args));
    }

    private static int run(String[] args)
    {
        String url = System.getenv().getOrDefault("SEARCH_URL", "http://localhost:8080");
        String mode = "keyword";
        int top = 10;
        boolean rawJson = false;
        List<String> words = new ArrayList<>();

        try
        {
            for (int i = 0; i < args.length; i++)
            {
                String arg = args[i];
                switch (arg)
                {
                    case "-h", "--help" ->
                    {
                        usage();
                        return EXIT_OK;
                    }
                    case "-m", "--mode" -> mode = value(args, ++i, arg);
                    case "-n", "--top" -> top = Integer.parseInt(value(args, ++i, arg));
                    case "-u", "--url" -> url = value(args, ++i, arg);
                    case "--json" -> rawJson = true;
                    default -> words.add(arg);
                }
            }
        } catch (IllegalArgumentException e)
        {
            System.err.println("Invalid option: " + e.getMessage());
            usage();
            return EXIT_USAGE;
        }

        String query = String.join(" ", words).trim();
        if (query.isEmpty() || !MODES.contains(mode) || top < 1)
        {
            usage();
            return EXIT_USAGE;
        }

        return search(url.replaceAll("/+$", ""), query, mode, top, rawJson);
    }

    private static int search(String url, String query, String mode, int top, boolean rawJson)
    {
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        try
        {
            String body = MAPPER.writeValueAsString(Map.of("query", query, "mode", mode));
            HttpRequest request = HttpRequest.newBuilder(URI.create(url + "/api/search"))
                    .timeout(Duration.ofSeconds(120))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();

            HttpResponse<String> response =
                    client.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200)
            {
                String message = MAPPER.readTree(response.body()).path("message")
                        .asText(response.body());
                System.err.println("Server error (HTTP " + response.statusCode() + "): " + message);
                return EXIT_ERROR;
            }
            if (rawJson)
            {
                System.out.println(response.body());
                return EXIT_OK;
            }
            print(MAPPER.readTree(response.body()), query, mode, top);
            return EXIT_OK;
        } catch (ConnectException | HttpConnectTimeoutException e)
        {
            System.err.println("Cannot connect to " + url + ". Is the server running?");
            return EXIT_UNREACHABLE;
        } catch (HttpTimeoutException e)
        {
            System.err.println("Request timed out.");
            return EXIT_ERROR;
        } catch (IOException e)
        {
            System.err.println("I/O error: " + e.getMessage());
            return EXIT_ERROR;
        } catch (InterruptedException e)
        {
            Thread.currentThread().interrupt();
            return EXIT_ERROR;
        }
    }

    private static void print(JsonNode root, String query, String mode, int top)
    {
        JsonNode results = root.path("results");
        int shown = Math.min(top, results.size());

        System.out.printf("Query: \"%s\"  (mode: %s, %d ms, found: %d, shown: %d)%n%n",
                query, mode, root.path("time_ms").asLong(), results.size(), shown);

        if (results.isEmpty())
        {
            System.out.println("No results. Is the index built? (POST /api/index)");
            return;
        }
        for (int i = 0; i < shown; i++)
        {
            JsonNode item = results.get(i);
            System.out.printf("%2d. %s   score %.1f%n", i + 1, item.path("doc_id").asText(),
                    item.path("score").asDouble());
            String snippet = item.path("snippet").asText("");
            if (!snippet.isEmpty())
            {
                System.out.println("    " + snippet);
            }
        }
    }

    private static String value(String[] args, int index, String option)
    {
        if (index >= args.length)
        {
            throw new IllegalArgumentException("missing value for " + option);
        }
        return args[index];
    }

    private static void usage()
    {
        System.err.println("""
                Usage: search [options] <query>

                Options:
                  -m, --mode <keyword|ollama>  search mode (default: keyword)
                  -n, --top <N>                number of results to print (default: 10)
                  -u, --url <url>              server URL (default: $SEARCH_URL or http://localhost:8080)
                      --json                   print raw JSON response
                  -h, --help                   show this help

                Examples:
                  ./search "space movie"
                  ./search -m ollama -n 5 "space movie"
                """);
    }
}
