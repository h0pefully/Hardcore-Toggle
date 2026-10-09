package com.hardcoretoggle;

import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Turns config.yml's "messages" section (MiniMessage strings) into components.
 * Supports two shortcuts, matching the convention used by the other SMP
 * plugins: {@code <prefix>} and {@code <theme>...</theme>}.
 */
public final class Messages {

    private static final MiniMessage MINI = MiniMessage.miniMessage();

    private final JavaPlugin plugin;
    private String themeOpen = "";
    private String themeClose = "";
    private TagResolver prefixResolver = TagResolver.empty();

    public Messages(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    /** Re-reads theme/prefix from config.yml. Call on enable and on /hardcore reload. */
    public void load() {
        themeOpen = plugin.getConfig().getString("theme.open", "");
        themeClose = plugin.getConfig().getString("theme.close", "");
        prefixResolver = Placeholder.parsed("prefix", theme(plugin.getConfig().getString("prefix", "")));
    }

    /** Replaces {@code <theme>} / {@code </theme>} with the configured gradient. */
    public String theme(String raw) {
        if (raw == null || raw.indexOf("theme>") < 0) {
            return raw;
        }
        return raw.replace("<theme>", themeOpen).replace("</theme>", themeClose);
    }

    /** Parses a MiniMessage string (with prefix + theme support) from config.yml, with a fallback. */
    public Component get(String key, String fallback, TagResolver... resolvers) {
        String raw = plugin.getConfig().getString("messages." + key, fallback);
        return MINI.deserialize(theme(raw), TagResolver.resolver(prefixResolver, TagResolver.resolver(resolvers)));
    }

    /** Sends a message by key. Skips silently if the configured text is empty. */
    public void send(Audience audience, String key, String fallback, TagResolver... resolvers) {
        String raw = plugin.getConfig().getString("messages." + key, fallback);
        if (raw.isEmpty()) {
            return;
        }
        audience.sendMessage(get(key, fallback, resolvers));
    }

    /** A placeholder inserted as plain text (player names, numbers, etc. — never re-parsed as MiniMessage). */
    public static TagResolver text(String key, Object value) {
        return Placeholder.unparsed(key, String.valueOf(value));
    }
}
