package com.example.forcedmulticore;

import net.minecraft.command.CommandBase;
import net.minecraft.command.ICommandSender;
import net.minecraft.server.MinecraftServer;

final class MultiCoreCommand extends CommandBase {
    private final MultiCoreHelper1122 mod;

    MultiCoreCommand(MultiCoreHelper1122 mod) {
        this.mod = mod;
    }

    @Override
    public String getName() {
        return "multicorecpu";
    }

    @Override
    public String getUsage(ICommandSender sender) {
        return "/multicorecpu <status|entities|cleanup>";
    }

    @Override
    public int getRequiredPermissionLevel() {
        return 0;
    }

    @Override
    public void execute(MinecraftServer server, ICommandSender sender, String[] args) {
        if (args.length == 0 || "status".equalsIgnoreCase(args[0])) {
            mod.sendStatus(sender);
        } else if ("entities".equalsIgnoreCase(args[0])) {
            mod.sendEntityStatus(sender);
        } else if ("cleanup".equalsIgnoreCase(args[0])) {
            if (!sender.canUseCommand(2, getName())) {
                Chat.send(sender, "[MultiCore Helper] You need permission level 2 to run cleanup.");
                return;
            }
            mod.cleanupLooseEntities(sender);
        } else {
            Chat.send(sender, getUsage(sender));
        }
    }
}
