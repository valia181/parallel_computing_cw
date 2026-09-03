package llm;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class OllamaWorkerTest
{
    private static class FakeOllamaCli implements OllamaCliInterface
    {
        private final String response;
        private FakeOllamaCli(String response) { this.response = response; }

        @Override
        public String generate(String prompt) { return response; }

        @Override
        public boolean isAlive() { return true; }
    }

    private static class FailingOllamaCli implements OllamaCliInterface
    {
        @Override
        public String generate(String prompt) throws Exception
        {
            throw new Exception("connection refused");
        }

        @Override
        public boolean isAlive() { return false; }
    }

    @Test
    void synonymsSeparated()
    {
        OllamaWorker worker = new OllamaWorker(new FakeOllamaCli("cute kitten pet"));
        List<String> result = worker.getWords(List.of("cat"));

        assertEquals(List.of("cute", "kitten", "pet"), result);
    }

    @Test
    void lowerCases()
    {
        OllamaWorker worker = new OllamaWorker(new FakeOllamaCli("CUTE Kitten"));
        List<String> result = worker.getWords(List.of("cat"));

        assertEquals(List.of("cute", "kitten"), result);
    }

    @Test
    void wordsAlreadyInQuery()
    {
        OllamaWorker worker = new OllamaWorker(new FakeOllamaCli("cat cute kitten"));
        List<String> result = worker.getWords(List.of("cat"));

        assertFalse(result.contains("cat"));
        assertTrue(result.contains("cute"));
        assertTrue(result.contains("kitten"));
    }

    @Test
    void handlesModel()
    {
        OllamaWorker worker = new OllamaWorker(new FakeOllamaCli("Sure! cute, kitten, and pet."));
        List<String> result = worker.getWords(List.of("cat"));

        assertTrue(result.contains("cute"));
        assertTrue(result.contains("kitten"));
        assertTrue(result.contains("pet"));
    }

    @Test
    void returnsEmptyList()
    {
        OllamaWorker worker = new OllamaWorker(new FailingOllamaCli());
        List<String> result = worker.getWords(List.of("cat"));

        assertNotNull(result);
        assertTrue(result.isEmpty());
    }

    @Test
    void returnsEmptyListForEmptyResponse()
    {
        OllamaWorker worker = new OllamaWorker(new FakeOllamaCli(""));
        List<String> result = worker.getWords(List.of("cat"));

        assertTrue(result.isEmpty());
    }
}