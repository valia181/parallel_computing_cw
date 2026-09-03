package llm;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class OllamaIntegrationTest
{
    @Test
    @Timeout(30)
    void respondsGenerate() throws Exception
    {
        OllamaCli cli = new OllamaCli("http://localhost:11434", "llama3.2:1b");
        assertTrue(cli.isAlive());

        String response = cli.generate("Are cats cute? One word YES or no");

        System.out.println(response);
        assertNotNull(response);
        assertFalse(response.isEmpty());
    }

    @Test
    @Timeout(30)
    void ollamaExpands()
    {
        OllamaCli cli = new OllamaCli("http://localhost:11434", "llama3.2:1b");
        assertTrue(cli.isAlive());

        OllamaWorker worker = new OllamaWorker(cli);
        List<String> result = worker.getWords(List.of("cat", "cute"));

        System.out.println("Synonyms: " + result);
        assertFalse(result.isEmpty());
    }
}