package com.hardcoretoggle;

import org.bukkit.plugin.java.JavaPlugin;

public class HardcorePlugin extends JavaPlugin {

    private HardcoreManager manager;

    @Override
    public void onEnable() {
        saveDefaultConfig();

        manager = new HardcoreManager(this);
        manager.load();

        getServer().getPluginManager().registerEvents(new PlayerListener(this, manager), this);

        HardcoreCommand command = new HardcoreCommand(this, manager);
        getCommand("hardcore").setExecutor(command);
        getCommand("hardcore").setTabCompleter(command);

        if (getServer().getPluginManager().isPluginEnabled("PlaceholderAPI")) {
            new HardcorePlaceholders(this, manager).register();
            getLogger().info("PlaceholderAPI found; registered %hardcoretoggle_...% placeholders.");
        }

        getLogger().info("HardcoreToggle enabled.");
    }

    @Override
    public void onDisable() {
        if (manager != null) {
            manager.shutdown();
        }
    }

    public HardcoreManager getManager() {
        return manager;
    }
}
