package com.example.multicorehelper.paper;

import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

public final class MultiCoreHelperPlugin extends JavaPlugin {
    private AnalysisWorker worker;
    private BukkitTask sampler;
    private int maxWorlds;
    @Override public void onEnable() {
        if (!Bukkit.getMinecraftVersion().equals("26.3")) {
            getLogger().severe("This build requires Paper Minecraft 26.3.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        saveDefaultConfig();
        maxWorlds = Math.clamp(getConfig().getInt("max-worlds", 64), 1, 256);
        long interval = Math.clamp(getConfig().getLong("sample-interval-ticks", 200), 100, 72000);
        worker = new AnalysisWorker();
        Objects.requireNonNull(getCommand("multicorehelper")).setExecutor(this);
        sampler = Bukkit.getScheduler().runTaskTimer(this, this::sample, 1, interval);
        getLogger().info("Enabled Paper 26.3 snapshot monitoring; workers=1; cleanup=OFF; global tuning=OFF. " + integrations());
    }
    private void requireMainThread() {
        if (!Bukkit.isPrimaryThread()) throw new IllegalStateException("Bukkit access requires server thread");
    }
    private Snapshot capture() {
        requireMainThread();
        var worlds = Bukkit.getWorlds();
        var values = new ArrayList<Snapshot.WorldSample>();
        for (var world : worlds) {
            if (values.size() >= maxWorlds) break;
            values.add(new Snapshot.WorldSample(world.getName(), world.getChunkCount(), world.getEntityCount()));
        }
        var runtime = Runtime.getRuntime();
        return new Snapshot(System.currentTimeMillis(), Bukkit.getTPS()[0], Bukkit.getAverageTickTime(),
                Bukkit.getOnlinePlayers().size(), runtime.totalMemory() - runtime.freeMemory(), runtime.maxMemory(),
                worlds.size() > maxWorlds, values);
    }
    private void sample() {
        requireMainThread();
        if (worker != null && !worker.busy()) worker.submit(capture());
    }
    private String integrations() {
        requireMainThread();
        var plugins = getServer().getPluginManager();
        return "Geyser=" + plugins.isPluginEnabled("Geyser-Spigot") + ", Floodgate=" + plugins.isPluginEnabled("floodgate")
                + " (optional; authentication and networking unchanged)";
    }
    @Override public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        requireMainThread();
        if (!sender.hasPermission("multicorehelper.admin")) { sender.sendMessage("Permission denied."); return true; }
        if (worker == null) { sender.sendMessage("MultiCore Helper is not active."); return true; }
        String action = args.length == 0 ? "status" : args[0].toLowerCase(Locale.ROOT);
        switch (action) {
            case "analyze" -> sender.sendMessage(worker.submit(capture())
                    ? "[MultiCore Helper] Snapshot queued; use /mch status for results."
                    : "[MultiCore Helper] Analysis busy or stopping; request skipped.");
            case "save" -> {
                // Explicit administrator request only; Paper owns saving and normal shutdown.
                Bukkit.savePlayers();
                for (var world : Bukkit.getWorlds()) world.save();
                sender.sendMessage("[MultiCore Helper] Main-thread player/world save completed. Cold backup requires a full server stop.");
            }
            case "status" -> {
                sender.sendMessage("[MultiCore Helper] Paper 26.3 v" + getPluginMeta().getVersion()
                        + "; worker=1; busy=" + worker.busy() + "; skipped=" + worker.skipped() + "; error=" + worker.lastError());
                var report = worker.latest();
                if (report == null) sender.sendMessage("No completed snapshot yet.");
                else {
                    var s = report.snapshot();
                    sender.sendMessage(String.format(Locale.ROOT,
                            "TPS=%.2f MSPT=%.2f players=%d chunks=%d entities=%d age=%.1fs partial=%s pressure=%s",
                            s.tps(), s.mspt(), s.players(), report.chunks(), report.entities(),
                            (System.currentTimeMillis() - s.capturedAtMillis()) / 1000.0, s.truncated(), report.overloaded()));
                    sender.sendMessage("Busiest world (entity count only): " + report.busiestWorld()
                            + "; heap MiB=" + s.heapUsed() / 1048576 + "/" + s.heapMax() / 1048576);
                }
                sender.sendMessage("Automatic cleanup=OFF; global pool tuning=OFF; " + integrations());
            }
            default -> { return false; }
        }
        return true;
    }
    @Override public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        return sender.hasPermission("multicorehelper.admin") && args.length == 1
                ? List.of("status", "analyze", "save").stream().filter(s -> s.startsWith(args[0].toLowerCase(Locale.ROOT))).toList()
                : List.of();
    }
    @Override public void onDisable() {
        if (sampler != null) { sampler.cancel(); sampler = null; }
        if (worker != null) {
            worker.close();
            getLogger().info("Stopped snapshot monitoring; worker terminated=" + worker.terminated());
            worker = null;
        }
    }
}
