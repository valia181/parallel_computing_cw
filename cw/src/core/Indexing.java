package core;

import collections.HashTable;
import com.fasterxml.jackson.annotation.JsonProperty;
import pool.ThreadPoolInterface;
import pool.WorkerThreadPool;
import llm.OllamaWorker;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

public class Indexing
{
    private final HashTable table;
    public record IndexResponse(String status, @JsonProperty("documents_indexed") int documentsIndexed, @JsonProperty("time_ms") long timeMs) {}

    private int totalDocumentsIndexed = 0;
    private long totalIndexTimeMs = 0;
    private int totalThreadsUsed = 0;

    public record StatsResponse(
            @JsonProperty("documents") int documents,
            @JsonProperty("index_build_time_ms") long indexBuildTimeMs,
            @JsonProperty("threads_used") int threadsUsed
    ) {}

    private final OllamaWorker ollama;

    public Indexing (int size)
    {
        this(size, null);
    }

    public Indexing(int size, OllamaWorker ollama)
    {
        if (size < 1)
        {
            throw new IllegalArgumentException("Invalid arg");
        }

        table = new HashTable(size);
        this.ollama = ollama;
    }

    public IndexResponse buildIndex(List<Path> listOfDocs, int threadsCount)
    {
        if (threadsCount < 1)
        {
            throw new IllegalArgumentException("Invalid thread count");
        }

        table.clear();

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

        this.totalDocumentsIndexed = docCounts.get();
        this.totalIndexTimeMs = end;
        this.totalThreadsUsed = threadsCount;

        IndexResponse result = new IndexResponse("ok", docCounts.get(), end);
        return result;
    }

    public StatsResponse getStats()
    {
        return new StatsResponse(totalDocumentsIndexed, totalIndexTimeMs, totalThreadsUsed);
    }

    public record SearchItem (@JsonProperty("doc_id") String docId, @JsonProperty("score") int score, @JsonProperty("snippet") String snippet) {}
    public record SearchResult (@JsonProperty("results") List <SearchItem> results, @JsonProperty("time_ms") long time){}

    public SearchResult search (List<String> words, String mode)
    {
        if (words == null || mode == null || words.isEmpty() || mode.isEmpty())
        {
            return new SearchResult(Collections.emptyList(), 0);
        }

        long start = System.currentTimeMillis();

        List<String> searchWords = new ArrayList<>(words);
        if ("ollama".equals(mode) && ollama != null)
        {
            System.out.println("Synonyms from Ollama...");
            List<String> synonyms = ollama.getWords(searchWords);
            searchWords.addAll(synonyms);
        }

        Map<String, Integer> scoreDocs = calculateScore(searchWords);

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
