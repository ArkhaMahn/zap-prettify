package org.zaproxy.zap.extension.prettify;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Shared background executor used to prepare message text off the Event Dispatch Thread.
 *
 * <p>Mirrors Burp's approach of doing document preparation asynchronously: work is submitted here
 * and the result is published back on the EDT, while the caller cancels stale work by bumping a
 * generation counter (so only the newest message in a burst is rendered).
 *
 * <p>A small pool (not a single thread) is used so that showing a request and its response in quick
 * succession does not serialise the two preparations behind each other. The threads are daemons and
 * the executor is recreated on demand, which keeps it safe across add-on unload/reload cycles.
 */
final class PrettifyExecutor {

    private static final int WORKERS =
            Math.max(2, Math.min(4, Runtime.getRuntime().availableProcessors() / 2));

    private static final AtomicInteger THREAD_COUNT = new AtomicInteger();

    private static ExecutorService executor;

    private PrettifyExecutor() {}

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
