package org.zaproxy.zap.extension.prettify;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Shared background executor used to prepare message text off the Event Dispatch Thread.
 *
 * <p>Document preparation is asynchronous: work is submitted here and the result is published
 * back on the EDT, while the caller cancels stale work by bumping a generation counter (so only
 * the newest message in a burst is rendered).
 *
 * <p>A pool wide enough for the machine is used so that the request and the response of a displayed
 * message, and any further messages the user browses to, prepare in parallel instead of queueing
 * behind each other. The threads are daemons and the executor is recreated on demand, which keeps
 * it safe across add-on unload/reload cycles. The size can be tuned with the {@code
 * prettify.workers} system property.
 */
final class PrettifyExecutor {

    private static final int WORKERS = workers();

    private static final AtomicInteger THREAD_COUNT = new AtomicInteger();

    private static ExecutorService executor;

    private PrettifyExecutor() {}

    /**
     * Workers for message preparation. One per core, and never fewer than two, so that a request
     * and its response prepare side by side on any machine; never more than eight, since the work
     * is CPU-bound and the machine also serves its UI thread. An explicit {@code prettify.workers}
     * property (1 or more) wins.
     */
    private static int workers() {
        int configured = Integer.getInteger("prettify.workers", 0);
        if (configured > 0) {
            return configured;
        }
        int cores = Runtime.getRuntime().availableProcessors();
        return Math.max(2, Math.min(8, cores));
    }


    static synchronized ExecutorService get() {
        if (executor == null || executor.isShutdown()) {
            executor =
                    Executors.newFixedThreadPool(
                            WORKERS,
                            runnable -> {
                                Thread thread =
                                        new Thread(
                                                runnable,
                                                "Prettify-preparer-"
                                                        + THREAD_COUNT.incrementAndGet());
                                thread.setDaemon(true);
                                return thread;
                            });
        }
        return executor;
    }

    static synchronized void shutdown() {
        if (executor != null) {
            executor.shutdownNow();
            executor = null;
        }
    }
}
