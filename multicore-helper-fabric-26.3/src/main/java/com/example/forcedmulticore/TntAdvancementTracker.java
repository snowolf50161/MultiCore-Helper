package com.example.forcedmulticore;

import net.minecraft.advancements.AdvancementHolder;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.item.PrimedTnt;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

final class TntAdvancementTracker {
    private static final long WINDOW_MS = 4000L;
    private static final int CHAIN_COUNT = 8;
    private static final Map<UUID, ArrayDeque<Long>> IGNITIONS = new HashMap<>();

    private TntAdvancementTracker() {
    }

    static void onEntityLoad(net.minecraft.world.entity.Entity entity, net.minecraft.server.level.ServerLevel level) {
        if (!(entity instanceof PrimedTnt tnt)) {
            return;
        }
        LivingEntity owner = tnt.getOwner();
        if (!(owner instanceof ServerPlayer player)) {
            return;
        }
        grant(level, player, "oh_my_pc");
        long now = System.currentTimeMillis();
        ArrayDeque<Long> recent = IGNITIONS.computeIfAbsent(player.getUUID(), ignored -> new ArrayDeque<>());
        while (!recent.isEmpty() && now - recent.peekFirst() > WINDOW_MS) {
            recent.removeFirst();
        }
        recent.addLast(now);
        if (recent.size() >= CHAIN_COUNT) {
            grant(level, player, "oh_no");
            recent.clear();
        }
    }

    private static void grant(net.minecraft.server.level.ServerLevel level, ServerPlayer player, String path) {
        AdvancementHolder advancement = level.getServer().getAdvancements()
                .get(Identifier.fromNamespaceAndPath(MultiCoreHelperFabric.MOD_ID, path));
        if (advancement != null) {
            player.getAdvancements().award(advancement, "ignited");
        }
    }
}
