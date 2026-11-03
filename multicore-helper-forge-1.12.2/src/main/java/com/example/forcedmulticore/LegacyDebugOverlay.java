package com.example.forcedmulticore;

import net.minecraft.client.Minecraft;
import net.minecraft.client.settings.KeyBinding;
import net.minecraft.util.text.TextComponentString;
import net.minecraft.util.text.TextComponentTranslation;
import net.minecraftforge.client.event.RenderGameOverlayEvent;
import net.minecraftforge.fml.client.registry.ClientRegistry;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.InputEvent;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;
import org.lwjgl.input.Keyboard;

@SideOnly(Side.CLIENT)
final class LegacyDebugOverlay {
    private static final KeyBinding TOGGLE_KEY = new KeyBinding(
            "key.forcedmulticore.toggle_debug", Keyboard.KEY_F8, "key.categories.forcedmulticore");

    private final MultiCoreHelper1122 mod;
    private final ClientUpdateChecker updateChecker;
    private boolean showInfo;

    LegacyDebugOverlay(MultiCoreHelper1122 mod) {
        this.mod = mod;
        this.updateChecker = new ClientUpdateChecker(MultiCoreHelper1122.VERSION, "1.12.2", "Forge");
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
        ClientUpdateChecker.Result update = minecraft.player == null ? null : updateChecker.poll();
        if (update != null) {
            TextComponentTranslation message = new TextComponentTranslation(
                    "message.forcedmulticore.update_available",
                    update.currentVersion,
                    update.latestVersion);
            message.appendSibling(new TextComponentString(" " + update.downloadUrl));
            minecraft.player.sendMessage(message);
        }
        if (showInfo && minecraft.gameSettings.showDebugInfo) {
            event.getRight().addAll(mod.getF3Lines());
        }
    }
}
