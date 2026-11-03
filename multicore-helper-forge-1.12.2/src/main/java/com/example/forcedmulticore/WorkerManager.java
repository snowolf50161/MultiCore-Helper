package com.example.forcedmulticore;

import org.apache.logging.log4j.Logger;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

final class WorkerManager {
    private static final String FORK_JOIN_PARALLELISM = "java.util.concurrent.ForkJoinPool.common.parallelism";
    private static final String NETTY_EVENT_LOOP_THREADS = "io.netty.eventLoopThreads";

    private final Logger logger;
    private final ExecutorService pool;
    private final TuningReport tuningReport;
    private final AtomicBoolean analysisRunning = new AtomicBoolean(false);
    private final AtomicLong jobsSubmitted = new AtomicLong();
    private final AtomicLong jobsCompleted = new AtomicLong();
    private final AtomicLong snapshotsProcessed = new AtomicLong();
    private final AtomicLong lastAnalysisNanos = new AtomicLong();
    private volatile BackgroundAnalysisResult lastAnalysis = BackgroundAnalysisResult.empty();

    private WorkerManager(Logger logger, ExecutorService pool, TuningReport tuningReport) {
        this.logger = logger;
        this.pool = pool;
        this.tuningReport = tuningReport;
    }

    static WorkerManager notStarted(Logger logger) {
        return new WorkerManager(logger, null, TuningReport.notApplied());
    }

    static WorkerManager start(CommonConfig config, Logger logger) {
        TuningReport report = applyTuning(config, logger);
        ThreadPoolExecutor pool = report.workerThreads <= 0 ? null : new ThreadPoolExecutor(
                1,
                report.workerThreads,
                30L,
                TimeUnit.SECONDS,
                new LinkedBlockingQueue<Runnable>(64),
                new NamedThreadFactory());
        if (pool != null) {
            pool.allowCoreThreadTimeOut(true);
        }
        return new WorkerManager(logger, pool, report);
    }

    TuningReport getTuningReport() {
        return tuningReport;
    }

    BackgroundAnalysisResult getLastAnalysis() {
        return lastAnalysis;
    }

    synchronized void dispatchEntityAnalysis(List<EntitySnapshot> snapshots, int batchSize) {
        if (pool == null || pool.isShutdown() || pool.isTerminated()
                || snapshots.isEmpty() || analysisRunning.get()) {
            return;
        }

        analysisRunning.set(true);
        long startNanos = System.nanoTime();
        List<List<EntitySnapshot>> batches = partition(snapshots, Math.max(1, batchSize));
        adaptWorkerCount(batches.size());
        List<CompletableFuture<BatchResult>> futures = new ArrayList<CompletableFuture<BatchResult>>();

        for (final List<EntitySnapshot> batch : batches) {
            try {
                CompletableFuture<BatchResult> future = CompletableFuture
                        .supplyAsync(() -> analyzeBatch(batch), pool)
                        .whenComplete((result, throwable) -> {
                            jobsCompleted.incrementAndGet();
                            if (throwable != null) {
                                logger.warn("[MultiCore Helper] Worker task failed", throwable);
                            }
                        });
                futures.add(future);
                jobsSubmitted.incrementAndGet();
            } catch (RejectedExecutionException rejected) {
                logger.debug("[MultiCore Helper] Skipped entity analysis while worker pool was stopping");
                break;
            }
        }

        if (futures.isEmpty()) {
            analysisRunning.set(false);
            return;
        }

        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).whenComplete((ignored, throwable) -> {
            try {
                BackgroundAnalysisResult merged = merge(futures);
                lastAnalysis = merged;
                snapshotsProcessed.addAndGet(merged.entityCount);
                lastAnalysisNanos.set(System.nanoTime() - startNanos);
            } finally {
                analysisRunning.set(false);
            }
        });
    }

    synchronized void shutdown() {
        if (pool != null) {
            pool.shutdownNow();
        }
        analysisRunning.set(false);
    }

    String describeProperties() {
        return "ForkJoin parallelism=" + System.getProperty(FORK_JOIN_PARALLELISM, "default")
                + ", Netty eventLoopThreads=" + System.getProperty(NETTY_EVENT_LOOP_THREADS, "default");
    }

    String describePool() {
        if (pool instanceof ThreadPoolExecutor) {
            ThreadPoolExecutor threadPool = (ThreadPoolExecutor) pool;
            return "workerPool=adaptive=" + threadPool.getPoolSize() + "/" + threadPool.getMaximumPoolSize()
                    + ", target=" + threadPool.getCorePoolSize()
                    + ", active=" + threadPool.getActiveCount()
                    + ", queued=" + threadPool.getQueue().size()
                    + ", jobsSubmitted=" + jobsSubmitted.get()
                    + ", jobsCompleted=" + jobsCompleted.get()
                    + ", snapshotsProcessed=" + snapshotsProcessed.get()
                    + ", lastTimeMs=" + String.format(Locale.ROOT, "%.3f", lastAnalysisNanos.get() / 1_000_000.0D);
        }
        return "workerPool=not started";
    }

    private void adaptWorkerCount(int batchCount) {
        if (!(pool instanceof ThreadPoolExecutor)) {
            return;
        }
        ThreadPoolExecutor threadPool = (ThreadPoolExecutor) pool;
        int desiredWorkers = clamp((batchCount + 1) / 2, 1, threadPool.getMaximumPoolSize());
        if (threadPool.getCorePoolSize() != desiredWorkers) {
            threadPool.setCorePoolSize(desiredWorkers);
        }
    }

    private static TuningReport applyTuning(CommonConfig config, Logger logger) {
        int detectedProcessors = Math.max(1, Runtime.getRuntime().availableProcessors());
        if (!config.enabled) {
            return new TuningReport(false, detectedProcessors, 0, "disabled in config");
        }

        int automaticThreads = Math.max(1, Math.min(4, detectedProcessors / 2));
        int workerThreads = config.workerThreads > 0 ? config.workerThreads : automaticThreads;
        workerThreads = clamp(workerThreads, 1, Math.min(4, config.maxWorkerThreads));

        return new TuningReport(true, detectedProcessors, workerThreads,
                "safe mode; adaptive workers 1-" + workerThreads + "; global ForkJoin/Netty tuning disabled");
    }

    private static BatchResult analyzeBatch(List<EntitySnapshot> snapshots) {
        Map<String, Integer> countsByType = new HashMap<String, Integer>();
        int loose = 0;
        for (EntitySnapshot snapshot : snapshots) {
            if (snapshot.loose) {
                loose++;
            }
            Integer current = countsByType.get(snapshot.typeName);
            countsByType.put(snapshot.typeName, current == null ? 1 : current + 1);
        }
        return new BatchResult(snapshots.size(), loose, countsByType);
    }

    private static BackgroundAnalysisResult merge(List<CompletableFuture<BatchResult>> futures) {
        Map<String, Integer> countsByType = new HashMap<String, Integer>();
        int total = 0;
        int loose = 0;

        for (CompletableFuture<BatchResult> future : futures) {
            if (future.isCompletedExceptionally() || future.isCancelled()) {
                continue;
            }
            BatchResult result = future.join();
            total += result.entityCount;
            loose += result.looseEntities;
            for (Map.Entry<String, Integer> entry : result.countsByType.entrySet()) {
                Integer current = countsByType.get(entry.getKey());
                countsByType.put(entry.getKey(), current == null ? entry.getValue() : current + entry.getValue());
            }
        }

        List<Map.Entry<String, Integer>> topTypes = new ArrayList<Map.Entry<String, Integer>>(countsByType.entrySet());
        Collections.sort(topTypes, new Comparator<Map.Entry<String, Integer>>() {
            @Override
            public int compare(Map.Entry<String, Integer> left, Map.Entry<String, Integer> right) {
                return right.getValue().compareTo(left.getValue());
            }
        });
        if (topTypes.size() > 5) {
            topTypes = new ArrayList<Map.Entry<String, Integer>>(topTypes.subList(0, 5));
        }

        return new BackgroundAnalysisResult(total, loose, topTypes);
    }

    private static List<List<EntitySnapshot>> partition(List<EntitySnapshot> snapshots, int batchSize) {
        List<List<EntitySnapshot>> batches = new ArrayList<List<EntitySnapshot>>();
        for (int index = 0; index < snapshots.size(); index += batchSize) {
            batches.add(snapshots.subList(index, Math.min(snapshots.size(), index + batchSize)));
        }
        return batches;
    }

    private static void setPropertyIfAllowed(String key, String value, boolean overrideExisting, Logger logger) {
        String currentValue = System.getProperty(key);
        if (overrideExisting || currentValue == null || currentValue.trim().isEmpty()) {
            System.setProperty(key, value);
            logger.debug("[MultiCore Helper] Set {}={}", key, value);
        } else {
            logger.debug("[MultiCore Helper] Kept existing {}={}", key, currentValue);
        }
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static final class NamedThreadFactory implements ThreadFactory {
        private final AtomicInteger nextId = new AtomicInteger(1);

        @Override
        public Thread newThread(Runnable runnable) {
            Thread thread = new Thread(runnable, "MultiCoreHelper-Worker-" + nextId.getAndIncrement());
            thread.setDaemon(true);
            thread.setPriority(Math.max(Thread.MIN_PRIORITY, Thread.NORM_PRIORITY - 1));
            return thread;
        }
    }

    private static final class BatchResult {
        private final int entityCount;
        private final int looseEntities;
        private final Map<String, Integer> countsByType;

        private BatchResult(int entityCount, int looseEntities, Map<String, Integer> countsByType) {
            this.entityCount = entityCount;
            this.looseEntities = looseEntities;
            this.countsByType = countsByType;
        }
    }
}
