package com.hardcoretoggle;

import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.Skull;
import org.bukkit.block.data.BlockData;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;

/**
 * Tracks who has opted into Hardcore mode, who is currently "downed" (waiting
 * to be revived), and handles persistence, tab-list/nametag styling, the
 * death-head marker, and the timed revive.
 */
public class HardcoreManager {

    private final HardcorePlugin plugin;
    private final File dataFile;
    private final Messages messages;

    // Players who have toggled Hardcore mode on.
    private final Set<UUID> hardcorePlayers = new HashSet<>();

    // Players currently dead-and-waiting-to-be-revived, keyed by their UUID.
    private final Map<UUID, DownedState> downedPlayers = new HashMap<>();

    // Downed entries loaded from disk whose world isn't loaded yet.
    private final Map<UUID, PendingDowned> pendingDowned = new HashMap<>();

    // Active timed revives, indexed both by who is reviving and who is being revived.
    private final Map<UUID, ReviveSession> sessionsByReviver = new HashMap<>();
    private final Map<UUID, ReviveSession> sessionsByTarget = new HashMap<>();

    // Single background thread so disk writes never block the server tick
    // and always happen in the order they were requested.
    private final ExecutorService ioExecutor = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "HardcoreToggle-IO");
        thread.setDaemon(true);
        return thread;
    });

    /**
     * Everything we need to remember about a downed player: where they died
     * (also where their head marker sits and where they'll be teleported
     * back to on revive), and whatever block used to be there so we can put
     * it back once the head is removed.
     */
    private record DownedState(Location location, BlockData originalBlockData) {
    }

    /** A downed entry read from data.yml that is waiting for its world to load. */
    private record PendingDowned(UUID worldId, double x, double y, double z, float yaw, float pitch,
                                 String blockData) {
    }

    /** One in-progress timed revive. */
    private static final class ReviveSession {
        final Player reviver;
        final UUID targetId;
        final String targetName;
        final BossBar bar;
        final int totalSteps;
        int step = 0;
        BukkitTask task;

        ReviveSession(Player reviver, UUID targetId, String targetName, BossBar bar, int totalSteps) {
            this.reviver = reviver;
            this.targetId = targetId;
            this.targetName = targetName;
            this.bar = bar;
            this.totalSteps = totalSteps;
        }
    }

    // Loading-spinner frames and the major scale used for the revive melody.
    private static final String[] SPINNER = {"\u25D0", "\u25D3", "\u25D1", "\u25D2"};
    private static final int[] SCALE = {0, 2, 4, 5, 7, 9, 11};
    private static final int STEP_TICKS = 2;

    public HardcoreManager(HardcorePlugin plugin) {
        this.plugin = plugin;
        this.dataFile = new File(plugin.getDataFolder(), "data.yml");
        this.messages = new Messages(plugin);
        this.messages.load();
    }

    /** Re-reads the theme/prefix/messages from config.yml. Call after /hardcore reload. */
    public void reloadMessages() {
        messages.load();
    }

    // ---------------------------------------------------------------
    // Persistence
    // ---------------------------------------------------------------

    public void load() {
        if (!dataFile.exists()) {
            return;
        }
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(dataFile);
        for (String uuidString : yaml.getStringList("hardcore-players")) {
            try {
                hardcorePlayers.add(UUID.fromString(uuidString));
            } catch (IllegalArgumentException ignored) {
                // Skip malformed entries rather than failing the whole load.
            }
        }

        ConfigurationSection downed = yaml.getConfigurationSection("downed");
        if (downed != null) {
            for (String key : downed.getKeys(false)) {
                ConfigurationSection section = downed.getConfigurationSection(key);
                if (section == null) {
                    continue;
                }
                try {
                    pendingDowned.put(UUID.fromString(key), new PendingDowned(
                            UUID.fromString(section.getString("world", "")),
                            section.getDouble("x"),
                            section.getDouble("y"),
                            section.getDouble("z"),
                            (float) section.getDouble("yaw"),
                            (float) section.getDouble("pitch"),
                            section.getString("block", "minecraft:air")));
                } catch (IllegalArgumentException ignored) {
                    // Malformed entry; skip it.
                }
            }
        }
        resolvePending();
    }

    /**
     * Turns any loaded-from-disk downed entries into live ones once their
     * world exists. Called after load() and whenever a world loads.
     */
    public void resolvePending() {
        if (pendingDowned.isEmpty()) {
            return;
        }
        Iterator<Map.Entry<UUID, PendingDowned>> iterator = pendingDowned.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<UUID, PendingDowned> entry = iterator.next();
            PendingDowned pending = entry.getValue();
            World world = Bukkit.getWorld(pending.worldId());
            if (world == null) {
                continue;
            }
            BlockData blockData;
            try {
                blockData = Bukkit.createBlockData(pending.blockData());
            } catch (IllegalArgumentException e) {
                blockData = Material.AIR.createBlockData();
            }
            downedPlayers.put(entry.getKey(), new DownedState(
                    new Location(world, pending.x(), pending.y(), pending.z(), pending.yaw(), pending.pitch()),
                    blockData));
            iterator.remove();
        }
    }

    /**
     * Snapshots the data on the main thread (cheap), then writes it to disk
     * on the background thread so file I/O never stalls the server.
     */
    public void save() {
        String snapshot = buildYaml();
        try {
            ioExecutor.execute(() -> writeFile(snapshot));
        } catch (RejectedExecutionException e) {
            writeFile(snapshot); // executor already shut down; fall back to writing inline
        }
    }

    /** Called from onDisable: stop revives, flush pending writes, write the final state. */
    public void shutdown() {
        cancelAllRevives();
        String snapshot = buildYaml();
        ioExecutor.shutdown();
        try {
            ioExecutor.awaitTermination(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        writeFile(snapshot);
    }

    private String buildYaml() {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("hardcore-players", hardcorePlayers.stream().map(UUID::toString).toList());

        for (Map.Entry<UUID, DownedState> entry : downedPlayers.entrySet()) {
            Location location = entry.getValue().location();
            if (location.getWorld() == null) {
                continue;
            }
            String base = "downed." + entry.getKey();
            yaml.set(base + ".world", location.getWorld().getUID().toString());
            yaml.set(base + ".x", location.getX());
            yaml.set(base + ".y", location.getY());
            yaml.set(base + ".z", location.getZ());
            yaml.set(base + ".yaw", (double) location.getYaw());
            yaml.set(base + ".pitch", (double) location.getPitch());
            yaml.set(base + ".block", entry.getValue().originalBlockData().getAsString());
        }
        for (Map.Entry<UUID, PendingDowned> entry : pendingDowned.entrySet()) {
            PendingDowned p = entry.getValue();
            String base = "downed." + entry.getKey();
            yaml.set(base + ".world", p.worldId().toString());
            yaml.set(base + ".x", p.x());
            yaml.set(base + ".y", p.y());
            yaml.set(base + ".z", p.z());
            yaml.set(base + ".yaw", (double) p.yaw());
            yaml.set(base + ".pitch", (double) p.pitch());
            yaml.set(base + ".block", p.blockData());
        }
        return yaml.saveToString();
    }

    // Runs on the I/O thread (or inline during shutdown). Writes to a temp
    // file first, then swaps it in, so a crash can't leave a half-written data.yml.
    private synchronized void writeFile(String contents) {
        try {
            Path target = dataFile.toPath();
            Files.createDirectories(target.toAbsolutePath().getParent());
            Path temp = target.resolveSibling(dataFile.getName() + ".tmp");
            Files.writeString(temp, contents);
            try {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            plugin.getLogger().warning("Could not save HardcoreToggle data: " + e.getMessage());
        }
    }

    // ---------------------------------------------------------------
    // Hardcore state
    // ---------------------------------------------------------------

    public boolean isHardcore(UUID uuid) {
        return hardcorePlayers.contains(uuid);
    }

    public boolean isDowned(UUID uuid) {
        return downedPlayers.containsKey(uuid);
    }

    /** Cheap check used by the movement handler to bail out early. */
    public boolean hasAnyDowned() {
        return !downedPlayers.isEmpty();
    }

    /**
     * The exact spot a downed player is frozen at / will be teleported back
     * to on revive. Returns null if they aren't downed.
     */
    public Location getDeathLocation(UUID uuid) {
        DownedState state = downedPlayers.get(uuid);
        return state == null ? null : state.location();
    }

    /**
     * Enables Hardcore mode for a player, updates their tab-list name, and
     * (optionally) broadcasts it. Returns false if they were already in it.
     */
    public boolean enable(Player player) {
        if (!hardcorePlayers.add(player.getUniqueId())) {
            return false;
        }
        save();
        updateTabList(player);

        if (plugin.getConfig().getBoolean("broadcast-on-enable", true)) {
            Component message = messages.get("enabled-broadcast",
                    "<prefix><player> has enabled <theme>Hardcore mode</theme>!",
                    Messages.text("player", player.getName()));
            plugin.getServer().broadcast(message);
        }
        return true;
    }

    /**
     * Disables Hardcore mode for a player and resets their tab-list name.
     * Returns false if they weren't in it.
     */
    public boolean disable(Player player) {
        if (!hardcorePlayers.remove(player.getUniqueId())) {
            return false;
        }
        save();
        updateTabList(player);
        return true;
    }

    // ---------------------------------------------------------------
    // Tab list
    // ---------------------------------------------------------------

    /**
     * Applies (or removes) the Hardcore prefix on this player's tab-list name.
     */
    public void updateTabList(Player player) {
        if (isHardcore(player.getUniqueId())) {
            String prefix = plugin.getConfig().getString("tab-prefix", "<red>\u2620 </red>");
            Component prefixComponent = MiniMessage.miniMessage().deserialize(prefix);
            player.playerListName(prefixComponent.append(Component.text(player.getName())));
        } else {
            player.playerListName(Component.text(player.getName()));
        }
    }

    /** Re-applies tab list styling for everyone online (used after /hardcore reload). */
    public void refreshAll() {
        for (Player online : plugin.getServer().getOnlinePlayers()) {
            updateTabList(online);
        }
    }

    // ---------------------------------------------------------------
    // Death
    // ---------------------------------------------------------------

    /**
     * Called when a Hardcore player dies. Marks them downed, places a head
     * marker with their skin at the death spot, and shows the death title.
     */
    public void markDowned(Player player, Location deathLocation) {
        Block block = deathLocation.getBlock();
        BlockData originalBlockData = block.getBlockData().clone();

        block.setType(Material.PLAYER_HEAD, false);
        if (block.getState() instanceof Skull skull) {
            skull.setPlayerProfile(player.getPlayerProfile());
            skull.update(true, false);
        }

        downedPlayers.put(player.getUniqueId(), new DownedState(deathLocation.clone(), originalBlockData));
        save();

        Component title = messages.get("death-title", "<dark_red><b>You've Died</b></dark_red>");
        Component subtitle = messages.get("death-subtitle", "<gray>Someone needs to right-click your head to revive you");

        player.showTitle(Title.title(
                title,
                subtitle,
                Title.Times.times(Duration.ofMillis(500), Duration.ofSeconds(4), Duration.ofSeconds(1))
        ));

        Component deathBroadcast = messages.get("death-broadcast",
                "<prefix><dark_red><player> has died!</dark_red> <gray>Find their head and right-click it to bring them back.",
                Messages.text("player", player.getName()));
        plugin.getServer().broadcast(deathBroadcast);
    }

    // ---------------------------------------------------------------
    // Timed revive
    // ---------------------------------------------------------------

    /**
     * Called from the interact listener whenever someone right-clicks a
     * block. If that block is a downed player's head marker, starts a timed
     * revive. Returns true if the click was on a marker (so the listener
     * knows to cancel the interaction).
     */
    public boolean tryReviveByClick(Player clicker, Block clickedBlock) {
        if (clickedBlock.getType() != Material.PLAYER_HEAD) {
            return false;
        }

        for (Map.Entry<UUID, DownedState> entry : downedPlayers.entrySet()) {
            UUID downedUuid = entry.getKey();
            Location markerLocation = entry.getValue().location();

            if (!isSameBlock(markerLocation, clickedBlock.getLocation())) {
                continue;
            }
            if (downedUuid.equals(clicker.getUniqueId())) {
                clicker.sendMessage(getMessage("cannot-self-revive", "<prefix><red>You can't revive yourself \u2014 someone else has to do it."));
                return true;
            }

            Player downedPlayer = plugin.getServer().getPlayer(downedUuid);
            if (downedPlayer == null) {
                clicker.sendMessage(getMessage("target-offline", "<prefix><red>That player is offline right now. They can be revived once they rejoin."));
                return true;
            }

            startRevive(downedPlayer, clicker, entry.getValue());
            return true;
        }
        return false;
    }

    private void startRevive(Player downedPlayer, Player reviver, DownedState state) {
        if (sessionsByReviver.containsKey(reviver.getUniqueId())) {
            return; // already channeling; repeated clicks are ignored
        }
        if (sessionsByTarget.containsKey(downedPlayer.getUniqueId())) {
            reviver.sendMessage(getMessage("already-being-revived", "<prefix><red>Someone is already reviving that player."));
            return;
        }

        double seconds = Math.max(0.0, plugin.getConfig().getDouble("revive-duration-seconds", 3.0));
        if (seconds <= 0.0) {
            revive(downedPlayer, reviver, state);
            return;
        }

        int totalSteps = Math.max(1, (int) Math.round(seconds * 20.0 / STEP_TICKS));
        BossBar bar = BossBar.bossBar(
                barTitle(downedPlayer.getName(), 0, 0),
                0f,
                BossBar.Color.GREEN,
                BossBar.Overlay.PROGRESS);
        reviver.showBossBar(bar);

        ReviveSession session = new ReviveSession(reviver, downedPlayer.getUniqueId(), downedPlayer.getName(), bar, totalSteps);
        sessionsByReviver.put(reviver.getUniqueId(), session);
        sessionsByTarget.put(downedPlayer.getUniqueId(), session);
        session.task = plugin.getServer().getScheduler().runTaskTimer(plugin, () -> tickSession(session), 0L, STEP_TICKS);
    }

    private void tickSession(ReviveSession session) {
        DownedState state = downedPlayers.get(session.targetId);
        if (state == null) {
            endSession(session); // target was revived another way (e.g. /hardcore revive)
            return;
        }

        Player reviver = session.reviver;
        Player target = plugin.getServer().getPlayer(session.targetId);
        Location head = state.location();
        double maxDistance = plugin.getConfig().getDouble("revive-cancel-distance", 6.0);
        boolean sounds = plugin.getConfig().getBoolean("revive-sounds", true);

        boolean valid = target != null
                && reviver.isOnline()
                && !reviver.isDead()
                && reviver.getGameMode() != GameMode.SPECTATOR
                && head.getWorld() != null
                && reviver.getWorld().equals(head.getWorld())
                && reviver.getLocation().distanceSquared(blockCenter(head)) <= maxDistance * maxDistance;

        if (!valid) {
            endSession(session);
            if (reviver.isOnline()) {
                reviver.sendMessage(getMessage("revive-cancelled", "<prefix><red>Revive cancelled. Stay close to the head until it finishes."));
                if (sounds) {
                    head.getWorld().playSound(blockCenter(head), Sound.BLOCK_NOTE_BLOCK_BASS, SoundCategory.RECORDS, 1f, 0.5f);
                }
            }
            return;
        }

        if (session.step >= session.totalSteps) {
            endSession(session);
            revive(target, reviver, state);
            if (sounds) {
                playFinale(head);
            }
            return;
        }

        float progress = Math.min(1f, (session.step + 1) / (float) session.totalSteps);
        session.bar.progress(progress);
        session.bar.name(barTitle(session.targetName, session.step, Math.round(progress * 100)));

        target.sendActionBar(getMessage("being-revived", "<yellow><reviver></yellow> <gray>is reviving you... <spinner>",
                Messages.text("reviver", reviver.getName()),
                Messages.text("spinner", SPINNER[session.step % SPINNER.length])));

        if (sounds) {
            playTick(head, session.step, session.totalSteps);
        }
        session.step++;
    }

    private void endSession(ReviveSession session) {
        if (session.task != null) {
            session.task.cancel();
        }
        session.reviver.hideBossBar(session.bar);
        sessionsByReviver.remove(session.reviver.getUniqueId(), session);
        sessionsByTarget.remove(session.targetId, session);
    }

    private void cancelAllRevives() {
        for (ReviveSession session : new ArrayList<>(sessionsByReviver.values())) {
            endSession(session);
        }
    }

    private Component barTitle(String targetName, int step, int percent) {
        return getMessage("reviving-bar", "<green><spinner></green> <white>Reviving <yellow><player></yellow><white>... <gray><percent>%",
                Messages.text("spinner", SPINNER[step % SPINNER.length]),
                Messages.text("player", targetName),
                Messages.text("percent", percent));
    }

    // A rising major scale (two octaves) played on note block harp + bell,
    // one note every other step, ending in a bright chord when the revive lands.
    private void playTick(Location head, int step, int totalSteps) {
        if (step % 2 != 0) {
            return;
        }
        int index = Math.min(14, (int) ((step / (double) totalSteps) * 15));
        int offset = SCALE[index % 7] + 12 * (index / 7);
        float pitch = (float) Math.min(2.0, 0.5 * Math.pow(2.0, offset / 12.0));

        World world = head.getWorld();
        Location at = blockCenter(head);
        world.playSound(at, Sound.BLOCK_NOTE_BLOCK_HARP, SoundCategory.RECORDS, 0.9f, pitch);
        world.playSound(at, Sound.BLOCK_NOTE_BLOCK_BELL, SoundCategory.RECORDS, 0.35f, pitch);
    }

    private void playFinale(Location head) {
        World world = head.getWorld();
        Location at = blockCenter(head);
        for (float pitch : new float[]{1.0f, 1.26f, 1.498f, 2.0f}) {
            world.playSound(at, Sound.BLOCK_NOTE_BLOCK_BELL, SoundCategory.RECORDS, 0.8f, pitch);
            world.playSound(at, Sound.BLOCK_NOTE_BLOCK_HARP, SoundCategory.RECORDS, 0.6f, pitch);
        }
    }

    private Location blockCenter(Location location) {
        return new Location(location.getWorld(), location.getBlockX() + 0.5, location.getBlockY() + 0.5, location.getBlockZ() + 0.5);
    }

    private void revive(Player downedPlayer, Player reviver, DownedState state) {
        downedPlayers.remove(downedPlayer.getUniqueId());
        removeHeadMarker(state);
        save();

        downedPlayer.setGameMode(GameMode.SURVIVAL);
        downedPlayer.teleport(state.location());
        downedPlayer.setHealth(downedPlayer.getAttribute(org.bukkit.attribute.Attribute.MAX_HEALTH).getValue());

        Component title = messages.get("revived-title", "<green><b>You've Been Revived!</b></green>");
        Component subtitle = messages.get("revived-subtitle", "<gray><reviver> brought you back",
                Messages.text("reviver", reviver.getName()));

        downedPlayer.showTitle(Title.title(
                title,
                subtitle,
                Title.Times.times(Duration.ofMillis(500), Duration.ofSeconds(3), Duration.ofSeconds(1))
        ));

        Component broadcastMessage = messages.get("revive-broadcast", "<prefix><reviver> revived <player>!",
                Messages.text("reviver", reviver.getName()),
                Messages.text("player", downedPlayer.getName()));
        plugin.getServer().broadcast(broadcastMessage);
    }

    /**
     * Admin/testing revive: brings a downed player back instantly without
     * the timed channel. Returns false if the target wasn't downed.
     */
    public boolean forceRevive(Player target) {
        DownedState state = downedPlayers.remove(target.getUniqueId());
        if (state == null) {
            return false;
        }
        ReviveSession running = sessionsByTarget.get(target.getUniqueId());
        if (running != null) {
            endSession(running);
        }
        removeHeadMarker(state);
        save();

        target.setGameMode(GameMode.SURVIVAL);
        if (state.location().getWorld() != null) {
            target.teleport(state.location());
        }
        target.setHealth(target.getAttribute(org.bukkit.attribute.Attribute.MAX_HEALTH).getValue());

        Component title = messages.get("revived-title", "<green><b>You've Been Revived!</b></green>");
        target.showTitle(Title.title(
                title,
                Component.empty(),
                Title.Times.times(Duration.ofMillis(500), Duration.ofSeconds(3), Duration.ofSeconds(1))
        ));
        return true;
    }

    private void removeHeadMarker(DownedState state) {
        Block block = state.location().getBlock();
        if (block.getType() == Material.PLAYER_HEAD) {
            block.setBlockData(state.originalBlockData(), false);
        }
    }

    /**
     * True if the given location is currently a downed player's head marker
     * (used to stop the block from being mined away instead of clicked).
     */
    public boolean isHeadMarker(Location location) {
        for (DownedState state : downedPlayers.values()) {
            if (isSameBlock(state.location(), location)) {
                return true;
            }
        }
        return false;
    }

    private boolean isSameBlock(Location a, Location b) {
        return a.getWorld() != null
                && a.getWorld().equals(b.getWorld())
                && a.getBlockX() == b.getBlockX()
                && a.getBlockY() == b.getBlockY()
                && a.getBlockZ() == b.getBlockZ();
    }

    // ---------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------

    /**
     * Reads a MiniMessage string from config.yml (under "messages.") and
     * returns it as a formatted Component, falling back to the given
     * default if missing. Supports the <prefix>/<theme> shortcuts plus
     * whatever placeholder resolvers are passed in (e.g. Messages.text("player", name)).
     */
    public Component getMessage(String key, String fallback, TagResolver... resolvers) {
        return messages.get(key, fallback, resolvers);
    }
}
