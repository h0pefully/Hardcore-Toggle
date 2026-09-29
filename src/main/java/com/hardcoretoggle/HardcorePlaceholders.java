package com.hardcoretoggle;

import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.OfflinePlayer;
import org.jetbrains.annotations.NotNull;

/**
 * Exposes HardcoreToggle's state to PlaceholderAPI so other plugins (LuckPerms-aware
 * nametag/tab plugins like TAB, chat plugins, scoreboards, etc.) can display it
 * without HardcoreToggle needing to own the tab list or nametag itself.
 *
 * Placeholders:
 *   %hardcoretoggle_icon%      - the configured icon (nametag-placeholder-icon) if the
 *                                 player is in Hardcore mode, otherwise an empty string.
 *   %hardcoretoggle_active%    - "true"/"false" for whether the player is in Hardcore mode.
 *   %hardcoretoggle_downed%    - "true"/"false" for whether the player is currently downed.
 */
public class HardcorePlaceholders extends PlaceholderExpansion {

    private final HardcorePlugin plugin;
    private final HardcoreManager manager;

    public HardcorePlaceholders(HardcorePlugin plugin, HardcoreManager manager) {
        this.plugin = plugin;
        this.manager = manager;
    }

    @Override
    public @NotNull String getIdentifier() {
        return "hardcoretoggle";
    }

    @Override
    public @NotNull String getAuthor() {
        return "You";
    }

    @Override
    public @NotNull String getVersion() {
        return plugin.getPluginMeta().getVersion();
    }

    // Keeps the expansion registered across /papi reload without needing to re-run
    // the plugin's own registration logic.
    @Override
    public boolean persist() {
        return true;
    }

    @Override
    public String onRequest(OfflinePlayer player, @NotNull String params) {
        if (player == null) {
            return "";
        }

        boolean hardcore = manager.isHardcore(player.getUniqueId());

        return switch (params.toLowerCase()) {
            case "icon" -> hardcore ? plugin.getConfig().getString("nametag-placeholder-icon", "&c☠ ") : "";
            case "active" -> String.valueOf(hardcore);
            case "downed" -> String.valueOf(manager.isDowned(player.getUniqueId()));
            default -> null;
        };
    }
}
