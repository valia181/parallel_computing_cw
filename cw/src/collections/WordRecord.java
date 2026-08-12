package collections;

import java.util.HashMap;
import java.util.Map;

public class WordRecord
{
    final String word;
    Map<String, Integer> wordFreqInDocs;

    public WordRecord (String word)
    {
        if (word == null || word.isEmpty())
        {
            throw new IllegalArgumentException("Empty word");
        }
        this.word = word;
        wordFreqInDocs = new HashMap<>();
    }

    public void addWordFreq (String docId, int freq)
    {
        if (docId == null || freq < 0)
        {
            throw new IllegalArgumentException("Invalid input");
        }
        wordFreqInDocs.merge(docId, freq, Integer::sum);
    }
}
