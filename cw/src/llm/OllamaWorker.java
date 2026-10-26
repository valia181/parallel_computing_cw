package llm;

import core.Tokenizer;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class OllamaWorker {
    private static final int MAX_TERMS = 5;
    private static final Set<String> JUNK_WORDS =
            Set.of(
                    "here", "are", "is", "some", "synonym", "synonyms", "keyword", "keywords", "related",
                    "word", "words", "phrase", "sure", "following", "okay", "ok", "these", "the", "and",
                    "for", "of", "a", "an", "to", "in", "with", "or", "as");

    private final OllamaCliInterface cli;

    public OllamaWorker(OllamaCliInterface cli) {
        this.cli = cli;
    }

    public synchronized List<String> getWords(List<String> words) {
        List<String> newList = new ArrayList<>();
        if (words == null || words.isEmpty()) {
            return newList;
        }

        try {
            Set<String> known = new HashSet<>(Tokenizer.distinctTerms(words));
            String query = String.join(" ", words);
            String prompt =
                    "Provide 3-5 single-word synonyms or highly related keywords for the phrase: '"
                            + query
                            + "'. Return ONLY the words separated by spaces. No explanation. "
                            + "Do not repeat words.";

            String generated = cli.generate(prompt);
            for (String word : Tokenizer.tokenize(generated)) {
                if (newList.size() >= MAX_TERMS) {
                    break;
                }
                if (!JUNK_WORDS.contains(word) && known.add(word)) {
                    newList.add(word);
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            System.err.println("Some error occurred: " + e.getMessage());
        }

        return newList;
    }
}