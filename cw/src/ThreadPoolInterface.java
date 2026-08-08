public interface ThreadPoolInterface
{
    void runTask (Runnable task);
    void stopPool();
    void waitTermination();
}
