package llm;

import java.util.ArrayList;
import java.util.List;

public class OllamaWorker
{
    private final OllamaCliInterface cli;

    public OllamaWorker (OllamaCliInterface cli)
    {
        this.cli = cli;
    }

    public synchronized List<String> getWords (List<String> words)
    {
        List<String> newList = new ArrayList<>();
        try
        {
            String query = String.join(" ", words);
            String prompt = "Provide 3-5 single-word synonyms or highly related keywords for the phrase: '"
                    + query + "'. Return ONLY the words separated by spaces. No explanation. Do not repeat words.";

            String generated = cli.generate(prompt);
            String[] newWords = generated.toLowerCase().split("[^a-z]+");
            for (String word : newWords)
            {
                if (!word.isEmpty() && !words.contains(word) && !newList.contains(word))
                {
                    newList.add(word);
                }
            }
        } catch (Exception e)
        {
            System.err.println("Some error occurred: " + e.getMessage());
        }

        return newList;
    }
}
