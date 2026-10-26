package core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntFunction;
import java.util.stream.Stream;
import llm.OllamaCliInterface;
import llm.OllamaWorker;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class IndexingTest
{
    private static final double DELTA = 1e-9;

    private static List<Path> createDocs(Path dir, int count, IntFunction<String> content)
            throws IOException
    {
        List<Path> files = new ArrayList<>();
        for (int i = 0; i < count; i++)
        {
            Path file = dir.resolve(String.format("doc%04d.txt", i));
            Files.writeString(file, content.apply(i));
            files.add(file);
        }
        return files;
    }

    private static Path write(Path dir, String name, String text) throws IOException
    {
        Path file = dir.resolve(name);
        Files.writeString(file, text);
        return file;
    }

    private static class StubCli implements OllamaCliInterface
    {
        private final String answer;
        private final boolean failing;
        final AtomicInteger calls = new AtomicInteger();

        StubCli(String answer, boolean failing)
        {
            this.answer = answer;
            this.failing = failing;
        }

        @Override
        public String generate(String prompt) throws Exception
        {
            calls.incrementAndGet();
            if (failing)
            {
                throw new IllegalStateException("LLM is down");
            }
            return answer;
        }

        @Override
        public boolean isAlive()
        {
            return !failing;
        }
    }

    @Test
    void constructorInvalidSize()
    {
        assertThrows(IllegalArgumentException.class, () -> new Indexing(0));
        assertThrows(IllegalArgumentException.class, () -> new Indexing(-1));
    }

    @Test
    void buildInvalidThread(@TempDir Path dir) throws IOException
    {
        Path file = write(dir, "doc1.txt", "word1 word2");
        Indexing indexing = new Indexing(16);

        assertThrows(IllegalArgumentException.class,
                () -> indexing.buildIndex(List.of(file), 0));
    }

    @Test
    void indexesWordsFromFile(@TempDir Path dir) throws IOException
    {
        Path file = write(dir, "doc1.txt", "cat dog cat1 dog dog1 cat boba");

        Indexing indexing = new Indexing(16);
        var result = indexing.buildIndex(List.of(file), 2);

        assertEquals("ok", result.status());
        assertEquals(1, result.documentsIndexed());

        Map<String, Integer> catFreq = indexing.getTable().search("cat");
        assertEquals(3, catFreq.get("doc1.txt"));

        Map<String, Integer> dogFreq = indexing.getTable().search("dog");
        assertEquals(3, dogFreq.get("doc1.txt"));
    }

    @Test
    void indexesFiles(@TempDir Path dir) throws IOException
    {
        write(dir, "doc1.txt", "cat dog cat");
        write(dir, "doc2.txt", "cat boba");
        write(dir, "doc3.txt", "dog boba");

        List<Path> files;
        try (Stream<Path> list = Files.list(dir))
        {
            files = list.filter(Files::isRegularFile).toList();
        }

        Indexing indexing = new Indexing(16);
        var result = indexing.buildIndex(files, 4);

        assertEquals(3, result.documentsIndexed());

        Map<String, Integer> cat = indexing.getTable().search("cat");
        assertEquals(2, cat.size());
        assertEquals(2, cat.get("doc1.txt"));
        assertEquals(1, cat.get("doc2.txt"));

        Map<String, Integer> dog = indexing.getTable().search("dog");
        assertEquals(2, dog.size());
    }

    @Test
    void resultIsConsistentRegardlessOfThreadCount(@TempDir Path dir) throws IOException
    {
        List<Path> files = createDocs(dir, 20, i -> "word" + (i % 5) + " cat");

        for (int threads : new int[] {1, 2, 4, 8})
        {
            Indexing indexing = new Indexing(64);
            var result = indexing.buildIndex(files, threads);

            assertEquals(20, result.documentsIndexed());
            assertEquals(20, indexing.getTable().search("cat").size());
        }
    }

    @Test
    void unreadableFile(@TempDir Path dir) throws IOException
    {
        write(dir, "good1.txt", "cat");
        write(dir, "good2.txt", "dog");
        Path missing = dir.resolve("missing.txt");

        List<Path> files = List.of(missing, dir.resolve("good1.txt"), dir.resolve("good2.txt"));

        Indexing indexing = new Indexing(16);
        var result = indexing.buildIndex(files, 3);

        assertEquals(2, result.documentsIndexed());
        assertFalse(indexing.getTable().search("cat").isEmpty());
        assertFalse(indexing.getTable().search("dog").isEmpty());
    }

    @Test
    void searchNullWords()
    {
        Indexing indexing = new Indexing(16);
        Indexing.SearchResult result = indexing.search(null, "keyword");

        assertTrue(result.results().isEmpty());
    }

    @Test
    void searchNullMode(@TempDir Path dir) throws IOException
    {
        Path doc = write(dir, "doc1.txt", "cat dog");
        Indexing indexing = new Indexing(16);
        indexing.buildIndex(List.of(doc), 1);

        Indexing.SearchResult result = indexing.search(List.of("cat"), null);

        assertTrue(result.results().isEmpty());
    }

    @Test
    void searchEmptyWordsList()
    {
        Indexing indexing = new Indexing(16);
        Indexing.SearchResult result = indexing.search(Collections.emptyList(), "keyword");

        assertTrue(result.results().isEmpty());
    }

    @Test
    void searchTermNotFound(@TempDir Path dir) throws IOException
    {
        Path doc = write(dir, "doc1.txt", "cat dog");
        Indexing indexing = new Indexing(16);
        indexing.buildIndex(List.of(doc), 1);

        Indexing.SearchResult result = indexing.search(List.of("elephant"), "keyword");

        assertTrue(result.results().isEmpty());
    }

    @Test
    void searchSingleTermInSingleDocument(@TempDir Path dir) throws IOException
    {
        Path doc = write(dir, "doc1.txt", "cat cat cat dog");
        Indexing indexing = new Indexing(16);
        indexing.buildIndex(List.of(doc), 1);

        Indexing.SearchResult result = indexing.search(List.of("cat"), "keyword");

        assertEquals(1, result.results().size());
        assertEquals("doc1.txt", result.results().get(0).docId());
        assertEquals(3.0, result.results().get(0).score(), DELTA);
    }

    @Test
    void searchRanksDocuments(@TempDir Path dir) throws IOException
    {
        List<Path> files = List.of(
                write(dir, "doc1.txt", "dog dog dog"),
                write(dir, "doc2.txt", "cat dog cat dog"),
                write(dir, "doc3.txt", "cat"));
        Indexing indexing = new Indexing(16);
        indexing.buildIndex(files, 2);

        Indexing.SearchResult result = indexing.search(List.of("cat", "dog"), "keyword");

        assertEquals(3, result.results().size());

        var results = result.results();
        assertEquals("doc2.txt", results.get(0).docId());
        assertEquals(4.0, results.get(0).score(), DELTA);

        assertEquals("doc1.txt", results.get(1).docId());
        assertEquals(3.0, results.get(1).score(), DELTA);

        assertEquals("doc3.txt", results.get(2).docId());
        assertEquals(1.0, results.get(2).score(), DELTA);
    }

    @Test
    void initialStatsAreZero()
    {
        Indexing indexing = new Indexing(16);
        Indexing.StatsResponse stats = indexing.getStats();

        assertEquals(0, stats.documents());
        assertEquals(0, stats.indexBuildTimeMs());
        assertEquals(0, stats.threadsUsed());
    }

    @Test
    void statsAreUpdatedAfterIndexing(@TempDir Path dir) throws IOException
    {
        write(dir, "doc1.txt", "test word");
        write(dir, "doc2.txt", "another word");
        write(dir, "doc3.txt", "final word");

        List<Path> files;
        try (Stream<Path> list = Files.list(dir))
        {
            files = list.filter(Files::isRegularFile).toList();
        }

        Indexing indexing = new Indexing(16);
        int threads = 3;

        indexing.buildIndex(files, threads);
        Indexing.StatsResponse stats = indexing.getStats();

        assertEquals(3, stats.documents());
        assertTrue(stats.indexBuildTimeMs() >= 0);
        assertEquals(threads, stats.threadsUsed());
    }

    @Test
    void buildRejectsNullListAndTooManyThreads(@TempDir Path dir) throws IOException
    {
        Path doc = write(dir, "doc1.txt", "cat");
        Indexing indexing = new Indexing(16);

        assertThrows(IllegalArgumentException.class, () -> indexing.buildIndex(null, 2));
        assertThrows(IllegalArgumentException.class,
                () -> indexing.buildIndex(List.of(doc), Indexing.MAX_THREADS + 1));

        assertEquals("ok", indexing.buildIndex(List.of(doc), Indexing.MAX_THREADS).status());
    }

    @Test
    void failedValidationKeepsOldIndex(@TempDir Path dir) throws IOException
    {
        Path doc = write(dir, "doc1.txt", "cat");
        Indexing indexing = new Indexing(16);
        indexing.buildIndex(List.of(doc), 1);

        assertThrows(IllegalArgumentException.class, () -> indexing.buildIndex(List.of(doc), 0));

        assertEquals(1, indexing.search(List.of("cat"), "keyword").results().size());
        assertEquals(1, indexing.getStats().documents());
    }

    @Test
    void rebuildReplacesOldIndexCompletely(@TempDir Path dir) throws IOException
    {
        Path oldDoc = write(dir, "old.txt", "cat cat");
        Indexing indexing = new Indexing(16);
        indexing.buildIndex(List.of(oldDoc), 1);
        assertEquals(1, indexing.search(List.of("cat"), "keyword").results().size());

        Path freshDoc = write(dir, "fresh.txt", "dog");
        indexing.buildIndex(List.of(freshDoc), 2);

        assertTrue(indexing.search(List.of("cat"), "keyword").results().isEmpty());
        assertEquals(1, indexing.search(List.of("dog"), "keyword").results().size());

        Indexing.StatsResponse stats = indexing.getStats();
        assertEquals(1, stats.documents());
        assertEquals(2, stats.threadsUsed());
    }

    @Test
    void searchDuringRebuildNeverSeesPartialIndex(@TempDir Path dir) throws Exception
    {
        int docs = 300;
        List<Path> files = createDocs(dir, docs, i -> "cat word" + i);
        Indexing indexing = new Indexing(256);
        indexing.buildIndex(files, 2);
        assertEquals(docs, indexing.getTable().search("cat").size());

        AtomicBoolean done = new AtomicBoolean(false);
        AtomicInteger reads = new AtomicInteger();
        AtomicInteger badReads = new AtomicInteger();

        Thread reader = new Thread(() ->
        {
            while (!done.get())
            {
                int seen = indexing.getTable().search("cat").size();
                reads.incrementAndGet();
                if (seen != docs)
                {
                    badReads.incrementAndGet();
                }
            }
        });
        reader.start();

        for (int round = 0; round < 5; round++)
        {
            indexing.buildIndex(files, 4);
        }
        done.set(true);
        reader.join();

        assertTrue(reads.get() > 0);
        assertEquals(0, badReads.get(), "reader saw a half-built index");
    }

    @Test
    void concurrentBuildsAreRejectedOrSerialized(@TempDir Path dir) throws Exception
    {
        int docs = 3000;
        List<Path> files = createDocs(dir, docs, i -> "word" + (i % 50) + " cat");
        Indexing indexing = new Indexing(1024);

        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        Callable<String> task = () ->
        {
            start.await();
            try
            {
                indexing.buildIndex(files, 1);
                return "ok";
            } catch (IllegalStateException e)
            {
                return "conflict";
            }
        };

        Future<String> first = executor.submit(task);
        Future<String> second = executor.submit(task);
        start.countDown();

        List<String> outcomes = List.of(first.get(60, TimeUnit.SECONDS),
                second.get(60, TimeUnit.SECONDS));
        executor.shutdown();

        assertTrue(outcomes.contains("ok"), "at least one build must succeed: " + outcomes);
        assertEquals(docs, indexing.getStats().documents());
        assertEquals(docs, indexing.getTable().search("cat").size());

        assertEquals("ok", indexing.buildIndex(files, 2).status());
    }

    @Test
    void buildFlagIsReleasedAfterSequentialBuilds(@TempDir Path dir) throws IOException
    {
        Path doc = write(dir, "doc1.txt", "cat");
        Indexing indexing = new Indexing(16);

        for (int i = 0; i < 3; i++)
        {
            assertEquals("ok", indexing.buildIndex(List.of(doc), 1).status());
        }
    }

    @Test
    void searchNormalizesQuery(@TempDir Path dir) throws IOException
    {
        Path doc = write(dir, "doc1.txt", "cat");
        Indexing indexing = new Indexing(16);
        indexing.buildIndex(List.of(doc), 1);

        assertEquals(1, indexing.search(List.of("CAT,"), "keyword").results().size());
        assertEquals(1, indexing.search(List.of("123", "cat"), "keyword").results().size());
    }

    @Test
    void searchWithoutLettersReturnsEmptyResult(@TempDir Path dir) throws IOException
    {
        Path doc = write(dir, "doc1.txt", "cat");
        Indexing indexing = new Indexing(16);
        indexing.buildIndex(List.of(doc), 1);

        assertTrue(indexing.search(List.of("123", "!!!", ""), "keyword").results().isEmpty());
    }

    @Test
    void duplicateQueryTermsDoNotInflateScore(@TempDir Path dir) throws IOException
    {
        Path doc = write(dir, "doc1.txt", "cat cat dog");
        Indexing indexing = new Indexing(16);
        indexing.buildIndex(List.of(doc), 1);

        double single = indexing.search(List.of("cat"), "keyword").results().get(0).score();
        double repeated =
                indexing.search(List.of("cat", "CAT", "cat"), "keyword").results().get(0).score();

        assertEquals(2.0, single, DELTA);
        assertEquals(single, repeated, DELTA);
    }

    @Test
    void synonymsWeighLessThanOriginalTerms(@TempDir Path dir) throws IOException
    {
        List<Path> files = List.of(
                write(dir, "doc1.txt", "cat cat"),
                write(dir, "doc2.txt", "feline feline feline"));
        Indexing indexing = new Indexing(16, new OllamaWorker(new StubCli("feline", false)));
        indexing.buildIndex(files, 2);

        var results = indexing.search(List.of("cat"), "ollama").results();

        assertEquals(2, results.size());
        assertEquals("doc1.txt", results.get(0).docId());
        assertEquals(2.0, results.get(0).score(), DELTA);
        assertEquals("doc2.txt", results.get(1).docId());
        assertEquals(1.5, results.get(1).score(), DELTA);
    }

    @Test
    void synonymEqualToOriginalKeepsFullWeight(@TempDir Path dir) throws IOException
    {
        Path doc = write(dir, "doc1.txt", "cat cat");
        Indexing indexing = new Indexing(16);
        indexing.buildIndex(List.of(doc), 1);

        var results = indexing.searchWithExpansion(List.of("cat"), List.of("cat")).results();

        assertEquals(1, results.size());
        assertEquals(2.0, results.get(0).score(), DELTA);
    }

    @Test
    void keywordModeDoesNotCallLlm(@TempDir Path dir) throws IOException
    {
        Path doc = write(dir, "doc1.txt", "cat");
        StubCli cli = new StubCli("feline", false);
        Indexing indexing = new Indexing(16, new OllamaWorker(cli));
        indexing.buildIndex(List.of(doc), 1);

        indexing.search(List.of("cat"), "keyword");

        assertEquals(0, cli.calls.get());
    }

    @Test
    void ollamaModeCallsLlmOnce(@TempDir Path dir) throws IOException
    {
        Path doc = write(dir, "doc1.txt", "cat");
        StubCli cli = new StubCli("feline", false);
        Indexing indexing = new Indexing(16, new OllamaWorker(cli));
        indexing.buildIndex(List.of(doc), 1);

        indexing.search(List.of("cat"), "ollama");

        assertEquals(1, cli.calls.get());
    }

    @Test
    void searchWorksWhenLlmFails(@TempDir Path dir) throws IOException
    {
        Path doc = write(dir, "doc1.txt", "cat");
        Indexing indexing = new Indexing(16, new OllamaWorker(new StubCli("", true)));
        indexing.buildIndex(List.of(doc), 1);

        var results = indexing.search(List.of("cat"), "ollama").results();

        assertEquals(1, results.size());
        assertEquals(1.0, results.get(0).score(), DELTA);
    }

    @Test
    void ollamaModeWithoutWorkerFallsBackToKeyword(@TempDir Path dir) throws IOException
    {
        Path doc = write(dir, "doc1.txt", "cat");
        Indexing indexing = new Indexing(16);
        indexing.buildIndex(List.of(doc), 1);

        assertEquals(1, indexing.search(List.of("cat"), "ollama").results().size());
    }

    @Test
    void resultsAreLimitedToTopKAndSorted(@TempDir Path dir) throws IOException
    {
        // doc i містить (i + 1) входжень "cat": бали 1.0 ... 60.0
        List<Path> files = createDocs(dir, 60, i -> "cat ".repeat(i + 1));
        Indexing indexing = new Indexing(64);
        indexing.buildIndex(files, 4);

        var results = indexing.search(List.of("cat"), "keyword").results();

        assertEquals(Indexing.TOP_K, results.size());
        assertEquals(60.0, results.get(0).score(), DELTA);
        assertEquals(11.0, results.get(results.size() - 1).score(), DELTA);
        for (int i = 1; i < results.size(); i++)
        {
            assertTrue(results.get(i - 1).score() >= results.get(i).score());
        }
    }

    @Test
    void equalScoresAreOrderedByDocId(@TempDir Path dir) throws IOException
    {
        List<Path> files = List.of(
                write(dir, "b.txt", "cat"),
                write(dir, "c.txt", "cat"),
                write(dir, "a.txt", "cat"));
        Indexing indexing = new Indexing(16);
        indexing.buildIndex(files, 3);

        var results = indexing.search(List.of("cat"), "keyword").results();

        assertEquals(List.of("a.txt", "b.txt", "c.txt"),
                results.stream().map(Indexing.SearchItem::docId).toList());
    }

    @Test
    void shortDocumentSnippetIsWholeTextWithCollapsedWhitespace(@TempDir Path dir)
            throws IOException
    {
        Path doc = write(dir, "doc1.txt", "The CAT\nsat");
        Indexing indexing = new Indexing(16);
        indexing.buildIndex(List.of(doc), 1);

        var item = indexing.search(List.of("cat"), "keyword").results().get(0);

        assertEquals("The CAT sat", item.snippet());
    }

    @Test
    void longDocumentSnippetIsCutAroundTerm(@TempDir Path dir) throws IOException
    {
        Path doc = write(dir, "doc1.txt", "x ".repeat(100) + "needle" + " y".repeat(100));
        Indexing indexing = new Indexing(16);
        indexing.buildIndex(List.of(doc), 1);

        String snippet = indexing.search(List.of("needle"), "keyword").results().get(0).snippet();

        assertTrue(snippet.contains("needle"));
        assertTrue(snippet.startsWith("..."));
        assertTrue(snippet.endsWith("..."));
        assertTrue(snippet.length() <= 2 * 80 + "needle".length() + 6, "snippet too long");
    }

    @Test
    void snippetIsEmptyWhenFileDisappearedAfterIndexing(@TempDir Path dir) throws IOException
    {
        Path doc = write(dir, "doc1.txt", "cat");
        Indexing indexing = new Indexing(16);
        indexing.buildIndex(List.of(doc), 1);
        Files.delete(doc);

        var results = indexing.search(List.of("cat"), "keyword").results();

        assertEquals(1, results.size());
        assertEquals("", results.get(0).snippet());
    }
}