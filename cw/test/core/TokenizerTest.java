package core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class TokenizerTest {

    @Test
    void tokenizeLowercasesAndSplitsOnNonLetters() {
        assertEquals(List.of("hello", "world", "again"), Tokenizer.tokenize("Hello, WORLD! again."));
    }

    @Test
    void tokenizeTreatsDigitsAndPunctuationAsSeparators() {
        assertEquals(List.of("abc", "def"), Tokenizer.tokenize("abc123def"));
        assertEquals(List.of("cats"), Tokenizer.tokenize("123 cats"));
        assertEquals(List.of("don", "t"), Tokenizer.tokenize("don't"));
    }

    @Test
    void tokenizeNeverReturnsEmptyTokens() {
        assertTrue(Tokenizer.tokenize("  ,,, 123 --- ").isEmpty());
        assertTrue(Tokenizer.tokenize("").isEmpty());
    }

    @Test
    void tokenizeHandlesNull() {
        assertTrue(Tokenizer.tokenize(null).isEmpty());
    }

    @Test
    void tokenizeSplitsOnNonAsciiLetters() {
        assertEquals(List.of("caf", "au", "lait"), Tokenizer.tokenize("café au lait"));
    }

    @Test
    void tokenizeKeepsWordAtTheEndOfText() {
        assertEquals(List.of("one", "two"), Tokenizer.tokenize("one two"));
    }

    @Test
    void countWordsCountsCaseInsensitively() {
        Map<String, Integer> counts = Tokenizer.countWords("Cat cat CAT dog");

        assertEquals(2, counts.size());
        assertEquals(3, counts.get("cat"));
        assertEquals(1, counts.get("dog"));
    }

    @Test
    void countWordsOfEmptyTextIsEmpty() {
        assertTrue(Tokenizer.countWords("").isEmpty());
        assertTrue(Tokenizer.countWords(null).isEmpty());
    }

    @Test
    void distinctTermsRemovesDuplicatesAndKeepsOrder() {
        List<String> result = Tokenizer.distinctTerms(List.of("Space", "movie", "space", "MOVIE", "a1b"));

        assertEquals(List.of("space", "movie", "a", "b"), result);
    }

    @Test
    void distinctTermsSplitsPhrasesAndSkipsEmpty() {
        assertEquals(List.of("sci", "fi"), Tokenizer.distinctTerms(List.of("sci-fi", "", "  ")));
    }

    @Test
    void distinctTermsHandlesNull() {
        assertTrue(Tokenizer.distinctTerms(null).isEmpty());
    }
}