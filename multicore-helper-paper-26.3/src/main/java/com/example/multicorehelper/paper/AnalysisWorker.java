package com.example.multicorehelper.paper;

import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;

/** Owns one bounded executor; at most one snapshot is retained for analysis. */
public final class AnalysisWorker implements AutoCloseable {
    private final ThreadPoolExecutor executor;
    private final Function<Snapshot, Analysis.Report> analyze;
    private final AtomicBoolean busy = new AtomicBoolean();
    private final AtomicLong skipped = new AtomicLong();
    private boolean closed;
    private volatile Analysis.Report latest;
    private volatile String lastError = "none";
    public AnalysisWorker() { this(Analysis::analyze); }
    AnalysisWorker(Function<Snapshot, Analysis.Report> analyze) {
        this.analyze = analyze;
        executor = new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS, new ArrayBlockingQueue<>(1), r -> {
            var thread = new Thread(r, "MultiCoreHelper-analysis");
            thread.setDaemon(true);
            thread.setPriority(Thread.MIN_PRIORITY);
            return thread;
        }, new ThreadPoolExecutor.AbortPolicy());
    }
    public synchronized boolean submit(Snapshot snapshot) {
        if (closed) return false;
        if (!busy.compareAndSet(false, true)) { skipped.incrementAndGet(); return false; }
        try {
            executor.execute(() -> {
                try { latest = analyze.apply(snapshot); lastError = "none"; }
                catch (RuntimeException e) { lastError = e.getClass().getSimpleName(); }
                finally { busy.set(false); }
            });
            return true;
        } catch (RejectedExecutionException e) { busy.set(false); skipped.incrementAndGet(); return false; }
    }
    public Analysis.Report latest() { return latest; }
    public boolean busy() { return busy.get(); }
    public long skipped() { return skipped.get(); }
    public String lastError() { return lastError; }
    public boolean terminated() { return executor.isTerminated(); }
    @Override public synchronized void close() {
        closed = true;
        executor.shutdownNow();
        try { executor.awaitTermination(1, TimeUnit.SECONDS); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }
}
