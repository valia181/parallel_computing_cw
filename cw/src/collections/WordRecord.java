package collections;

import java.util.Collections;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class WordRecord
{
    final String word;
    final Map<String, Integer> wordFreqInDocs;

    public WordRecord(String word)
    {
        if (word == null || word.isEmpty())
        {
            throw new IllegalArgumentException("Empty word");
        }
        this.word = word;
        this.wordFreqInDocs = new ConcurrentHashMap<>();
    }

    public void addWordFreq(String docId, int freq)
    {
        if (docId == null || freq < 0)
        {
            throw new IllegalArgumentException("Invalid input");
        }
        wordFreqInDocs.merge(docId, freq, Integer::sum);
    }

    public Map<String, Integer> getFrequencies()
    {
        return Collections.unmodifiableMap(wordFreqInDocs);
    }
}