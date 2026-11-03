package com.example.forcedmulticore;

import net.minecraft.client.renderer.block.model.ModelResourceLocation;
import net.minecraftforge.client.event.ModelRegistryEvent;
import net.minecraftforge.client.model.ModelLoader;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.relauncher.Side;

@Mod.EventBusSubscriber(modid = MultiCoreHelper1122.MOD_ID, value = Side.CLIENT)
public final class ClientModelRegistration {
    private ClientModelRegistration() {
    }

    @SubscribeEvent
    public static void registerModels(ModelRegistryEvent event) {
        if (LegacyContent.cpuCoreItem != null) {
            ModelLoader.setCustomModelResourceLocation(
                    LegacyContent.cpuCoreItem,
                    0,
                    new ModelResourceLocation(LegacyContent.cpuCoreItem.getRegistryName(), "inventory"));
        }
    }
}
