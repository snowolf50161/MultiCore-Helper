package com.example.forcedmulticore;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.text.Text;
import org.lwjgl.glfw.GLFW;

public final class MultiCoreHelperFabricClient implements ClientModInitializer {
    private static final String VERSION = "1.0.6";
    private static boolean showF3Info;

    public static boolean isF3InfoEnabled() {
        return showF3Info;
    }

    @Override
    public void onInitializeClient() {
        ClientUpdateChecker updateChecker = new ClientUpdateChecker(VERSION, "1.21.8", "Fabric");
        updateChecker.start();
        KeyBinding toggleKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.forcedmulticore.toggle_debug",
                InputUtil.Type.KEYSYM,
                GLFW.GLFW_KEY_F8,
                "key.categories.forcedmulticore"));
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            while (toggleKey.wasPressed()) {
                showF3Info = !showF3Info;
            }
            ClientUpdateChecker.Result update = client.player == null ? null : updateChecker.poll();
            if (update != null) {
                client.player.sendMessage(
                        Text.translatable(
                                "message.forcedmulticore.update_available",
                                update.currentVersion,
                                update.latestVersion)
                                .append(Text.literal(" " + update.downloadUrl)),
                        false);
            }
        });

        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) ->
                dispatcher.register(ClientCommandManager.literal("multicorecpu")
                        .then(ClientCommandManager.literal("clientstatus")
                                .executes(context -> {
                                    for (String line : MultiCoreHelperFabric.statusLines()) {
                                        context.getSource().sendFeedback(Text.literal(line));
                                    }
                                    context.getSource().sendFeedback(Text.literal("[MultiCore Helper] Multiplayer note: /multicorecpu status, entities, and cleanup require the server to install this mod."));
                                    return 1;
                                }))));
    }
}
