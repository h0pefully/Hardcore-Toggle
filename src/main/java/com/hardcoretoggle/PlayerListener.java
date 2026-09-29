package com.hardcoretoggle;

import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.entity.PlayerDeathEvent;

public class PlayerListener implements Listener {

    private final HardcorePlugin plugin;
    private final HardcoreManager manager;

    public PlayerListener(HardcorePlugin plugin, HardcoreManager manager) {
        this.plugin = plugin;
        this.manager = manager;
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        // Restore the tab-list marker for anyone rejoining with Hardcore already on.
        manager.updateTabList(player);

        // If they logged off while downed, put them back in spectator mode
        // at their death spot until someone revives them.
        Location deathLocation = manager.getDeathLocation(player.getUniqueId());
        if (deathLocation != null) {
            player.setGameMode(GameMode.SPECTATOR);
            player.teleport(deathLocation);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDeath(PlayerDeathEvent event) {
        Player player = event.getEntity();

        // Regular survival players: nothing special happens, vanilla death/respawn applies.
        if (!manager.isHardcore(player.getUniqueId())) {
            return;
        }

        manager.markDowned(player, player.getLocation());
    }

    @EventHandler
    public void onRespawn(PlayerRespawnEvent event) {
        Player player = event.getPlayer();

        Location deathLocation = manager.getDeathLocation(player.getUniqueId());
        if (deathLocation == null) {
            return; // not a downed Hardcore player, respawn normally
        }

        // Respawn them right where they died and drop them into spectator
        // mode until someone revives them by clicking their head.
        event.setRespawnLocation(deathLocation);
        plugin.getServer().getScheduler().runTask(plugin, () -> player.setGameMode(GameMode.SPECTATOR));
    }

    /**
     * Someone right-clicking a downed player's head marker revives them.
     */
    @EventHandler
    public void onInteract(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK) {
            return;
        }
        Block clicked = event.getClickedBlock();
        if (clicked == null) {
            return;
        }
        if (manager.tryReviveByClick(event.getPlayer(), clicked)) {
            event.setCancelled(true);
        }
    }

    /**
     * A head marker can only be removed by successfully reviving its owner,
     * not by just mining it.
     */
    @EventHandler
    public void onBreak(BlockBreakEvent event) {
        if (manager.isHeadMarker(event.getBlock().getLocation())) {
            event.setCancelled(true);
        }
    }

    /**
     * Downed players are frozen in place (still in Spectator mode so they
     * can watch, but can't fly off) until someone revives them. They can
     * still look around freely — only actual movement is blocked.
     */
    @EventHandler(ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        Player player = event.getPlayer();
        Location frozenAt = manager.getDeathLocation(player.getUniqueId());
        if (frozenAt == null) {
            return; // not downed, nothing to enforce
        }

        Location to = event.getTo();
        if (to.getX() == frozenAt.getX() && to.getY() == frozenAt.getY() && to.getZ() == frozenAt.getZ()) {
            return; // hasn't actually moved position, just possibly looked around
        }

        // Snap position back but keep whatever direction they're currently looking.
        event.setTo(new Location(frozenAt.getWorld(), frozenAt.getX(), frozenAt.getY(), frozenAt.getZ(),
                to.getYaw(), to.getPitch()));
    }
}
