package com.example.forcedmulticore;

import net.minecraft.command.CommandBase;
import net.minecraft.command.ICommandSender;

final class MultiCoreCommand extends CommandBase {
    private final MultiCoreHelperLegacy mod;

    MultiCoreCommand(MultiCoreHelperLegacy mod) {
        this.mod = mod;
    }

    @Override
    public String getCommandName() {
        return "multicorecpu";
    }

    @Override
    public String getCommandUsage(ICommandSender sender) {
        return "/multicorecpu <status|entities|cleanup>";
    }

    @Override
    public int getRequiredPermissionLevel() {
        return 0;
    }

    @Override
    public void processCommand(ICommandSender sender, String[] args) {
        if (args.length == 0 || "status".equalsIgnoreCase(args[0])) {
            mod.sendStatus(sender);
        } else if ("entities".equalsIgnoreCase(args[0])) {
            mod.sendEntityStatus(sender);
        } else if ("cleanup".equalsIgnoreCase(args[0])) {
            if (!sender.canCommandSenderUseCommand(2, getCommandName())) {
                Chat.send(sender, "[MultiCore Helper] You need permission level 2 to run cleanup.");
                return;
            }
            mod.cleanupLooseEntities(sender);
        } else {
            Chat.send(sender, getCommandUsage(sender));
        }
    }
}
