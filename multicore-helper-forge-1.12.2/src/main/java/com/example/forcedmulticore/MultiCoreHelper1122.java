package com.example.forcedmulticore;

import net.minecraft.command.ICommandSender;
import net.minecraft.server.MinecraftServer;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.FMLCommonHandler;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.event.FMLInitializationEvent;
import net.minecraftforge.fml.common.event.FMLPreInitializationEvent;
import net.minecraftforge.fml.common.event.FMLServerStartingEvent;
import net.minecraftforge.fml.common.event.FMLServerStoppingEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.ArrayList;
import java.util.List;

@Mod(modid = MultiCoreHelper1122.MOD_ID, name = MultiCoreHelper1122.MOD_NAME,
        version = MultiCoreHelper1122.VERSION, acceptedMinecraftVersions = "[1.12.2]")
public final class MultiCoreHelper1122 {
    public static final String MOD_ID = "forcedmulticore";
    public static final String MOD_NAME = "MultiCore Helper";
    public static final String VERSION = "1.0.5";
    static final Logger LOGGER = LogManager.getLogger(MOD_NAME);

    private CommonConfig config = CommonConfig.defaults();
    private volatile WorkerManager workers = WorkerManager.notStarted(LOGGER);
    private final EntityPressureService entityPressure = new EntityPressureService(LOGGER);
    private int serverTicks;
    private volatile boolean serverActive;

    @Mod.EventHandler
    public void preInit(FMLPreInitializationEvent event) {
        config = CommonConfig.load(event.getSuggestedConfigurationFile());
    }

    @Mod.EventHandler
    public void init(FMLInitializationEvent event) {
        FMLCommonHandler.instance().bus().register(this);
        if (event.getSide().isClient()) {
            MinecraftForge.EVENT_BUS.register(new LegacyDebugOverlay(this));
        }
    }

    @Mod.EventHandler
    public void serverStarting(FMLServerStartingEvent event) {
        serverActive = false;
        workers.shutdown();
        workers = WorkerManager.start(config, LOGGER);
        serverTicks = 0;
        serverActive = true;
        LOGGER.info("[MultiCore Helper] {}", workers.getTuningReport().toLogLine());
        event.registerServerCommand(new MultiCoreCommand(this));
    }

    @Mod.EventHandler
    public void serverStopping(FMLServerStoppingEvent event) {
        serverActive = false;
        workers.shutdown();
        workers = WorkerManager.notStarted(LOGGER);
    }

    @SubscribeEvent
    public void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || !serverActive) {
            return;
        }

        MinecraftServer server = FMLCommonHandler.instance().getMinecraftServerInstance();
        if (server == null) {
            return;
        }

        serverTicks++;
        handleEntityPressure(server);
        dispatchBackgroundAnalysis(server);
    }

    int sendStatus(ICommandSender sender) {
        Chat.send(sender, "[MultiCore Helper] " + workers.getTuningReport().toHumanLine());
        Chat.send(sender, "[MultiCore Helper] " + workers.describeProperties());
        Chat.send(sender, "[MultiCore Helper] " + workers.describePool());
        Chat.send(sender, "[MultiCore Helper] lastBackgroundAnalysis=" + workers.getLastAnalysis().toHumanLine());
        Chat.send(sender, "[MultiCore Helper] entityPressure=" + entityPressure.getLastReport().toHumanLine()
                + ", cleaned=" + entityPressure.getCleanedCount());
        return 1;
    }

    List<String> getF3Lines() {
        List<String> lines = new ArrayList<String>();
        lines.add("MultiCore Helper SAFE | logical CPU=" + Runtime.getRuntime().availableProcessors());
        lines.add(workers.describePool());
        return lines;
    }

    int sendEntityStatus(ICommandSender sender) {
        MinecraftServer server = FMLCommonHandler.instance().getMinecraftServerInstance();
        if (server != null) {
            entityPressure.inspect(server);
        }
        Chat.send(sender, "[MultiCore Helper] " + entityPressure.getLastReport().toHumanLine());
        Chat.send(sender, "[MultiCore Helper] cleanedLooseEntities=" + entityPressure.getCleanedCount()
                + ", autoCleanup=" + config.autoCleanupLooseEntities);
        return 1;
    }

    int cleanupLooseEntities(ICommandSender sender) {
        MinecraftServer server = FMLCommonHandler.instance().getMinecraftServerInstance();
        if (server == null) {
            Chat.send(sender, "[MultiCore Helper] Server is not ready.");
            return 0;
        }

        int cleaned = entityPressure.cleanupLooseEntities(server, config.manualCleanupLimit);
        entityPressure.inspect(server);
        Chat.send(sender, "[MultiCore Helper] Cleaned " + cleaned + " loose item/xp entities.");
        Chat.send(sender, "[MultiCore Helper] " + entityPressure.getLastReport().toHumanLine());
        return cleaned;
    }

    private void handleEntityPressure(MinecraftServer server) {
        if (!config.entityPressureGuard || serverTicks % config.entityPressureIntervalTicks != 0) {
            return;
        }
        EntityPressureReport pressure = entityPressure.inspect(server);
        if (pressure.totalEntities >= config.entityWarningThreshold) {
            LOGGER.warn("[MultiCore Helper] Entity pressure: {}", pressure.toHumanLine());
        }
        if (config.autoCleanupLooseEntities && pressure.totalEntities >= config.entityEmergencyThreshold) {
            int cleaned = entityPressure.cleanupLooseEntities(server, config.maxAutoCleanupPerPass);
            if (cleaned > 0) {
                LOGGER.warn("[MultiCore Helper] Auto-cleaned {} loose item/xp entities at pressure {}",
                        cleaned, pressure.totalEntities);
            }
        }
    }

    private void dispatchBackgroundAnalysis(MinecraftServer server) {
        if (!config.parallelEntityAnalysis || serverTicks % config.analysisIntervalTicks != 0) {
            return;
        }
        List<EntitySnapshot> snapshots = entityPressure.captureSnapshots(server, config.maxEntitiesPerAnalysis);
        workers.dispatchEntityAnalysis(snapshots, config.entityBatchSize);
    }
}
