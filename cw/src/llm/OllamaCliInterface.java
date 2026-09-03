package llm;

public interface OllamaCliInterface
{
    String generate(String prompt) throws Exception;
    boolean isAlive();
}
