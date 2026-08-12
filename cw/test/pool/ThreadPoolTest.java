package pool;

import collections.HashTable;
import org.junit.Test;
import org.junit.jupiter.api.Timeout;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class ThreadPoolTest
{
    @Test
    @Timeout(10)
    public void parallelIndexing()
    {
        HashTable table = new HashTable(64);
        ThreadPoolInterface pool = new WorkerThreadPool(8);

        int docCount = 500;
        List<String> words = List.of("word1", "word2", "word3", "word4", "word5");

        for (int i = 0; i < docCount; i++)
        {
            final String docId = "doc" + i;
            pool.runTask(() ->
            {
                for (String word : words)
                {
                    table.addWord(word, docId, 1);
                }
            });
        }

        pool.stopPool();
        pool.waitTermination();

        for (String word : words)
        {
            Map<String, Integer> result = table.search(word);
            assertEquals(docCount, result.size());
        }
    }

    @Test
    @Timeout(10)
    public void freqCount()
    {
        HashTable table = new HashTable(4);
        ThreadPoolInterface pool = new WorkerThreadPool(8);

        int calls = 50;
        for (int i = 0; i < calls; i++)
        {
            pool.runTask(() -> table.addWord("word1", "doc1", 1));
        }

        pool.stopPool();
        pool.waitTermination();

        Map<String, Integer> result = table.search("word1");
        assertEquals(calls, result.get("doc1"));
    }

    @Test
    @Timeout(10)
    public void badTasks()
    {
        HashTable table = new HashTable(16);
        ThreadPoolInterface pool = new WorkerThreadPool(4);

        int docCount = 100;
        int expectedFailures = 10;

        for (int i = 0; i < docCount; i++)
        {
            final String docId = "doc" + i;
            final boolean shouldFail = (i % 10 == 0);

            pool.runTask(() -> {
                if (shouldFail)
                {
                    throw new RuntimeException("Failed: " + docId);
                }
                table.addWord("word", docId, 1);
            });
        }

        pool.stopPool();
        pool.waitTermination();

        Map<String, Integer> result = table.search("word");
        assertEquals(docCount - expectedFailures, result.size());
    }
}
