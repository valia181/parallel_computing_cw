import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

class HashTableTest
{
    private HashTable table;

    @BeforeEach
    void setUp()
    {
        table = new HashTable(10);
    }

    @Test
    void searchOneWord()
    {
        table.addWord("word", "doc1", 5);

        Map<String, Integer> result = table.search("word");

        assertFalse(result.isEmpty());
        assertEquals(5, result.get("doc1"));
    }

    @Test
    void emptyMap()
    {
        Map<String, Integer> result = table.search("unknown");

        assertTrue(result.isEmpty());
    }

    @Test
    void addDocumentsForWord()
    {
        table.addWord("word", "doc1", 10);
        table.addWord("word", "doc2", 3);

        Map<String, Integer> result = table.search("word");

        assertEquals(2, result.size(), "Its 2 docs");
        assertEquals(10, result.get("doc1"));
        assertEquals(3, result.get("doc2"));
    }

    @Test
    void invalidInputs() {
        assertThrows(IllegalArgumentException.class, () -> table.addWord("", "doc1", 1));
        assertThrows(IllegalArgumentException.class, () -> table.addWord("test", null, 1));
        assertThrows(IllegalArgumentException.class, () -> table.addWord("test", "doc1", -5));
        assertThrows(IllegalArgumentException.class, () -> new HashTable(0));
        assertThrows(IllegalArgumentException.class, () -> new HashTable(-5));
    }

    @Test
    void concurrentAddWord() throws InterruptedException
    {
        ExecutorService executor = Executors.newFixedThreadPool(10);
        int numberOfTasks = 1000;

        for (int i = 0; i < numberOfTasks; i++)
        {
            final String docId = "doc" + i;
            executor.submit(() ->
            {
                table.addWord("word", docId, 1);
            });
        }

        executor.shutdown();
        boolean finished = executor.awaitTermination(1, TimeUnit.MINUTES);

        assertTrue(finished);

        Map<String, Integer> result = table.search("word");
        assertEquals(numberOfTasks, result.size());
    }

    @Test
    void concurrentAddCollisions() throws InterruptedException
    {
        HashTable smallTable = new HashTable(1);
        ExecutorService executor = Executors.newFixedThreadPool(10);

        for (int i = 0; i < 500; i++)
        {
            final String word = "word" + i;
            executor.submit(() ->
            {
                smallTable.addWord(word, "doc1", 1);
            });
        }

        executor.shutdown();
        executor.awaitTermination(1, TimeUnit.MINUTES);

        assertEquals(1, smallTable.search("word0").get("doc1"));
        assertEquals(1, smallTable.search("word499").get("doc1"));
    }

    @Test
    void checkAddingFreq()
    {
        for (int i = 0; i < 500; i++)
        {
            table.addWord("word", "doc1", 1);
        }

        Map <String, Integer> result = table.search("word");

        assertEquals(500, result.get("doc1"));
    }
}