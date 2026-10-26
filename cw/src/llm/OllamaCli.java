package llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;

public class OllamaCli implements OllamaCliInterface {
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(3);
    private static final Duration HEALTH_TIMEOUT = Duration.ofSeconds(3);
    private static final Duration GENERATE_TIMEOUT = Duration.ofSeconds(90);

    private final HttpClient client;
    private final String url;
    private final String model;
    private final ObjectMapper mapper = new ObjectMapper();

    public OllamaCli(String url, String model) {
        this.url = url;
        this.model = model;
        this.client = HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build();
    }

    @Override
    public String generate(String prompt) throws Exception {
        String body =
                mapper.writeValueAsString(Map.of("model", model, "prompt", prompt, "stream", false));

        HttpRequest request =
                HttpRequest.newBuilder()
                        .uri(URI.create(url + "/api/generate"))
                        .timeout(GENERATE_TIMEOUT)
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(body))
                        .build();

        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() != 200) {
            throw new RuntimeException(
                    "Ollama error (status " + response.statusCode() + "): " + response.body());
        }

        JsonNode json = mapper.readTree(response.body());
        return json.path("response").asText("");
    }

    @Override
    public boolean isAlive() {
        try {
            HttpRequest request =
                    HttpRequest.newBuilder(URI.create(url)).timeout(HEALTH_TIMEOUT).GET().build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            return response.statusCode() == 200;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } catch (Exception e) {
            return false;
        }
    }
}