import java.util.Collections;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReferenceArray;

public class HashTable
{
    private final int size;
    private final AtomicReferenceArray<Node> table;

    public HashTable(int size)
    {
        if (size <= 0)
        {
            throw new IllegalArgumentException("Invalid size of table");
        }

        this.size = size;
        this.table = new AtomicReferenceArray<>(size);
    }

    public boolean addWord (String word, String docId, int freq)
    {
        checkWord(word);
        Node node = new Node(word);
        node.addFreq(docId, freq);
        int hash = getHash(word);

        boolean success = table.compareAndSet(hash, null, node);
        if (success)
        {
            return true;
        } else
        {
            Node head = table.get(hash);
            synchronized (head)
            {
                Node nodeCurr = head;
                while (true)
                {
                    if (nodeCurr.key.equals(word))
                    {
                        nodeCurr.addFreq(docId, freq);
                        return true;
                    }

                    if (nodeCurr.nextNode == null)
                    {
                        break;
                    }

                    nodeCurr = nodeCurr.nextNode;
                }

                nodeCurr.nextNode = node;
                return true;
            }
        }
    }

    private int getHash (String word)
    {
        int hash = (word.hashCode() & 0x7fffffff) % size;
        return hash;
    }

    public Map<String, Integer> search(String word)
    {
        checkWord(word);
        int hash = getHash(word);

        Node nodeCurr = table.get(hash);

        while (nodeCurr != null)
        {
            if (nodeCurr.key.equals(word))
            {
                return nodeCurr.record.wordFreqInDocs;
            }

            nodeCurr = nodeCurr.nextNode;
        }

        return Collections.emptyMap();
    }

    private void checkWord(String word)
    {
        if (word == null || word.isEmpty())
        {
            throw new IllegalArgumentException("Empty word");
        }
    }
}
