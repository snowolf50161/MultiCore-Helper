package com.example.forcedmulticore;

import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;
import net.minecraft.client.Minecraft;
import net.minecraft.client.settings.KeyBinding;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.ChatComponentTranslation;
import cpw.mods.fml.client.registry.ClientRegistry;
import cpw.mods.fml.common.gameevent.InputEvent;
import net.minecraftforge.client.event.RenderGameOverlayEvent;
import org.lwjgl.input.Keyboard;

@SideOnly(Side.CLIENT)
final class LegacyDebugOverlay {
    private static final KeyBinding TOGGLE_KEY = new KeyBinding(
            "key.forcedmulticore.toggle_debug", Keyboard.KEY_F8, "key.categories.forcedmulticore");

    private final MultiCoreHelperLegacy mod;
    private final ClientUpdateChecker updateChecker;
    private boolean showInfo;

    LegacyDebugOverlay(MultiCoreHelperLegacy mod) {
        this.mod = mod;
        this.updateChecker = new ClientUpdateChecker(MultiCoreHelperLegacy.VERSION, "1.7.10", "Forge");
        this.updateChecker.start();
        ClientRegistry.registerKeyBinding(TOGGLE_KEY);
    }

    @SubscribeEvent
    public void onKeyInput(InputEvent.KeyInputEvent event) {
        while (TOGGLE_KEY.isPressed()) {
            showInfo = !showInfo;
        }
    }

    @SubscribeEvent
    public void onDebugText(RenderGameOverlayEvent.Text event) {
        Minecraft minecraft = Minecraft.getMinecraft();
        ClientUpdateChecker.Result update = minecraft.thePlayer == null ? null : updateChecker.poll();
        if (update != null) {
            ChatComponentTranslation message = new ChatComponentTranslation(
                    "message.forcedmulticore.update_available",
                    update.currentVersion,
                    update.latestVersion);
            message.appendSibling(new ChatComponentText(" " + update.downloadUrl));
            minecraft.thePlayer.addChatMessage(message);
        }
        if (showInfo && minecraft.gameSettings.showDebugInfo) {
            event.right.addAll(mod.getF3Lines());
        }
    }
}
