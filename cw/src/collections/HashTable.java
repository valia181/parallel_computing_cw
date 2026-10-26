package collections;

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

    public boolean addWord(String word, String docId, int freq)
    {
        checkWord(word);
        if (docId == null || freq < 0)
        {
            throw new IllegalArgumentException("Invalid input");
        }
        int hash = getHash(word);

        while (true)
        {
            Node head = table.get(hash);

            if (head == null)
            {
                Node node = new Node(word);
                node.addFreq(docId, freq);
                if (table.compareAndSet(hash, null, node))
                {
                    return true;
                }
                continue;
            }

            synchronized (head)
            {
                Node current = head;
                while (true) {
                    if (current.key.equals(word))
                    {
                        current.addFreq(docId, freq);
                        return true;
                    }
                    if (current.nextNode == null)
                    {
                        Node node = new Node(word);
                        node.addFreq(docId, freq);
                        current.nextNode = node;
                        return true;
                    }
                    current = current.nextNode;
                }
            }
        }
    }

    private int getHash(String word) {
        return (word.hashCode() & 0x7fffffff) % size;
    }

    public Map<String, Integer> search(String word)
    {
        checkWord(word);
        Node current = table.get(getHash(word));

        while (current != null)
        {
            if (current.key.equals(word))
            {
                return current.getFrequencies();
            }
            current = current.nextNode;
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

    public void clear()
    {
        for (int i = 0; i < size; i++)
        {
            table.set(i, null);
        }
    }
}