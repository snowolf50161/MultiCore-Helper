package com.example.forcedmulticore;

import net.minecraft.advancements.Advancement;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.item.PrimedTnt;
import net.minecraftforge.event.entity.EntityJoinLevelEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

final class TntAdvancementTracker {
    private static final long WINDOW_MS = 4000L;
    private static final int CHAIN_COUNT = 8;
    private final Map<UUID, ArrayDeque<Long>> ignitions = new HashMap<>();

    @SubscribeEvent
    public void onEntityJoin(EntityJoinLevelEvent event) {
        if (event.getLevel().isClientSide() || !(event.getEntity() instanceof PrimedTnt tnt)) {
            return;
        }
        LivingEntity owner = tnt.getOwner();
        if (!(owner instanceof ServerPlayer player)) {
            return;
        }
        grant(player, "oh_my_pc");
        long now = System.currentTimeMillis();
        ArrayDeque<Long> recent = ignitions.computeIfAbsent(player.getUUID(), ignored -> new ArrayDeque<>());
        while (!recent.isEmpty() && now - recent.peekFirst() > WINDOW_MS) {
            recent.removeFirst();
        }
        recent.addLast(now);
        if (recent.size() >= CHAIN_COUNT) {
            grant(player, "oh_no");
            recent.clear();
        }
    }

    private static void grant(ServerPlayer player, String path) {
        Advancement advancement = player.server.getAdvancements()
                .getAdvancement(new ResourceLocation(MultiCoreHelperForge.MOD_ID, path));
        if (advancement != null) {
            player.getAdvancements().award(advancement, "ignited");
        }
    }
}
