package com.example.forcedmulticore;

import net.minecraftforge.common.config.Configuration;

import java.io.File;

final class CommonConfig {
    final boolean enabled;
    final int workerThreads;
    final int reserveThreadsForSystem;
    final int maxWorkerThreads;
    final boolean tuneForkJoinPool;
    final boolean tuneNettyThreads;
    final boolean overrideExistingProperties;
    final boolean preWarmCommonPool;
    final boolean parallelEntityAnalysis;
    final int analysisIntervalTicks;
    final int maxEntitiesPerAnalysis;
    final int entityBatchSize;
    final boolean entityPressureGuard;
    final int entityPressureIntervalTicks;
    final int entityWarningThreshold;
    final int entityEmergencyThreshold;
    final boolean autoCleanupLooseEntities;
    final int maxAutoCleanupPerPass;
    final int manualCleanupLimit;

    private CommonConfig(boolean enabled, int workerThreads, int reserveThreadsForSystem, int maxWorkerThreads,
                         boolean tuneForkJoinPool, boolean tuneNettyThreads, boolean overrideExistingProperties,
                         boolean preWarmCommonPool, boolean parallelEntityAnalysis, int analysisIntervalTicks,
                         int maxEntitiesPerAnalysis, int entityBatchSize, boolean entityPressureGuard,
                         int entityPressureIntervalTicks, int entityWarningThreshold, int entityEmergencyThreshold,
                         boolean autoCleanupLooseEntities, int maxAutoCleanupPerPass, int manualCleanupLimit) {
        this.enabled = enabled;
        this.workerThreads = workerThreads;
        this.reserveThreadsForSystem = reserveThreadsForSystem;
        this.maxWorkerThreads = maxWorkerThreads;
        this.tuneForkJoinPool = tuneForkJoinPool;
        this.tuneNettyThreads = tuneNettyThreads;
        this.overrideExistingProperties = overrideExistingProperties;
        this.preWarmCommonPool = preWarmCommonPool;
        this.parallelEntityAnalysis = parallelEntityAnalysis;
        this.analysisIntervalTicks = analysisIntervalTicks;
        this.maxEntitiesPerAnalysis = maxEntitiesPerAnalysis;
        this.entityBatchSize = entityBatchSize;
        this.entityPressureGuard = entityPressureGuard;
        this.entityPressureIntervalTicks = entityPressureIntervalTicks;
        this.entityWarningThreshold = entityWarningThreshold;
        this.entityEmergencyThreshold = entityEmergencyThreshold;
        this.autoCleanupLooseEntities = autoCleanupLooseEntities;
        this.maxAutoCleanupPerPass = maxAutoCleanupPerPass;
        this.manualCleanupLimit = manualCleanupLimit;
    }

    static CommonConfig defaults() {
        return new CommonConfig(true, 0, 2, 4, false, false, false, false,
                true, 100, 2048, 256, true, 100, 2500, 5000,
                false, 512, 2048);
    }

    static CommonConfig load(File file) {
        Configuration cfg = new Configuration(file);
        cfg.load();
        CommonConfig loaded = new CommonConfig(
                cfg.getBoolean("enabled", "cpu", true, "Enable safe multicore tuning hints."),
                cfg.getInt("workerThreads", "cpu", 0, 0, 256, "Number of worker threads to request. Set to 0 to auto-detect CPU cores."),
                cfg.getInt("reserveThreadsForSystem", "cpu", 2, 0, 64, "Legacy setting. Safe automatic mode uses at most half the logical processors."),
                cfg.getInt("maxWorkerThreads", "cpu", 4, 1, 256, "Requested limit; safe mode always caps Helper workers at 4."),
                cfg.getBoolean("tuneForkJoinPool", "cpu", false, "Legacy setting; global ForkJoin tuning is disabled in safe mode."),
                cfg.getBoolean("tuneNettyThreads", "cpu", false, "Legacy setting; global Netty tuning is disabled in safe mode."),
                cfg.getBoolean("overrideExistingProperties", "cpu", false, "Override JVM -D properties if they already exist."),
                cfg.getBoolean("preWarmCommonPool", "cpu", false, "Legacy setting; common-pool prewarming is disabled in safe mode."),
                cfg.getBoolean("parallelEntityAnalysis", "experimentalMultithreading", true, "Run multi-threaded entity snapshot analysis on worker threads. World data is captured on the server thread."),
                cfg.getInt("analysisIntervalTicks", "experimentalMultithreading", 100, 20, 20 * 60, "How often to dispatch parallel entity analysis. Default: five seconds."),
                cfg.getInt("maxEntitiesPerAnalysis", "experimentalMultithreading", 2048, 1, 100000, "Maximum entity snapshots to process per analysis pass."),
                cfg.getInt("entityBatchSize", "experimentalMultithreading", 256, 1, 8192, "How many entity snapshots each worker task receives."),
                cfg.getBoolean("entityPressureGuard", "entityPressureGuard", true, "Track entity counts and warn when too many entities may cause lag, watchdog timeouts, or crashes."),
                cfg.getInt("entityPressureIntervalTicks", "entityPressureGuard", 100, 20, 20 * 60, "How often to inspect entity pressure."),
                cfg.getInt("entityWarningThreshold", "entityPressureGuard", 2500, 1, 1000000, "Log a warning when total loaded entities reaches this value."),
                cfg.getInt("entityEmergencyThreshold", "entityPressureGuard", 5000, 1, 1000000, "If autoCleanupLooseEntities is enabled, cleanup starts when total loaded entities reaches this value."),
                cfg.getBoolean("autoCleanupLooseEntities", "entityPressureGuard", false, "Automatically remove dropped items and XP orbs during emergency entity pressure."),
                cfg.getInt("maxAutoCleanupPerPass", "entityPressureGuard", 512, 1, 100000, "Maximum dropped item / XP orb entities to remove during one automatic cleanup pass."),
                cfg.getInt("manualCleanupLimit", "entityPressureGuard", 2048, 1, 100000, "Maximum dropped item / XP orb entities removed by /multicorecpu cleanup."));
        if (cfg.hasChanged()) {
            cfg.save();
        }
        return loaded;
    }
}
