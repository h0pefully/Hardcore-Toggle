package com.hardcoretoggle;

import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.entity.Player;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public class HardcoreCommand implements CommandExecutor, TabCompleter {

    private final HardcorePlugin plugin;
    private final HardcoreManager manager;

    public HardcoreCommand(HardcorePlugin plugin, HardcoreManager manager) {
        this.plugin = plugin;
        this.manager = manager;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 1 && args[0].equalsIgnoreCase("reload")) {
            if (!sender.hasPermission("hardcoretoggle.reload")) {
                sender.sendMessage(manager.getMessage("no-permission", "<prefix><red>You don't have permission to do that."));
                return true;
            }
            plugin.reloadConfig();
            manager.reloadMessages();
            manager.refreshAll();
            sender.sendMessage(manager.getMessage("reloaded", "<prefix><green>HardcoreToggle config reloaded."));
            return true;
        }

        if (args.length >= 1 && args[0].equalsIgnoreCase("revive")) {
            if (!sender.hasPermission("hardcoretoggle.admin")) {
                sender.sendMessage(manager.getMessage("no-permission", "<prefix><red>You don't have permission to do that."));
                return true;
            }

            Player target;
            if (args.length >= 2) {
                target = plugin.getServer().getPlayer(args[1]);
                if (target == null) {
                    sender.sendMessage(manager.getMessage("player-not-found", "<prefix><red>That player isn't online."));
                    return true;
                }
            } else if (sender instanceof Player selfPlayer) {
                target = selfPlayer;
            } else {
                sender.sendMessage(manager.getMessage("usage", "<prefix><red>Usage: /hardcore revive <player> (required from console)"));
                return true;
            }

            TagResolver playerTag = Messages.text("player", target.getName());
            if (manager.forceRevive(target)) {
                sender.sendMessage(manager.getMessage("force-revived", "<prefix><green>Revived <player>.", playerTag));
            } else {
                sender.sendMessage(manager.getMessage("not-downed", "<prefix><red><player> isn't currently downed.", playerTag));
            }
            return true;
        }

        if (!(sender instanceof Player player)) {
            sender.sendMessage("This command can only be used by players.");
            return true;
        }

        if (args.length != 1 || !args[0].equalsIgnoreCase("toggle")) {
            player.sendMessage(manager.getMessage("usage", "<prefix><red>Usage: /hardcore toggle"));
            return true;
        }

        if (manager.isDowned(player.getUniqueId())) {
            player.sendMessage(manager.getMessage("cannot-toggle-while-downed", "<prefix><red>You can't change Hardcore mode while waiting to be revived."));
            return true;
        }

        if (manager.isHardcore(player.getUniqueId())) {
            manager.disable(player);
            player.sendMessage(manager.getMessage("disabled-self", "<prefix><green>Hardcore mode disabled."));
        } else {
            manager.enable(player);
            player.sendMessage(manager.getMessage("enabled-self", "<prefix><red><theme>Hardcore mode</theme> enabled. Good luck out there."));
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 1) {
            if (sender.hasPermission("hardcoretoggle.admin")) {
                return Arrays.asList("toggle", "reload", "revive");
            }
            if (sender.hasPermission("hardcoretoggle.reload")) {
                return Arrays.asList("toggle", "reload");
            }
            return Collections.singletonList("toggle");
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("revive") && sender.hasPermission("hardcoretoggle.admin")) {
            return plugin.getServer().getOnlinePlayers().stream().map(Player::getName).toList();
        }
        return Collections.emptyList();
    }
}
