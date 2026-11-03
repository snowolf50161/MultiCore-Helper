package com.example.forcedmulticore;

import net.minecraft.command.ICommandSender;
import net.minecraft.util.text.TextComponentString;

final class Chat {
    private Chat() {
    }

    static void send(ICommandSender sender, String line) {
        sender.sendMessage(new TextComponentString(line));
    }
}
