package com.example.forcedmulticore;

import com.mojang.brigadier.CommandDispatcher;
import net.minecraft.core.registries.Registries;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.client.KeyMapping;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.ExperienceOrb;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.common.ForgeConfigSpec;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.server.ServerStartingEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.client.event.CustomizeGuiOverlayEvent;
import net.minecraftforge.client.event.InputEvent;
import net.minecraftforge.client.event.RegisterKeyMappingsEvent;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;
import net.minecraftforge.server.ServerLifecycleHooks;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
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

@Mod(MultiCoreHelperForge.MOD_ID)
public final class MultiCoreHelperForge {
    public static final String MOD_ID = "forcedmulticore";
    public static final String VERSION = "1.0.7";

    private static final String FORK_JOIN_PARALLELISM = "java.util.concurrent.ForkJoinPool.common.parallelism";
    private static final String NETTY_EVENT_LOOP_THREADS = "io.netty.eventLoopThreads";
    private static final Logger LOGGER = LogManager.getLogger();

    private static final DeferredRegister<Item> ITEMS = DeferredRegister.create(ForgeRegistries.ITEMS, MOD_ID);
    private static final DeferredRegister<CreativeModeTab> CREATIVE_TABS = DeferredRegister.create(Registries.CREATIVE_MODE_TAB, MOD_ID);

    public static final RegistryObject<Item> CPU_CORE = ITEMS.register("cpu_core",
            () -> new Item(new Item.Properties()));

    public static final RegistryObject<CreativeModeTab> MAIN_TAB = CREATIVE_TABS.register("main",
            () -> CreativeModeTab.builder()
                    .title(Component.translatable("itemGroup.forcedmulticore"))
                    .icon(() -> new ItemStack(CPU_CORE.get()))
                    .displayItems((parameters, output) -> output.accept(CPU_CORE.get()))
                    .build());

    private static final ForgeConfigSpec COMMON_SPEC;
    private static final CommonConfig CONFIG;

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

    static {
        ForgeConfigSpec.Builder builder = new ForgeConfigSpec.Builder();
        CONFIG = new CommonConfig(builder);
        COMMON_SPEC = builder.build();
    }

    public MultiCoreHelperForge() {
        ITEMS.register(FMLJavaModLoadingContext.get().getModEventBus());
        CREATIVE_TABS.register(FMLJavaModLoadingContext.get().getModEventBus());
        ModLoadingContext.get().registerConfig(ModConfig.Type.COMMON, COMMON_SPEC);
        FMLJavaModLoadingContext.get().getModEventBus().addListener(this::onCommonSetup);
        MinecraftForge.EVENT_BUS.register(this);
        MinecraftForge.EVENT_BUS.register(new TntAdvancementTracker());
    }

    private void onCommonSetup(FMLCommonSetupEvent event) {
        event.enqueueWork(() -> {
            lastReport = applyTuning();
            LOGGER.info("[MultiCore Helper] {}", lastReport.toLogLine());
        });
    }

    @SubscribeEvent
    public void onServerStarting(ServerStartingEvent event) {
        serverActive = false;
        lastReport = applyTuning();
        rebuildWorkerPool(lastReport.workerThreads);
        serverTicks = 0;
        serverActive = true;
        LOGGER.info("[MultiCore Helper] Started server worker pool: {}", lastReport.toLogLine());
    }

    @SubscribeEvent
    public void onServerStopping(ServerStoppingEvent event) {
        serverActive = false;
        shutdownWorkerPool();
    }

    private static TuningReport applyTuning() {
        int detectedProcessors = Math.max(1, Runtime.getRuntime().availableProcessors());

        if (!CONFIG.enabled.get()) {
            return new TuningReport(false, detectedProcessors, 0, "disabled in config");
        }

        int automaticThreads = Math.max(1, Math.min(4, detectedProcessors / 2));
        int workerThreads = CONFIG.workerThreads.get() > 0 ? CONFIG.workerThreads.get() : automaticThreads;
        workerThreads = clamp(workerThreads, 1, Math.min(4, CONFIG.maxWorkerThreads.get()));

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

    @SubscribeEvent
    public void onRegisterCommands(RegisterCommandsEvent event) {
        CommandDispatcher<CommandSourceStack> dispatcher = event.getDispatcher();
        dispatcher.register(Commands.literal("multicorecpu")
                .then(Commands.literal("status")
                        .executes(context -> sendStatus(context.getSource())))
                .then(Commands.literal("entities")
                        .executes(context -> sendEntityStatus(context.getSource())))
                .then(Commands.literal("cleanup")
                        .requires(source -> source.hasPermission(2))
                        .executes(context -> cleanupLooseEntities(context.getSource()))));
    }

    private static int sendStatus(CommandSourceStack source) {
        for (String line : statusLines()) {
            source.sendSuccess(() -> Component.literal(line), false);
        }
        return 1;
    }

    public static List<String> statusLines() {
        List<String> lines = new ArrayList<>();
        lines.add("[MultiCore Helper] " + lastReport.toHumanLine());
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

    @Mod.EventBusSubscriber(modid = MOD_ID, value = Dist.CLIENT)
    public static final class ClientDebugEvents {
        private static final KeyMapping TOGGLE_KEY = new KeyMapping(
                "key.forcedmulticore.toggle_debug", GLFW.GLFW_KEY_F8, "key.categories.forcedmulticore");
        private static boolean showInfo;
        private static final ClientUpdateChecker UPDATE_CHECKER =
                new ClientUpdateChecker(VERSION, "1.20.1", "Forge");

        @SubscribeEvent
        public static void onKeyInput(InputEvent.Key event) {
            while (TOGGLE_KEY.consumeClick()) {
                showInfo = !showInfo;
            }
        }

        @SubscribeEvent
        public static void onDebugText(CustomizeGuiOverlayEvent.DebugText event) {
            if (showInfo) {
                event.getRight().addAll(f3Lines());
            }
        }

        @SubscribeEvent
        public static void onClientTick(TickEvent.ClientTickEvent event) {
            if (event.phase != TickEvent.Phase.END) {
                return;
            }
            net.minecraft.client.Minecraft minecraft = net.minecraft.client.Minecraft.getInstance();
            ClientUpdateChecker.Result update = minecraft.player == null ? null : UPDATE_CHECKER.poll();
            if (update != null) {
                minecraft.player.displayClientMessage(
                        Component.translatable(
                                "message.forcedmulticore.update_available",
                                update.currentVersion,
                                update.latestVersion)
                                .append(" " + update.downloadUrl),
                        false);
            }
        }

    }

    @Mod.EventBusSubscriber(modid = MOD_ID, value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.MOD)
    public static final class ClientModEvents {
        @SubscribeEvent
        public static void onRegisterKeyMappings(RegisterKeyMappingsEvent event) {
            event.register(ClientDebugEvents.TOGGLE_KEY);
            ClientDebugEvents.UPDATE_CHECKER.start();
        }
    }

    @SubscribeEvent
    public void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || !serverActive) {
            return;
        }

        serverTicks++;
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server == null) {
            return;
        }

        if (CONFIG.entityPressureGuard.get() && serverTicks % CONFIG.entityPressureIntervalTicks.get() == 0) {
            EntityPressureReport pressure = inspectEntityPressure(server);
            lastEntityPressure = pressure;
            if (pressure.totalEntities >= CONFIG.entityWarningThreshold.get()) {
                LOGGER.warn("[MultiCore Helper] Entity pressure: {}", pressure.toHumanLine());
            }
            if (CONFIG.autoCleanupLooseEntities.get() && pressure.totalEntities >= CONFIG.entityEmergencyThreshold.get()) {
                int cleaned = cleanupLooseEntities(server, CONFIG.maxAutoCleanupPerPass.get());
                if (cleaned > 0) {
                    entitiesCleaned.addAndGet(cleaned);
                    LOGGER.warn("[MultiCore Helper] Auto-cleaned {} loose item/xp entities because entity pressure reached {}", cleaned, pressure.totalEntities);
                }
            }
        }

        if (!CONFIG.parallelEntityAnalysis.get() || serverTicks % CONFIG.analysisIntervalTicks.get() != 0) {
            return;
        }

        ExecutorService pool = workerPool;
        if (pool == null || pool.isShutdown() || pool.isTerminated() || analysisRunning.get()) {
            return;
        }

        List<EntitySnapshot> snapshots = captureEntitySnapshots(server, CONFIG.maxEntitiesPerAnalysis.get());
        if (snapshots.isEmpty()) {
            return;
        }

        analysisRunning.set(true);
        long startNanos = System.nanoTime();
        List<List<EntitySnapshot>> batches = partition(snapshots, Math.max(1, CONFIG.entityBatchSize.get()));
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

    private static int sendEntityStatus(CommandSourceStack source) {
        MinecraftServer server = source.getServer();
        lastEntityPressure = inspectEntityPressure(server);
        source.sendSuccess(() -> Component.literal("[MultiCore Helper] " + lastEntityPressure.toHumanLine()), false);
        source.sendSuccess(() -> Component.literal("[MultiCore Helper] cleanedLooseEntities=" + entitiesCleaned.get()
                + ", autoCleanup=" + CONFIG.autoCleanupLooseEntities.get()), false);
        return 1;
    }

    private static int cleanupLooseEntities(CommandSourceStack source) {
        int cleaned = cleanupLooseEntities(source.getServer(), CONFIG.manualCleanupLimit.get());
        entitiesCleaned.addAndGet(cleaned);
        lastEntityPressure = inspectEntityPressure(source.getServer());
        source.sendSuccess(() -> Component.literal("[MultiCore Helper] Cleaned " + cleaned + " loose item/xp entities."), true);
        source.sendSuccess(() -> Component.literal("[MultiCore Helper] " + lastEntityPressure.toHumanLine()), false);
        return cleaned;
    }

    private static EntityPressureReport inspectEntityPressure(MinecraftServer server) {
        Map<String, Integer> countsByType = new HashMap<>();
        AtomicInteger totalEntities = new AtomicInteger();
        AtomicInteger looseEntities = new AtomicInteger();

        server.getAllLevels().forEach(level -> level.getAllEntities().forEach(entity -> {
            totalEntities.incrementAndGet();
            if (isLooseEntity(entity)) {
                looseEntities.incrementAndGet();
            }
            ResourceLocation typeName = ForgeRegistries.ENTITY_TYPES.getKey(entity.getType());
            countsByType.merge(typeName == null ? "unknown" : typeName.toString(), 1, Integer::sum);
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
        server.getAllLevels().forEach(level -> level.getAllEntities().forEach(entity -> {
            if (removed.get() >= maxToRemove || !isLooseEntity(entity)) {
                return;
            }
            entity.discard();
            removed.incrementAndGet();
        }));
        return removed.get();
    }

    private static boolean isLooseEntity(Entity entity) {
        return entity instanceof ItemEntity || entity instanceof ExperienceOrb;
    }

    private static List<EntitySnapshot> captureEntitySnapshots(MinecraftServer server, int maxEntities) {
        List<EntitySnapshot> snapshots = new ArrayList<>();
        server.getAllLevels().forEach(level -> level.getAllEntities().forEach(entity -> {
            if (snapshots.size() >= maxEntities) {
                return;
            }
            ResourceLocation typeName = ForgeRegistries.ENTITY_TYPES.getKey(entity.getType());
            snapshots.add(new EntitySnapshot(
                    entity.getUUID(),
                    typeName == null ? "unknown" : typeName.toString(),
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

    private static final class CommonConfig {
        private final ForgeConfigSpec.BooleanValue enabled;
        private final ForgeConfigSpec.IntValue workerThreads;
        private final ForgeConfigSpec.IntValue reserveThreadsForSystem;
        private final ForgeConfigSpec.IntValue maxWorkerThreads;
        private final ForgeConfigSpec.BooleanValue tuneForkJoinPool;
        private final ForgeConfigSpec.BooleanValue tuneNettyThreads;
        private final ForgeConfigSpec.BooleanValue overrideExistingProperties;
        private final ForgeConfigSpec.BooleanValue preWarmCommonPool;
        private final ForgeConfigSpec.BooleanValue parallelEntityAnalysis;
        private final ForgeConfigSpec.IntValue analysisIntervalTicks;
        private final ForgeConfigSpec.IntValue maxEntitiesPerAnalysis;
        private final ForgeConfigSpec.IntValue entityBatchSize;
        private final ForgeConfigSpec.BooleanValue entityPressureGuard;
        private final ForgeConfigSpec.IntValue entityPressureIntervalTicks;
        private final ForgeConfigSpec.IntValue entityWarningThreshold;
        private final ForgeConfigSpec.IntValue entityEmergencyThreshold;
        private final ForgeConfigSpec.BooleanValue autoCleanupLooseEntities;
        private final ForgeConfigSpec.IntValue maxAutoCleanupPerPass;
        private final ForgeConfigSpec.IntValue manualCleanupLimit;

        private CommonConfig(ForgeConfigSpec.Builder builder) {
            builder.push("cpu");
            enabled = builder.comment("Enable safe multicore tuning hints.").define("enabled", true);
            workerThreads = builder.comment("Number of worker threads to request. Set to 0 to auto-detect CPU cores.").defineInRange("workerThreads", 0, 0, 256);
            reserveThreadsForSystem = builder.comment("Legacy setting. Safe automatic mode uses at most half the logical processors.").defineInRange("reserveThreadsForSystem", 2, 0, 64);
            maxWorkerThreads = builder.comment("Requested limit; safe mode always caps Helper workers at 4.").defineInRange("maxWorkerThreads", 4, 1, 256);
            tuneForkJoinPool = builder.comment("Legacy setting; global ForkJoin tuning is disabled in safe mode.").define("tuneForkJoinPool", false);
            tuneNettyThreads = builder.comment("Legacy setting; global Netty tuning is disabled in safe mode.").define("tuneNettyThreads", false);
            overrideExistingProperties = builder.comment("Override JVM -D properties if they already exist.").define("overrideExistingProperties", false);
            preWarmCommonPool = builder.comment("Legacy setting; common-pool prewarming is disabled in safe mode.").define("preWarmCommonPool", false);
            builder.pop();

            builder.push("experimentalMultithreading");
            parallelEntityAnalysis = builder.comment("Run a real multi-threaded entity snapshot analysis on worker threads. World data is captured on the server thread; workers only process immutable snapshots.").define("parallelEntityAnalysis", true);
            analysisIntervalTicks = builder.comment("How often to dispatch parallel entity analysis. Default: five seconds.").defineInRange("analysisIntervalTicks", 100, 20, 20 * 60);
            maxEntitiesPerAnalysis = builder.comment("Maximum entity snapshots to process per analysis pass.").defineInRange("maxEntitiesPerAnalysis", 2048, 1, 100000);
            entityBatchSize = builder.comment("How many entity snapshots each worker task receives.").defineInRange("entityBatchSize", 256, 1, 8192);
            builder.pop();

            builder.push("entityPressureGuard");
            entityPressureGuard = builder.comment("Track entity counts and warn when too many entities may cause lag, watchdog timeouts, or crashes.").define("entityPressureGuard", true);
            entityPressureIntervalTicks = builder.comment("How often to inspect entity pressure. 100 ticks is about every five seconds.").defineInRange("entityPressureIntervalTicks", 100, 20, 20 * 60);
            entityWarningThreshold = builder.comment("Log a warning when total loaded entities reaches this value.").defineInRange("entityWarningThreshold", 2500, 1, 1000000);
            entityEmergencyThreshold = builder.comment("If autoCleanupLooseEntities is enabled, cleanup starts when total loaded entities reaches this value.").defineInRange("entityEmergencyThreshold", 5000, 1, 1000000);
            autoCleanupLooseEntities = builder.comment("Automatically remove dropped items and XP orbs during emergency entity pressure. Disabled by default to avoid deleting wanted drops.").define("autoCleanupLooseEntities", false);
            maxAutoCleanupPerPass = builder.comment("Maximum dropped item / XP orb entities to remove during one automatic cleanup pass.").defineInRange("maxAutoCleanupPerPass", 512, 1, 100000);
            manualCleanupLimit = builder.comment("Maximum dropped item / XP orb entities removed by /multicorecpu cleanup.").defineInRange("manualCleanupLimit", 2048, 1, 100000);
            builder.pop();
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
