import java.util.LinkedList;

public class WorkerThreadPool implements ThreadPoolInterface
{
    private final LinkedList<Runnable> tasks = new LinkedList<>();
    private final Thread[] workers;
    private volatile boolean running;

    WorkerThreadPool(int numThreads)
    {
        this.workers = new Thread[numThreads];
        this.running = true;

        for (int i = 0; i < numThreads; i++)
        {
            workers[i] = new Thread(this::routine);
            workers[i].start();
        }
    }

    @Override
    public synchronized void runTask(Runnable task)
    {
        tasks.addLast(task);
        notify();
    }

    private void routine()
    {
        while (true)
        {
            Runnable task;
            synchronized (this)
            {
                while (tasks.isEmpty() && running)
                {
                    try
                    {
                        wait();
                    }
                    catch (InterruptedException e)
                    {
                        return;
                    }
                }
                if (!running && tasks.isEmpty())
                {
                    return;
                }
                task = tasks.removeFirst();
            }

            try
            {
                task.run();
            } catch (Throwable e)
            {
                System.err.println("Task failed: " + e);
            }
        }
    }

    @Override
    public void stopPool()
    {
        synchronized (this)
        {
            running = false;

            notifyAll();
        }
    }

    @Override
    public void waitTermination()
    {
        for (Thread worker : workers)
        {
            try {
                if (worker != null)
                {
                    worker.join();
                }

            } catch (InterruptedException e)
            {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }
}
