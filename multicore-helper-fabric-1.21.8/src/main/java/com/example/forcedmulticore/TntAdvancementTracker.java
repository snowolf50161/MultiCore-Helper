package com.example.forcedmulticore;

import net.minecraft.advancement.AdvancementEntry;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.TntEntity;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.Identifier;

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

    static void onEntityLoad(net.minecraft.entity.Entity entity, net.minecraft.server.world.ServerWorld world) {
        if (!(entity instanceof TntEntity tnt)) {
            return;
        }
        LivingEntity owner = tnt.getOwner();
        if (!(owner instanceof ServerPlayerEntity player)) {
            return;
        }
        grant(player, "oh_my_pc");
        long now = System.currentTimeMillis();
        ArrayDeque<Long> recent = IGNITIONS.computeIfAbsent(player.getUuid(), ignored -> new ArrayDeque<>());
        while (!recent.isEmpty() && now - recent.peekFirst() > WINDOW_MS) {
            recent.removeFirst();
        }
        recent.addLast(now);
        if (recent.size() >= CHAIN_COUNT) {
            grant(player, "oh_no");
            recent.clear();
        }
    }

    private static void grant(ServerPlayerEntity player, String path) {
        AdvancementEntry advancement = player.getServer().getAdvancementLoader()
                .get(Identifier.of(MultiCoreHelperFabric.MOD_ID, path));
        if (advancement != null) {
            player.getAdvancementTracker().grantCriterion(advancement, "ignited");
        }
    }
}
