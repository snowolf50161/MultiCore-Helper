package com.example.forcedmulticore;

import com.mojang.brigadier.CommandDispatcher;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents;
import net.fabricmc.fabric.api.itemgroup.v1.FabricItemGroup;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.entity.Entity;
import net.minecraft.entity.ExperienceOrbEntity;
import net.minecraft.entity.ItemEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemGroup;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
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

public final class MultiCoreHelperFabric implements ModInitializer {
    public static final String MOD_ID = "forcedmulticore";

    private static final String FORK_JOIN_PARALLELISM = "java.util.concurrent.ForkJoinPool.common.parallelism";
    private static final String NETTY_EVENT_LOOP_THREADS = "io.netty.eventLoopThreads";
    private static final Logger LOGGER = LoggerFactory.getLogger("MultiCore Helper");

    private static final HelperConfig CONFIG = HelperConfig.load();
    private static final boolean SODIUM_LOADED = FabricLoader.getInstance().isModLoaded("sodium");
    private static final boolean LITHIUM_LOADED = FabricLoader.getInstance().isModLoaded("lithium");
    private static final Identifier CPU_CORE_ID = Identifier.of(MOD_ID, "cpu_core");

    public static final Item CPU_CORE = Registry.register(
            Registries.ITEM,
            CPU_CORE_ID,
            new Item(new Item.Settings().registryKey(RegistryKey.of(RegistryKeys.ITEM, CPU_CORE_ID))));

    public static final ItemGroup ITEM_GROUP = Registry.register(
            Registries.ITEM_GROUP,
            Identifier.of(MOD_ID, "main"),
            FabricItemGroup.builder()
                    .displayName(Text.translatable("itemGroup.forcedmulticore"))
                    .icon(() -> new ItemStack(CPU_CORE))
                    .entries((context, entries) -> entries.add(CPU_CORE))
                    .build());

    private static volatile ExecutorService workerPool;
    private static volatile TuningReport lastReport = TuningReport.notApplied();
    private static volatile ParallelAnalysisResult lastAnalysisResult = ParallelAnalysisResult.empty();
    private static volatile EntityPressureReport lastEntityPressure = EntityPressureReport.empty();

    private static final AtomicBoolean analysisRunning = new AtomicBoolean(false);
    private static final AtomicLong jobsSubmitted = new AtomicLong();
    private static final AtomicLong jobsCompleted = new AtomicLong();
    private static final AtomicLong snapshotsProcessed = new AtomicLong();
    private static final AtomicLong entitiesCleaned = new AtomicLong();
    private static final AtomicLong lastAnalysisNanos = new AtomicLong();

    private static int serverTicks;
    private static volatile boolean serverActive;

    @Override
    public void onInitialize() {
        lastReport = applyTuning();
        CommandRegistrationCallback.EVENT.register(this::registerCommands);
        ServerLifecycleEvents.SERVER_STARTING.register(this::onServerStarting);
        ServerLifecycleEvents.SERVER_STOPPING.register(this::onServerStopping);
        ServerTickEvents.END_SERVER_TICK.register(this::onEndServerTick);
        ServerEntityEvents.ENTITY_LOAD.register(TntAdvancementTracker::onEntityLoad);

        LOGGER.info("[MultiCore Helper] {}", lastReport.toLogLine());
        LOGGER.info("[MultiCore Helper] Compatibility: Sodium={}, Lithium={}, mode={}",
                SODIUM_LOADED ? "detected" : "not detected",
                LITHIUM_LOADED ? "detected" : "not detected",
                compatibilityMode());
    }

    private void onServerStarting(MinecraftServer server) {
        serverActive = false;
        lastReport = applyTuning();
        rebuildWorkerPool(lastReport.workerThreads);
        serverTicks = 0;
        serverActive = true;
        LOGGER.info("[MultiCore Helper] Started server worker pool: {}", lastReport.toLogLine());
    }

    private void onServerStopping(MinecraftServer server) {
        serverActive = false;
        shutdownWorkerPool();
    }

    private static TuningReport applyTuning() {
        int detectedProcessors = Math.max(1, Runtime.getRuntime().availableProcessors());

        if (!CONFIG.enabled) {
            return new TuningReport(false, detectedProcessors, 0, "disabled in config");
        }

        int automaticThreads = Math.max(1, Math.min(4, detectedProcessors / 2));
        int workerThreads = CONFIG.workerThreads > 0 ? CONFIG.workerThreads : automaticThreads;
        workerThreads = clamp(workerThreads, 1, Math.min(4, CONFIG.maxWorkerThreads));

        return new TuningReport(true, detectedProcessors, workerThreads,
                "safe mode; adaptive workers 1-" + workerThreads + "; global ForkJoin/Netty tuning disabled");
    }

    private static synchronized void rebuildWorkerPool(int workerThreads) {
        ExecutorService oldPool = workerPool;
        workerPool = null;
        if (workerThreads > 0) {
            ThreadPoolExecutor newPool = new ThreadPoolExecutor(
                    1,
                    workerThreads,
                    30L,
                    TimeUnit.SECONDS,
                    new LinkedBlockingQueue<>(64),
                    new NamedThreadFactory());
            newPool.allowCoreThreadTimeOut(true);
            workerPool = newPool;
        }
        analysisRunning.set(false);

        if (oldPool != null) {
            oldPool.shutdownNow();
        }
    }

    private static synchronized void shutdownWorkerPool() {
        ExecutorService oldPool = workerPool;
        workerPool = null;
        analysisRunning.set(false);
        if (oldPool != null) {
            oldPool.shutdownNow();
        }
    }

    private static void setPropertyIfAllowed(String key, String value, boolean overrideExisting) {
        String currentValue = System.getProperty(key);
        if (overrideExisting || currentValue == null || currentValue.trim().isEmpty()) {
            System.setProperty(key, value);
            LOGGER.debug("[MultiCore Helper] Set {}={}", key, value);
        } else {
            LOGGER.debug("[MultiCore Helper] Kept existing {}={}", key, currentValue);
        }
    }

    private void registerCommands(CommandDispatcher<ServerCommandSource> dispatcher, net.minecraft.command.CommandRegistryAccess registryAccess, CommandManager.RegistrationEnvironment environment) {
        dispatcher.register(CommandManager.literal("multicorecpu")
                .then(CommandManager.literal("status")
                        .executes(context -> sendStatus(context.getSource())))
                .then(CommandManager.literal("entities")
                        .executes(context -> sendEntityStatus(context.getSource())))
                .then(CommandManager.literal("cleanup")
                        .requires(source -> source.hasPermissionLevel(2))
                        .executes(context -> cleanupLooseEntities(context.getSource()))));
    }

    private static int sendStatus(ServerCommandSource source) {
        for (String line : statusLines()) {
            source.sendFeedback(() -> Text.literal(line), false);
        }
        return 1;
    }

    private void onEndServerTick(MinecraftServer server) {
        if (!serverActive) {
            return;
        }
        serverTicks++;

        if (CONFIG.entityPressureGuard && serverTicks % CONFIG.entityPressureIntervalTicks == 0) {
            EntityPressureReport pressure = inspectEntityPressure(server);
            lastEntityPressure = pressure;
            if (pressure.totalEntities >= CONFIG.entityWarningThreshold) {
                LOGGER.warn("[MultiCore Helper] Entity pressure: {}", pressure.toHumanLine());
            }
            if (CONFIG.autoCleanupLooseEntities && pressure.totalEntities >= CONFIG.entityEmergencyThreshold) {
                int cleaned = cleanupLooseEntities(server, CONFIG.maxAutoCleanupPerPass);
                if (cleaned > 0) {
                    entitiesCleaned.addAndGet(cleaned);
                    LOGGER.warn("[MultiCore Helper] Auto-cleaned {} loose item/xp entities because entity pressure reached {}", cleaned, pressure.totalEntities);
                }
            }
        }

        if (!CONFIG.parallelEntityAnalysis || serverTicks % CONFIG.analysisIntervalTicks != 0) {
            return;
        }

        ExecutorService pool = workerPool;
        if (pool == null || pool.isShutdown() || pool.isTerminated() || analysisRunning.get()) {
            return;
        }

        List<EntitySnapshot> snapshots = captureEntitySnapshots(server, CONFIG.maxEntitiesPerAnalysis);
        if (snapshots.isEmpty()) {
            return;
        }

        analysisRunning.set(true);
        long startNanos = System.nanoTime();
        List<List<EntitySnapshot>> batches = partition(snapshots, Math.max(1, CONFIG.entityBatchSize));
        adaptWorkerCount(pool, batches.size());
        List<CompletableFuture<PartialAnalysisResult>> futures = new ArrayList<>();

        for (List<EntitySnapshot> batch : batches) {
            try {
                CompletableFuture<PartialAnalysisResult> future = CompletableFuture
                        .supplyAsync(() -> analyzeBatch(batch), pool)
                        .whenComplete((result, throwable) -> {
                            jobsCompleted.incrementAndGet();
                            if (throwable != null) {
                                LOGGER.warn("[MultiCore Helper] Worker task failed", throwable);
                            }
                        });
                futures.add(future);
                jobsSubmitted.incrementAndGet();
            } catch (RejectedExecutionException rejected) {
                LOGGER.debug("[MultiCore Helper] Skipped entity analysis while worker pool was stopping");
                break;
            }
        }

        if (futures.isEmpty()) {
            analysisRunning.set(false);
            return;
        }

        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).whenComplete((ignored, throwable) -> {
            try {
                ParallelAnalysisResult merged = ParallelAnalysisResult.merge(futures);
                lastAnalysisResult = merged;
                snapshotsProcessed.addAndGet(merged.entityCount);
                lastAnalysisNanos.set(System.nanoTime() - startNanos);
            } finally {
                analysisRunning.set(false);
            }
        });
    }

    private static int sendEntityStatus(ServerCommandSource source) {
        MinecraftServer server = source.getServer();
        lastEntityPressure = inspectEntityPressure(server);
        source.sendFeedback(() -> Text.literal("[MultiCore Helper] " + lastEntityPressure.toHumanLine()), false);
        source.sendFeedback(() -> Text.literal("[MultiCore Helper] cleanedLooseEntities=" + entitiesCleaned.get()
                + ", autoCleanup=" + CONFIG.autoCleanupLooseEntities), false);
        return 1;
    }

    private static int cleanupLooseEntities(ServerCommandSource source) {
        int cleaned = cleanupLooseEntities(source.getServer(), CONFIG.manualCleanupLimit);
        entitiesCleaned.addAndGet(cleaned);
        lastEntityPressure = inspectEntityPressure(source.getServer());
        source.sendFeedback(() -> Text.literal("[MultiCore Helper] Cleaned " + cleaned + " loose item/xp entities."), true);
        source.sendFeedback(() -> Text.literal("[MultiCore Helper] " + lastEntityPressure.toHumanLine()), false);
        return cleaned;
    }

    private static EntityPressureReport inspectEntityPressure(MinecraftServer server) {
        Map<String, Integer> countsByType = new HashMap<>();
        AtomicInteger totalEntities = new AtomicInteger();
        AtomicInteger looseEntities = new AtomicInteger();

        server.getWorlds().forEach(world -> world.iterateEntities().forEach(entity -> {
            totalEntities.incrementAndGet();
            if (isLooseEntity(entity)) {
                looseEntities.incrementAndGet();
            }
            String typeName = Registries.ENTITY_TYPE.getId(entity.getType()).toString();
            countsByType.merge(typeName, 1, Integer::sum);
        }));

        List<Map.Entry<String, Integer>> topTypes = new ArrayList<>(countsByType.entrySet());
        topTypes.sort(Map.Entry.comparingByValue(Comparator.reverseOrder()));
        if (topTypes.size() > 5) {
            topTypes = new ArrayList<>(topTypes.subList(0, 5));
        }

        return new EntityPressureReport(totalEntities.get(), looseEntities.get(), topTypes);
    }

    private static int cleanupLooseEntities(MinecraftServer server, int maxToRemove) {
        AtomicInteger removed = new AtomicInteger();
        server.getWorlds().forEach(world -> world.iterateEntities().forEach(entity -> {
            if (removed.get() >= maxToRemove || !isLooseEntity(entity)) {
                return;
            }
            entity.discard();
            removed.incrementAndGet();
        }));
        return removed.get();
    }

    private static boolean isLooseEntity(Entity entity) {
        return entity instanceof ItemEntity || entity instanceof ExperienceOrbEntity;
    }

    private static List<EntitySnapshot> captureEntitySnapshots(MinecraftServer server, int maxEntities) {
        List<EntitySnapshot> snapshots = new ArrayList<>();
        server.getWorlds().forEach(world -> world.iterateEntities().forEach(entity -> {
            if (snapshots.size() >= maxEntities) {
                return;
            }
            snapshots.add(new EntitySnapshot(
                    entity.getUuid(),
                    Registries.ENTITY_TYPE.getId(entity.getType()).toString(),
                    entity.getX(),
                    entity.getY(),
                    entity.getZ()));
        }));
        return snapshots;
    }

    private static List<List<EntitySnapshot>> partition(List<EntitySnapshot> snapshots, int batchSize) {
        List<List<EntitySnapshot>> batches = new ArrayList<>();
        for (int index = 0; index < snapshots.size(); index += batchSize) {
            batches.add(snapshots.subList(index, Math.min(snapshots.size(), index + batchSize)));
        }
        return batches;
    }

    private static PartialAnalysisResult analyzeBatch(List<EntitySnapshot> batch) {
        int count = 0;
        double distanceScore = 0.0D;
        long idScore = 0L;

        for (EntitySnapshot snapshot : batch) {
            count++;
            distanceScore += Math.sqrt(snapshot.x * snapshot.x + snapshot.y * snapshot.y + snapshot.z * snapshot.z);
            idScore ^= snapshot.uuid.getMostSignificantBits();
            idScore ^= snapshot.uuid.getLeastSignificantBits();
            idScore += snapshot.typeName.hashCode();
        }

        return new PartialAnalysisResult(count, distanceScore, idScore);
    }

    public static List<String> statusLines() {
        List<String> lines = new ArrayList<>();
        lines.add("[MultiCore Helper] " + lastReport.toHumanLine());
        lines.add("[MultiCore Helper] Compatibility: Sodium="
                + (SODIUM_LOADED ? "detected" : "not detected")
                + ", Lithium=" + (LITHIUM_LOADED ? "detected" : "not detected")
                + ", mode=" + compatibilityMode());
        lines.add("[MultiCore Helper] ForkJoin parallelism="
                + System.getProperty(FORK_JOIN_PARALLELISM, "default")
                + ", Netty eventLoopThreads="
                + System.getProperty(NETTY_EVENT_LOOP_THREADS, "default"));
        lines.add("[MultiCore Helper] workerPool="
                + describeWorkerPool()
                + ", jobsSubmitted=" + jobsSubmitted.get()
                + ", jobsCompleted=" + jobsCompleted.get()
                + ", snapshotsProcessed=" + snapshotsProcessed.get());
        lines.add("[MultiCore Helper] lastParallelResult="
                + lastAnalysisResult.toHumanLine()
                + ", lastTimeMs=" + String.format(Locale.ROOT, "%.3f", lastAnalysisNanos.get() / 1_000_000.0D));
        lines.add("[MultiCore Helper] entityPressure="
                + lastEntityPressure.toHumanLine()
                + ", cleaned=" + entitiesCleaned.get());
        return lines;
    }

    public static List<String> f3Lines() {
        List<String> lines = new ArrayList<>();
        lines.add("MultiCore Helper SAFE | logical CPU=" + Runtime.getRuntime().availableProcessors());
        lines.add(describeWorkerPool()
                + " | jobs=" + jobsCompleted.get() + "/" + jobsSubmitted.get()
                + " | last=" + String.format(Locale.ROOT, "%.2fms", lastAnalysisNanos.get() / 1_000_000.0D));
        return lines;
    }

    private static String describeWorkerPool() {
        ExecutorService pool = workerPool;
        if (pool instanceof ThreadPoolExecutor threadPool) {
            return "adaptive=" + threadPool.getPoolSize() + "/" + threadPool.getMaximumPoolSize()
                    + ", target=" + threadPool.getCorePoolSize()
                    + ", active=" + threadPool.getActiveCount()
                    + ", queued=" + threadPool.getQueue().size();
        }
        return pool == null ? "not started" : "running";
    }

    private static void adaptWorkerCount(ExecutorService pool, int batchCount) {
        if (pool instanceof ThreadPoolExecutor threadPool) {
            int desiredWorkers = clamp((batchCount + 1) / 2, 1, threadPool.getMaximumPoolSize());
            if (threadPool.getCorePoolSize() != desiredWorkers) {
                threadPool.setCorePoolSize(desiredWorkers);
            }
        }
    }

    private static String compatibilityMode() {
        if (SODIUM_LOADED || LITHIUM_LOADED) {
            return "cooperative assist";
        }
        return "normal";
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private record EntitySnapshot(UUID uuid, String typeName, double x, double y, double z) {
    }

    private record PartialAnalysisResult(int entityCount, double distanceScore, long idScore) {
    }

    private static final class ParallelAnalysisResult {
        private final int entityCount;
        private final double distanceScore;
        private final long idScore;

        private ParallelAnalysisResult(int entityCount, double distanceScore, long idScore) {
            this.entityCount = entityCount;
            this.distanceScore = distanceScore;
            this.idScore = idScore;
        }

        private static ParallelAnalysisResult empty() {
            return new ParallelAnalysisResult(0, 0.0D, 0L);
        }

        private static ParallelAnalysisResult merge(List<CompletableFuture<PartialAnalysisResult>> futures) {
            int entityCount = 0;
            double distanceScore = 0.0D;
            long idScore = 0L;

            for (CompletableFuture<PartialAnalysisResult> future : futures) {
                if (future.isCompletedExceptionally() || future.isCancelled()) {
                    continue;
                }
                PartialAnalysisResult partial = future.join();
                entityCount += partial.entityCount();
                distanceScore += partial.distanceScore();
                idScore ^= partial.idScore();
            }

            return new ParallelAnalysisResult(entityCount, distanceScore, idScore);
        }

        private String toHumanLine() {
            return "entities=" + entityCount
                    + ", distanceScore=" + String.format(Locale.ROOT, "%.2f", distanceScore)
                    + ", checksum=" + Long.toUnsignedString(idScore);
        }
    }

    private static final class EntityPressureReport {
        private final int totalEntities;
        private final int looseEntities;
        private final List<Map.Entry<String, Integer>> topTypes;

        private EntityPressureReport(int totalEntities, int looseEntities, List<Map.Entry<String, Integer>> topTypes) {
            this.totalEntities = totalEntities;
            this.looseEntities = looseEntities;
            this.topTypes = topTypes;
        }

        private static EntityPressureReport empty() {
            return new EntityPressureReport(0, 0, new ArrayList<>());
        }

        private String toHumanLine() {
            return "total=" + totalEntities
                    + ", looseItemsOrXp=" + looseEntities
                    + ", topTypes=" + formatTopTypes();
        }

        private String formatTopTypes() {
            if (topTypes.isEmpty()) {
                return "none";
            }

            List<String> parts = new ArrayList<>();
            for (Map.Entry<String, Integer> entry : topTypes) {
                parts.add(entry.getKey() + "=" + entry.getValue());
            }
            return String.join(", ", parts);
        }
    }

    private static final class TuningReport {
        private final boolean enabled;
        private final int detectedProcessors;
        private final int workerThreads;
        private final String detail;

        private TuningReport(boolean enabled, int detectedProcessors, int workerThreads, String detail) {
            this.enabled = enabled;
            this.detectedProcessors = detectedProcessors;
            this.workerThreads = workerThreads;
            this.detail = detail;
        }

        private static TuningReport notApplied() {
            return new TuningReport(false, Math.max(1, Runtime.getRuntime().availableProcessors()), 0, "not applied yet");
        }

        private String toLogLine() {
            return String.format(Locale.ROOT, "enabled=%s, detectedProcessors=%d, workerThreads=%d, detail=%s",
                    enabled, detectedProcessors, workerThreads, detail);
        }

        private String toHumanLine() {
            if (!enabled) {
                return String.format(Locale.ROOT, "disabled, detected CPU threads: %d, detail: %s", detectedProcessors, detail);
            }
            return String.format(Locale.ROOT, "enabled, detected CPU threads: %d, requested worker threads: %d, detail: %s",
                    detectedProcessors, workerThreads, detail);
        }
    }

    private static final class HelperConfig {
        private final boolean enabled;
        private final int workerThreads;
        private final int reserveThreadsForSystem;
        private final int maxWorkerThreads;
        private final boolean tuneForkJoinPool;
        private final boolean tuneNettyThreads;
        private final boolean overrideExistingProperties;
        private final boolean preWarmCommonPool;
        private final boolean parallelEntityAnalysis;
        private final int analysisIntervalTicks;
        private final int maxEntitiesPerAnalysis;
        private final int entityBatchSize;
        private final boolean entityPressureGuard;
        private final int entityPressureIntervalTicks;
        private final int entityWarningThreshold;
        private final int entityEmergencyThreshold;
        private final boolean autoCleanupLooseEntities;
        private final int maxAutoCleanupPerPass;
        private final int manualCleanupLimit;

        private HelperConfig(Properties properties) {
            enabled = getBoolean(properties, "enabled", true);
            workerThreads = getInt(properties, "workerThreads", 0);
            reserveThreadsForSystem = getInt(properties, "reserveThreadsForSystem", 2);
            maxWorkerThreads = getInt(properties, "maxWorkerThreads", 4);
            tuneForkJoinPool = getBoolean(properties, "tuneForkJoinPool", false);
            tuneNettyThreads = getBoolean(properties, "tuneNettyThreads", false);
            overrideExistingProperties = getBoolean(properties, "overrideExistingProperties", false);
            preWarmCommonPool = getBoolean(properties, "preWarmCommonPool", false);
            parallelEntityAnalysis = getBoolean(properties, "parallelEntityAnalysis", true);
            analysisIntervalTicks = getInt(properties, "analysisIntervalTicks", 100);
            maxEntitiesPerAnalysis = getInt(properties, "maxEntitiesPerAnalysis", 2048);
            entityBatchSize = getInt(properties, "entityBatchSize", 256);
            entityPressureGuard = getBoolean(properties, "entityPressureGuard", true);
            entityPressureIntervalTicks = getInt(properties, "entityPressureIntervalTicks", 100);
            entityWarningThreshold = getInt(properties, "entityWarningThreshold", 2500);
            entityEmergencyThreshold = getInt(properties, "entityEmergencyThreshold", 5000);
            autoCleanupLooseEntities = getBoolean(properties, "autoCleanupLooseEntities", false);
            maxAutoCleanupPerPass = getInt(properties, "maxAutoCleanupPerPass", 512);
            manualCleanupLimit = getInt(properties, "manualCleanupLimit", 2048);
        }

        private static HelperConfig load() {
            Properties defaults = new Properties();
            return new HelperConfig(defaults);
        }

        private static boolean getBoolean(Properties properties, String key, boolean defaultValue) {
            return Boolean.parseBoolean(properties.getProperty(key, Boolean.toString(defaultValue)));
        }

        private static int getInt(Properties properties, String key, int defaultValue) {
            try {
                return Integer.parseInt(properties.getProperty(key, Integer.toString(defaultValue)));
            } catch (NumberFormatException ignored) {
                return defaultValue;
            }
        }
    }

    private static final class NamedThreadFactory implements ThreadFactory {
        private final AtomicInteger index = new AtomicInteger();

        @Override
        public Thread newThread(Runnable runnable) {
            Thread thread = new Thread(runnable, "MultiCoreHelper-Worker-" + index.incrementAndGet());
            thread.setDaemon(true);
            thread.setPriority(Math.max(Thread.MIN_PRIORITY, Thread.NORM_PRIORITY - 1));
            return thread;
        }
    }
}
