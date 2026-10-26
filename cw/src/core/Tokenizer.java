package core;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;

public final class Tokenizer {
    private Tokenizer() {}

    public static List<String> tokenize(String text) {
        List<String> tokens = new ArrayList<>();
        forEachToken(text, tokens::add);
        return tokens;
    }

    public static Map<String, Integer> countWords(String text) {
        Map<String, Integer> counts = new HashMap<>();
        forEachToken(text, token -> counts.merge(token, 1, Integer::sum));
        return counts;
    }

    public static List<String> distinctTerms(Collection<String> raw) {
        LinkedHashSet<String> result = new LinkedHashSet<>();
        if (raw != null) {
            for (String item : raw) {
                result.addAll(tokenize(item));
            }
        }
        return new ArrayList<>(result);
    }

    private static void forEachToken(String text, Consumer<String> consumer) {
        if (text == null) {
            return;
        }
        int start = -1;
        int length = text.length();
        for (int i = 0; i <= length; i++) {
            boolean letter = false;
            if (i < length) {
                char c = text.charAt(i);
                letter = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z');
            }
            if (letter) {
                if (start < 0) {
                    start = i;
                }
            } else if (start >= 0) {
                consumer.accept(text.substring(start, i).toLowerCase(Locale.ROOT));
                start = -1;
            }
        }
    }
}