package com.example.forcedmulticore;

import net.minecraft.command.ICommandSender;
import net.minecraft.util.ChatComponentText;

final class Chat {
    private Chat() {
    }

    static void send(ICommandSender sender, String line) {
        sender.addChatMessage(new ChatComponentText(line));
    }
}
