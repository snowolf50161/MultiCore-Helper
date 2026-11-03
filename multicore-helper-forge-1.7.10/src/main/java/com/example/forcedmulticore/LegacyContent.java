package com.example.forcedmulticore;

import cpw.mods.fml.common.registry.GameRegistry;
import net.minecraft.creativetab.CreativeTabs;
import net.minecraft.init.Items;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.stats.Achievement;
import net.minecraftforge.common.AchievementPage;

final class LegacyContent {
    static Item cpuCoreItem;
    static Achievement fineAchievement;
    static Achievement ohMyPcAchievement;
    static Achievement ohNoAchievement;

    private static final CreativeTabs TAB = new CreativeTabs(MultiCoreHelperLegacy.MOD_ID) {
        @Override
        public Item getTabIconItem() {
            return cpuCoreItem == null ? Items.redstone : cpuCoreItem;
        }
    };

    private LegacyContent() {
    }

    static void register() {
        registerItem();
        registerAchievement();
    }

    private static void registerItem() {
        cpuCoreItem = new Item()
                .setUnlocalizedName(MultiCoreHelperLegacy.MOD_ID + ".cpu_core")
                .setTextureName(MultiCoreHelperLegacy.MOD_ID + ":cpu_core")
                .setCreativeTab(TAB);
        GameRegistry.registerItem(cpuCoreItem, "cpu_core");
    }

    private static void registerAchievement() {
        ItemStack icon = cpuCoreItem == null ? new ItemStack(Items.redstone) : new ItemStack(cpuCoreItem);
        fineAchievement = new Achievement(
                "achievement." + MultiCoreHelperLegacy.MOD_ID + ".fine_ill_do_it_myself",
                MultiCoreHelperLegacy.MOD_ID + ".fine_ill_do_it_myself",
                0,
                0,
                icon,
                null).initIndependentStat().registerStat();
        ohMyPcAchievement = new Achievement(
                "achievement." + MultiCoreHelperLegacy.MOD_ID + ".oh_my_pc",
                MultiCoreHelperLegacy.MOD_ID + ".oh_my_pc",
                2,
                0,
                new ItemStack(net.minecraft.init.Blocks.tnt),
                fineAchievement).registerStat();
        ohNoAchievement = new Achievement(
                "achievement." + MultiCoreHelperLegacy.MOD_ID + ".oh_no",
                MultiCoreHelperLegacy.MOD_ID + ".oh_no",
                4,
                0,
                new ItemStack(net.minecraft.init.Blocks.tnt),
                ohMyPcAchievement).setSpecial().registerStat();
        AchievementPage.registerAchievementPage(new AchievementPage(
                MultiCoreHelperLegacy.MOD_NAME, fineAchievement, ohMyPcAchievement, ohNoAchievement));
    }
}
