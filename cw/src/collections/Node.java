package collections;

public class Node
{
    public final String key;
    WordRecord record;
    volatile Node nextNode;

    public Node (String word)
    {
        key = word;
        record = new WordRecord(word);
        nextNode = null;
    }

    public void addFreq (String docId, int freq)
    {
        record.addWordFreq(docId, freq);
    }
}