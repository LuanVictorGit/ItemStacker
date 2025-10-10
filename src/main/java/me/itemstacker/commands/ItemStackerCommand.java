package me.itemstacker.commands;

import java.util.ArrayList;
import java.util.List;

import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.command.TabCompleter;

import me.itemstacker.Core;

public class ItemStackerCommand implements CommandExecutor, TabCompleter {

    public ItemStackerCommand() {
        PluginCommand command = Core.getInstance().getCommand("itemstacker");
        if (command != null) {
            command.setExecutor(this);
            command.setTabCompleter(this);
        }
    }

    @Override
    public boolean onCommand(CommandSender s, Command c, String lb, String[] args) {

        if (!s.hasPermission("itemstacker.adm")) {
            s.sendMessage("§cYou do not have permission to do that!");
            return true;
        }

        if (args.length == 0 || args[0].equalsIgnoreCase("help")) {
            sendHelp(s);
            return true;
        }

        if (args.length == 1 && args[0].equalsIgnoreCase("reload")) {
            Core.getInstance().reloadPlugin();
            s.sendMessage("§aPlugin reloaded successfully.");
            return true;
        }

        s.sendMessage("§cUnknown subcommand. Use §e/itemstacker help§c.");
        return true;
    }

    private void sendHelp(CommandSender s) {
        s.sendMessage("§e§m----------------------------------------");
        s.sendMessage("§6§lItemStacker Commands");
        s.sendMessage("§e/itemstacker help §7- Show this help");
        s.sendMessage("§e/itemstacker reload §7- Reload the plugin");
        s.sendMessage("§e§m----------------------------------------");
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command cmd, String label, String[] args) {
        List<String> list = new ArrayList<>();

        if (!sender.hasPermission("itemstacker.adm")) {
            return list;
        }

        if (args.length == 1) {
            if ("help".startsWith(args[0].toLowerCase())) list.add("help");
            if ("reload".startsWith(args[0].toLowerCase())) list.add("reload");
            return list;
        }

        return list;
    }
}
