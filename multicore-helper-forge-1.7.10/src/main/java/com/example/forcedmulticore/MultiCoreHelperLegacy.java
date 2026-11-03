package com.example.forcedmulticore;

import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.Mod;
import cpw.mods.fml.common.event.FMLInitializationEvent;
import cpw.mods.fml.common.event.FMLPreInitializationEvent;
import cpw.mods.fml.common.event.FMLServerStartingEvent;
import cpw.mods.fml.common.event.FMLServerStoppingEvent;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.PlayerEvent;
import cpw.mods.fml.common.gameevent.TickEvent;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.server.MinecraftServer;
import net.minecraftforge.common.MinecraftForge;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.List;

@Mod(modid = MultiCoreHelperLegacy.MOD_ID, name = MultiCoreHelperLegacy.MOD_NAME, version = MultiCoreHelperLegacy.VERSION, acceptedMinecraftVersions = "[1.7.10]")
public final class MultiCoreHelperLegacy {
    public static final String MOD_ID = "forcedmulticore";
    public static final String MOD_NAME = "MultiCore Helper";
    public static final String VERSION = "1.7.10-1.0.7";
    static final Logger LOGGER = LogManager.getLogger(MOD_NAME);

    private CommonConfig config = CommonConfig.defaults();
    private volatile WorkerManager workers = WorkerManager.notStarted(LOGGER);
    private final EntityPressureService entityPressure = new EntityPressureService(LOGGER);
    private int serverTicks;
    private volatile boolean serverActive;

    @Mod.EventHandler
    public void preInit(FMLPreInitializationEvent event) {
        config = CommonConfig.load(event.getSuggestedConfigurationFile());
        LegacyContent.register();
    }

    @Mod.EventHandler
    public void init(FMLInitializationEvent event) {
        FMLCommonHandler.instance().bus().register(this);
        if (event.getSide().isClient()) {
            MinecraftForge.EVENT_BUS.register(new LegacyDebugOverlay(this));
        }
        MinecraftForge.EVENT_BUS.register(new TntAchievementTracker());
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
    public void onPlayerLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (LegacyContent.fineAchievement != null && event.player instanceof EntityPlayer) {
            ((EntityPlayer) event.player).triggerAchievement(LegacyContent.fineAchievement);
        }
    }

    @SubscribeEvent
    public void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || !serverActive) {
            return;
        }

        MinecraftServer server = MinecraftServer.getServer();
        if (server == null || server.worldServers == null) {
            return;
        }

        serverTicks++;
        handleEntityPressure(server);
        dispatchBackgroundAnalysis(server);
    }

    int sendStatus(net.minecraft.command.ICommandSender sender) {
        Chat.send(sender, "[MultiCore Helper] " + workers.getTuningReport().toHumanLine());
        Chat.send(sender, "[MultiCore Helper] " + workers.describeProperties());
        Chat.send(sender, "[MultiCore Helper] " + workers.describePool());
        Chat.send(sender, "[MultiCore Helper] lastBackgroundAnalysis=" + workers.getLastAnalysis().toHumanLine());
        Chat.send(sender, "[MultiCore Helper] entityPressure=" + entityPressure.getLastReport().toHumanLine()
                + ", cleaned=" + entityPressure.getCleanedCount());
        return 1;
    }

    List<String> getF3Lines() {
        List<String> lines = new java.util.ArrayList<String>();
        lines.add("MultiCore Helper SAFE | logical CPU=" + Runtime.getRuntime().availableProcessors());
        lines.add(workers.describePool());
        return lines;
    }

    int sendEntityStatus(net.minecraft.command.ICommandSender sender) {
        MinecraftServer server = MinecraftServer.getServer();
        if (server != null) {
            entityPressure.inspect(server);
        }
        Chat.send(sender, "[MultiCore Helper] " + entityPressure.getLastReport().toHumanLine());
        Chat.send(sender, "[MultiCore Helper] cleanedLooseEntities=" + entityPressure.getCleanedCount()
                + ", autoCleanup=" + config.autoCleanupLooseEntities);
        return 1;
    }

    int cleanupLooseEntities(net.minecraft.command.ICommandSender sender) {
        MinecraftServer server = MinecraftServer.getServer();
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
                LOGGER.warn("[MultiCore Helper] Auto-cleaned {} loose item/xp entities because entity pressure reached {}", cleaned, pressure.totalEntities);
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
