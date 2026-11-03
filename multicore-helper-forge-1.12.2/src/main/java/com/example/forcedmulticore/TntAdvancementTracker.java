package com.example.forcedmulticore;

import net.minecraft.advancements.Advancement;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.item.EntityTNTPrimed;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.util.ResourceLocation;
import net.minecraftforge.event.entity.EntityJoinWorldEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@Mod.EventBusSubscriber(modid = MultiCoreHelper1122.MOD_ID)
public final class TntAdvancementTracker {
    private static final long WINDOW_MS = 4000L;
    private static final int CHAIN_COUNT = 8;
    private static final Map<UUID, ArrayDeque<Long>> IGNITIONS = new HashMap<UUID, ArrayDeque<Long>>();

    private TntAdvancementTracker() {
    }

    @SubscribeEvent
    public static void onEntityJoin(EntityJoinWorldEvent event) {
        if (event.getWorld().isRemote || !(event.getEntity() instanceof EntityTNTPrimed)) {
            return;
        }
        EntityLivingBase owner = ((EntityTNTPrimed) event.getEntity()).getTntPlacedBy();
        if (!(owner instanceof EntityPlayerMP)) {
            return;
        }
        EntityPlayerMP player = (EntityPlayerMP) owner;
        grant(player, "oh_my_pc");

        long now = System.currentTimeMillis();
        ArrayDeque<Long> recent = IGNITIONS.get(player.getUniqueID());
        if (recent == null) {
            recent = new ArrayDeque<Long>();
            IGNITIONS.put(player.getUniqueID(), recent);
        }
        while (!recent.isEmpty() && now - recent.peekFirst() > WINDOW_MS) {
            recent.removeFirst();
        }
        recent.addLast(now);
        if (recent.size() >= CHAIN_COUNT) {
            grant(player, "oh_no");
            recent.clear();
        }
    }

    private static void grant(EntityPlayerMP player, String path) {
        Advancement advancement = player.getServer().getAdvancementManager()
                .getAdvancement(new ResourceLocation(MultiCoreHelper1122.MOD_ID, path));
        if (advancement != null) {
            player.getAdvancements().grantCriterion(advancement, "ignited");
        }
    }
}
