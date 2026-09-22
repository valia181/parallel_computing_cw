package core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.*;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

class IndexingTest
{
    @Test
    void constructorInvalidSize()
    {
        assertThrows(IllegalArgumentException.class, () -> new Indexing(0));
        assertThrows(IllegalArgumentException.class, () -> new Indexing(-1));
    }

    @Test
    void buildInvalidThread(@TempDir Path dir) throws IOException
    {
        Path file = dir.resolve("doc1.txt");
        Files.writeString(file, "word1 word2");
        Indexing indexing = new Indexing(16);

        assertThrows(IllegalArgumentException.class,
                () -> indexing.buildIndex(List.of(file), 0));
    }

    @Test
    void indexesWordsFromFile(@TempDir Path dir) throws IOException
    {
        Path file = dir.resolve("doc1.txt");
        Files.writeString(file, "cat dog cat1 dog dog1 cat boba");

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
        Files.writeString(dir.resolve("doc1.txt"), "cat dog cat");
        Files.writeString(dir.resolve("doc2.txt"), "cat boba");
        Files.writeString(dir.resolve("doc3.txt"), "dog boba");

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
        for (int i = 0; i < 20; i++)
        {
            Files.writeString(dir.resolve("doc" + i + ".txt"), "word" + (i % 5) + " cat");
        }
        List<Path> files;
        try (Stream<Path> list = Files.list(dir))
        {
            files = list.filter(Files::isRegularFile).toList();
        }

        for (int threads : new int[]{1, 2, 4, 8})
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
        Files.writeString(dir.resolve("good1.txt"), "cat");
        Files.writeString(dir.resolve("good2.txt"), "dog");
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
        Files.writeString(dir.resolve("doc1.txt"), "cat dog");
        Indexing indexing = new Indexing(16);
        indexing.buildIndex(List.of(dir.resolve("doc1.txt")), 1);

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
        Files.writeString(dir.resolve("doc1.txt"), "cat dog");
        Indexing indexing = new Indexing(16);
        indexing.buildIndex(List.of(dir.resolve("doc1.txt")), 1);

        Indexing.SearchResult result = indexing.search(List.of("elephant"), "keyword");

        assertTrue(result.results().isEmpty());
    }

    @Test
    void searchSingleTermInSingleDocument(@TempDir Path dir) throws IOException
    {
        Files.writeString(dir.resolve("doc1.txt"), "cat cat cat dog");
        Indexing indexing = new Indexing(16);
        indexing.buildIndex(List.of(dir.resolve("doc1.txt")), 1);

        Indexing.SearchResult result = indexing.search(List.of("cat"), "keyword");

        assertEquals(1, result.results().size());
        assertEquals("doc1.txt", result.results().get(0).docId());
        assertEquals(3, result.results().get(0).score());
    }

    @Test
    void searchRanksDocuments(@TempDir Path dir) throws IOException
    {
        Files.writeString(dir.resolve("doc1.txt"), "dog dog dog");
        Files.writeString(dir.resolve("doc2.txt"), "cat dog cat dog");
        Files.writeString(dir.resolve("doc3.txt"), "cat");

        List<Path> files = List.of(dir.resolve("doc1.txt"), dir.resolve("doc2.txt"), dir.resolve("doc3.txt"));
        Indexing indexing = new Indexing(16);
        indexing.buildIndex(files, 2);

        Indexing.SearchResult result = indexing.search(List.of("cat", "dog"), "keyword");

        assertEquals(3, result.results().size());

        var results = result.results();
        assertEquals("doc2.txt", results.get(0).docId());
        assertEquals(4, results.get(0).score());

        assertEquals("doc1.txt", results.get(1).docId());
        assertEquals(3, results.get(1).score());

        assertEquals("doc3.txt", results.get(2).docId());
        assertEquals(1, results.get(2).score());
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
        Files.writeString(dir.resolve("doc1.txt"), "test word");
        Files.writeString(dir.resolve("doc2.txt"), "another word");
        Files.writeString(dir.resolve("doc3.txt"), "final word");

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
}
