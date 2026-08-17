package core;

import collections.HashTable;
import com.fasterxml.jackson.annotation.JsonProperty;
import pool.ThreadPoolInterface;
import pool.WorkerThreadPool;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
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

    public record SearchItem (@JsonProperty("doc_id") String docId, @JsonProperty("score") int score, @JsonProperty("snippet") String snippet) {}
    public record SearchResult (@JsonProperty("results") List <SearchItem> results, @JsonProperty("time_ms") long time){}

    public SearchResult search (List<String> words, String mode)
    {
        long start = System.currentTimeMillis();

        if (words == null || mode == null || words.isEmpty() || mode.isEmpty())
        {
            return new SearchResult(Collections.emptyList(), 0);
        }

        if (mode.equals("ollama")) // TODO:OLLAMA
        {
            System.out.println("This is ollama mode ....");
        }

        Map<String, Integer> scoreDocs = calculateScore(words);

        List<SearchItem> resultList = new ArrayList<>();

        for (Map.Entry<String, Integer> scoreDoc : scoreDocs.entrySet())
        {
            String docId = scoreDoc.getKey();
            int score = scoreDoc.getValue();
            String snippet = "..."; // TODO:SNIPPET

            SearchItem res = new SearchItem(docId, score, snippet);
            resultList.add(res);
        }

        resultList.sort(Comparator.comparingInt(SearchItem::score).reversed());

        long end = System.currentTimeMillis() - start;

        SearchResult results = new SearchResult(resultList, end);

        return results;
    }

    private Map<String, Integer> calculateScore (List<String> words)
    {
        Map<String, Integer> scores = new HashMap<>();

        for (String word : words)
        {
            Map<String, Integer> freq = table.search(word);

            for (Map.Entry<String, Integer> set : freq.entrySet())
            {
                scores.merge(set.getKey(), set.getValue(), Integer::sum);
            }
        }

        return scores;
    }

    public HashTable getTable()
    {
        return this.table;
    }
}
