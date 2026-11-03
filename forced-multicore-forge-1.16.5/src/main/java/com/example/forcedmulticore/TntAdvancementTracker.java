package com.example.forcedmulticore;

import net.minecraft.advancements.Advancement;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.item.TNTEntity;
import net.minecraft.entity.player.ServerPlayerEntity;
import net.minecraft.util.ResourceLocation;
import net.minecraftforge.event.entity.EntityJoinWorldEvent;
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
    public void onEntityJoin(EntityJoinWorldEvent event) {
        if (event.getWorld().isRemote() || !(event.getEntity() instanceof TNTEntity)) {
            return;
        }
        LivingEntity owner = ((TNTEntity) event.getEntity()).getIgniter();
        if (!(owner instanceof ServerPlayerEntity)) {
            return;
        }
        ServerPlayerEntity player = (ServerPlayerEntity) owner;
        grant(player, "oh_my_pc");
        long now = System.currentTimeMillis();
        ArrayDeque<Long> recent = ignitions.computeIfAbsent(player.getUniqueID(), ignored -> new ArrayDeque<>());
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
        Advancement advancement = player.getServer().getAdvancementManager()
                .getAdvancement(new ResourceLocation(ForcedMultiCoreMod.MOD_ID, path));
        if (advancement != null) {
            player.getAdvancements().grantCriterion(advancement, "ignited");
        }
    }
}
