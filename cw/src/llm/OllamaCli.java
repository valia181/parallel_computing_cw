package llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

public class OllamaCli implements OllamaCliInterface
{
    private final HttpClient client;
    private final String url;
    private final String model;
    private final ObjectMapper mapper = new ObjectMapper();

    public OllamaCli (String url, String model)
    {
        this.url = url;
        this.model = model;
        client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    }

    @Override
    public String generate (String prompt) throws Exception
    {
        String body = mapper.writeValueAsString(java.util.Map.of(
                "model", model,
                "prompt", prompt,
                "stream", false
        ));

        HttpRequest request = HttpRequest.newBuilder().uri(URI.create(url + "/api/generate")).
                timeout(Duration.ofSeconds(30)).
                header("Content-Type", "application/json").
                POST(HttpRequest.BodyPublishers.ofString(body)).build();

        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() != 200)
        {
            throw new RuntimeException("Ollama error (status " + response.statusCode() + "): " + response.body());
        }

        JsonNode json = mapper.readTree(response.body());

        return json.get("response").asText();
    }

    @Override
    public boolean isAlive()
    {
        try
        {
            HttpRequest request = HttpRequest.newBuilder(URI.create(url)).
                    timeout(Duration.ofSeconds(30)).
                    GET().build();

            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            return response.statusCode() == 200;
        }
        catch (Exception e)
        {
            return false;
        }
    }
}
