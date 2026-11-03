package com.example.forcedmulticore;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.minecraft.client.KeyMapping;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import com.mojang.blaze3d.platform.InputConstants;
import org.lwjgl.glfw.GLFW;

public final class MultiCoreHelperFabricClient implements ClientModInitializer {
    private static final String VERSION = "1.0.6";
    private static boolean showF3Info;

    public static boolean isF3InfoEnabled() {
        return showF3Info;
    }

    @Override
    public void onInitializeClient() {
        ClientUpdateChecker updateChecker = new ClientUpdateChecker(VERSION, "26.2", "Fabric");
        updateChecker.start();
        KeyMapping.Category category = KeyMapping.Category.register(
                Identifier.fromNamespaceAndPath(MultiCoreHelperFabric.MOD_ID, "controls"));
        KeyMapping toggleKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key.forcedmulticore.toggle_debug",
                InputConstants.Type.KEYSYM,
                GLFW.GLFW_KEY_F8,
                category));
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            while (toggleKey.consumeClick()) {
                showF3Info = !showF3Info;
            }
            ClientUpdateChecker.Result update = client.player == null ? null : updateChecker.poll();
            if (update != null) {
                client.player.sendSystemMessage(
                        Component.translatable(
                                "message.forcedmulticore.update_available",
                                update.currentVersion,
                                update.latestVersion)
                                .append(" " + update.downloadUrl));
            }
        });

        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) ->
                dispatcher.register(ClientCommands.literal("multicorecpu")
                        .then(ClientCommands.literal("clientstatus")
                                .executes(context -> {
                                    for (String line : MultiCoreHelperFabric.statusLines()) {
                                        context.getSource().sendFeedback(Component.literal(line));
                                    }
                                    context.getSource().sendFeedback(Component.literal("[MultiCore Helper] Multiplayer note: /multicorecpu status, entities, and cleanup require the server to install this mod."));
                                    return 1;
                                }))));
    }
}
