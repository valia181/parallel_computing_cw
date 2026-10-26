package pool;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Objects;

public class WorkerThreadPool implements ThreadPoolInterface {
    private final Object lock = new Object();
    private final Deque<Runnable> tasks = new ArrayDeque<>();
    private final Thread[] workers;
    private boolean running = true;

    public WorkerThreadPool(int numThreads) {
        if (numThreads < 1) {
            throw new IllegalArgumentException("Number of threads must be positive");
        }

        this.workers = new Thread[numThreads];
        for (int i = 0; i < numThreads; i++) {
            workers[i] = new Thread(this::routine, "pool-worker-" + i);
            workers[i].start();
        }
    }

    @Override
    public void runTask(Runnable task) {
        Objects.requireNonNull(task, "task");
        synchronized (lock) {
            if (!running) {
                throw new IllegalStateException("Pool is stopped");
            }
            tasks.addLast(task);
            lock.notify();
        }
    }

    private void routine() {
        while (true) {
            Runnable task;
            synchronized (lock) {
                while (tasks.isEmpty() && running) {
                    try {
                        lock.wait();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
                if (tasks.isEmpty()) {
                    return;
                }
                task = tasks.removeFirst();
            }

            try {
                task.run();
            } catch (Throwable e) {
                System.err.println("Task failed: " + e);
            }
        }
    }

    @Override
    public void stopPool() {
        synchronized (lock) {
            running = false;
            lock.notifyAll();
        }
    }

    @Override
    public void waitTermination() {
        for (Thread worker : workers) {
            try {
                worker.join();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }
}