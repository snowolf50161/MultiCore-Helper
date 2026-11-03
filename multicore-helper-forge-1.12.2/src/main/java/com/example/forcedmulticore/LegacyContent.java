package com.example.forcedmulticore;

import net.minecraft.creativetab.CreativeTabs;
import net.minecraft.init.Items;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraftforge.event.RegistryEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;

@Mod.EventBusSubscriber(modid = MultiCoreHelper1122.MOD_ID)
public final class LegacyContent {
    static Item cpuCoreItem;

    private static final CreativeTabs TAB = new CreativeTabs(MultiCoreHelper1122.MOD_ID) {
        @Override
        public ItemStack createIcon() {
            return new ItemStack(cpuCoreItem == null ? Items.REDSTONE : cpuCoreItem);
        }
    };

    private LegacyContent() {
    }

    @SubscribeEvent
    public static void registerItems(RegistryEvent.Register<Item> event) {
        cpuCoreItem = new Item()
                .setTranslationKey(MultiCoreHelper1122.MOD_ID + ".cpu_core")
                .setRegistryName(MultiCoreHelper1122.MOD_ID, "cpu_core")
                .setCreativeTab(TAB);
        event.getRegistry().register(cpuCoreItem);
    }
}
