package collections;

import java.util.Map;

public class Node
{
    public final String key;
    final WordRecord record;
    volatile Node nextNode;

    public Node(String word)
    {
        this.key = word;
        this.record = new WordRecord(word);
    }

    public void addFreq(String docId, int freq)
    {
        record.addWordFreq(docId, freq);
    }

    public Map<String, Integer> getFrequencies()
    {
        return record.getFrequencies();
    }
}