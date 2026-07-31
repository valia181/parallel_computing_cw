import java.util.HashMap;
import java.util.Map;

public class Node {
    public final String keyWord;
    public final int hash;
    Map<String, Integer> wordFreqInDocs = new HashMap<>();
    volatile Node nextNode;

    public Node (String word)
    {
        if (word == null)
        {
            throw new IllegalArgumentException("Invalid argument 1");
        }
        this.keyWord = word;
        this.hash = keyWord.hashCode();

        nextNode = null;
    }

    public void setNode (Node node)
    {
        nextNode = node;
    }

    public void addFreq (String docId, int freq)
    {
        if (docId == null)
        {
            throw new IllegalArgumentException("Invalid arg 2");
        }

        wordFreqInDocs.put(docId, freq);
    }
}