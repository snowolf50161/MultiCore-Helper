package com.example.forcedmulticore;

import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.item.EntityTNTPrimed;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraftforge.event.entity.EntityJoinWorldEvent;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

final class TntAchievementTracker {
    private static final long WINDOW_MS = 4000L;
    private static final int CHAIN_COUNT = 8;
    private final Map<UUID, ArrayDeque<Long>> ignitions = new HashMap<UUID, ArrayDeque<Long>>();

    @SubscribeEvent
    public void onEntityJoin(EntityJoinWorldEvent event) {
        if (event.world.isRemote || !(event.entity instanceof EntityTNTPrimed)) {
            return;
        }
        EntityLivingBase owner = ((EntityTNTPrimed) event.entity).getTntPlacedBy();
        if (!(owner instanceof EntityPlayer)) {
            return;
        }
        EntityPlayer player = (EntityPlayer) owner;
        if (LegacyContent.ohMyPcAchievement != null) {
            player.triggerAchievement(LegacyContent.ohMyPcAchievement);
        }

        long now = System.currentTimeMillis();
        ArrayDeque<Long> recent = ignitions.get(player.getUniqueID());
        if (recent == null) {
            recent = new ArrayDeque<Long>();
            ignitions.put(player.getUniqueID(), recent);
        }
        while (!recent.isEmpty() && now - recent.peekFirst() > WINDOW_MS) {
            recent.removeFirst();
        }
        recent.addLast(now);
        if (recent.size() >= CHAIN_COUNT && LegacyContent.ohNoAchievement != null) {
            player.triggerAchievement(LegacyContent.ohNoAchievement);
            recent.clear();
        }
    }
}
