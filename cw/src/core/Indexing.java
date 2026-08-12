package core;

import collections.HashTable;
import com.fasterxml.jackson.annotation.JsonProperty;
import pool.ThreadPoolInterface;
import pool.WorkerThreadPool;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

public class Indexing
{
    private final HashTable table;
    public record IndexResponse(String status, @JsonProperty("documents_indexed") int documentsIndexed, @JsonProperty("time_ms") long timeMs) {}

    public Indexing(int size)
    {
        if (size < 1)
        {
            throw new IllegalArgumentException("Invalid arg");
        }

        table = new HashTable(size);
    }

    public IndexResponse buildIndex(List<Path> listOfDocs, int threadsCount)
    {
        if (threadsCount < 1)
        {
            throw new IllegalArgumentException("Invalid thread count");
        }

        long start = System.currentTimeMillis();
        ThreadPoolInterface threadPool = new WorkerThreadPool(threadsCount);
        AtomicInteger docCounts = new AtomicInteger(0);

        for (Path file: listOfDocs)
        {
            threadPool.runTask(() ->
            {
                try
                {
                    String text = Files.readString(file);
                    String[] words = text.toLowerCase().split("[^a-z]+");

                    HashMap<String, Integer> local = new HashMap<>();
                    for (String word : words)
                    {
                        if (!word.isEmpty())
                        {
                            local.merge(word, 1, Integer::sum);
                        }
                    }

                    String docId = file.getFileName().toString();
                    for(Map.Entry<String, Integer> set: local.entrySet())
                    {
                        table.addWord(set.getKey(), docId, set.getValue());
                    }

                    docCounts.getAndIncrement();
                } catch (IOException e)
                {
                    System.err.println("Error while work in " + file + ": " + e.getMessage());
                }
            });
        }
        threadPool.stopPool();
        threadPool.waitTermination();

        long end = System.currentTimeMillis() - start;
        IndexResponse result = new IndexResponse("ok", docCounts.get(), end);
        return result;
    }

    public HashTable getTable()
    {
        return this.table;
    }
}
