package com.example.forcedmulticore;

import net.minecraft.entity.Entity;
import net.minecraft.entity.item.EntityItem;
import net.minecraft.entity.item.EntityXPOrb;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.WorldServer;
import org.apache.logging.log4j.Logger;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

final class EntityPressureService {
    private final Logger logger;
    private final AtomicLong cleanedCount = new AtomicLong();
    private volatile EntityPressureReport lastReport = EntityPressureReport.empty();

    EntityPressureService(Logger logger) {
        this.logger = logger;
    }

    EntityPressureReport getLastReport() {
        return lastReport;
    }

    long getCleanedCount() {
        return cleanedCount.get();
    }

    EntityPressureReport inspect(MinecraftServer server) {
        EntityPressureReport report = summarize(captureSnapshots(server, Integer.MAX_VALUE));
        lastReport = report;
        return report;
    }

    List<EntitySnapshot> captureSnapshots(MinecraftServer server, int maxEntities) {
        List<EntitySnapshot> snapshots = new ArrayList<EntitySnapshot>();
        for (WorldServer world : server.worldServers) {
            if (world == null || snapshots.size() >= maxEntities) {
                continue;
            }
            for (Object value : world.loadedEntityList) {
                if (snapshots.size() >= maxEntities) {
                    return snapshots;
                }
                if (value instanceof Entity) {
                    Entity entity = (Entity) value;
                    snapshots.add(new EntitySnapshot(entity.getClass().getName(), isLooseEntity(entity)));
                }
            }
        }
        return snapshots;
    }

    int cleanupLooseEntities(MinecraftServer server, int maxToRemove) {
        int removed = 0;
        for (WorldServer world : server.worldServers) {
            if (world == null) {
                continue;
            }
            List<?> loaded = new ArrayList<Object>(world.loadedEntityList);
            for (Object value : loaded) {
                if (removed >= maxToRemove) {
                    cleanedCount.addAndGet(removed);
                    return removed;
                }
                if (value instanceof Entity && isLooseEntity((Entity) value)) {
                    ((Entity) value).setDead();
                    removed++;
                }
            }
        }
        cleanedCount.addAndGet(removed);
        return removed;
    }

    static EntityPressureReport summarize(List<EntitySnapshot> snapshots) {
        Map<String, Integer> countsByType = new HashMap<String, Integer>();
        int loose = 0;
        for (EntitySnapshot snapshot : snapshots) {
            if (snapshot.loose) {
                loose++;
            }
            Integer current = countsByType.get(snapshot.typeName);
            countsByType.put(snapshot.typeName, current == null ? 1 : current + 1);
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
        return new EntityPressureReport(snapshots.size(), loose, topTypes);
    }

    private static boolean isLooseEntity(Entity entity) {
        return entity instanceof EntityItem || entity instanceof EntityXPOrb;
    }
}
