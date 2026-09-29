# HardcoreToggle

A Paper plugin (targeting Paper/Minecraft **26.2**, Java 25) that lets each
player opt into a personal "Hardcore mode" instead of forcing it server-wide.

## What it does

- **`/hardcore toggle`** — turns Hardcore mode on or off for yourself.
  - Everyone starts in normal Survival. Dying as a normal Survival player
    does nothing special — vanilla behavior, no plugin interference.
  - Permission: `hardcoretoggle.use` (defaults to **true**, i.e. everyone).
- **Broadcast** — when a player enables Hardcore mode, the whole server sees
  `"<Player> has enabled Hardcore mode!"` in chat. (Configurable, can be
  turned off.)
- **Tab list marker** — players currently in Hardcore mode get a red skull
  (`☠`) prefix before their name in the tab list, so everyone can see who's
  playing on hard mode. This is a text/color marker, not a texture-pack
  icon — true tab-list icons require a resource pack and weren't in scope
  here, but this is the standard way plugins signal status in the tab list.
- **PlaceholderAPI support (optional)** — if PlaceholderAPI is installed,
  HardcoreToggle registers `%hardcoretoggle_icon%` (the configured
  `nametag-placeholder-icon`, or empty if the player isn't in Hardcore
  mode), `%hardcoretoggle_active%` (`true`/`false`), and
  `%hardcoretoggle_downed%` (`true`/`false`). Drop `%hardcoretoggle_icon%`
  into another plugin's nametag/tab format — e.g. alongside
  `%luckperms_prefix%` in a nametag plugin like TAB — to show the skull
  above heads and in the tab list next to rank prefixes. If PlaceholderAPI
  isn't installed, HardcoreToggle just skips registering it; nothing else
  is affected.
- **Death title & chat message** — when a Hardcore player dies, they see a
  big **"You've Died"** title on screen, everyone sees a chat message
  announcing it, and they're put into Spectator mode, frozen exactly where
  they fell (they can still look around, just can't fly off) instead of
  respawning normally.
- **Head-click revive** — dying places a block with the player's own head
  and skin at their death spot. Any other online player who right-clicks
  that head starts a timed revive: a green loading bar with a spinner fills
  over 3 seconds (`revive-duration-seconds`) while a rising note block
  melody plays, and the downed player sees "X is reviving you..." on their
  action bar. When it completes they're set back to Survival, teleported
  there, healed to full, and a title plus a server-wide chat message fire.
  Walking farther than `revive-cancel-distance` blocks away (or dying/
  logging out) cancels it. The head can't be mined away — only a successful
  revive removes it (and restores whatever block was there before).
- Downed state is saved to `data.yml` (asynchronously, so it never stalls
  the server), so a downed player stays revivable across logouts *and*
  server restarts, and their head marker stays protected.
- **`/hardcore revive [player]`** — an admin/testing command (permission
  `hardcoretoggle.admin`, defaults to ops) that revives yourself or someone
  else instantly, without needing another player to click your head. Handy
  for solo testing.
- **`/hardcore reload`** — reloads `config.yml` without restarting the
  server (permission `hardcoretoggle.reload`, defaults to ops).

All broadcasts, titles, and the tab prefix are editable in `config.yml`
after the first run.

## Project layout

```
hardcore-toggle/
├── build.gradle.kts
├── settings.gradle.kts
├── src/main/java/com/hardcoretoggle/
│   ├── HardcorePlugin.java     # onEnable/onDisable, wiring
│   ├── HardcoreManager.java    # state, persistence, tab list, revive logic
│   ├── HardcoreCommand.java    # /hardcore toggle
│   ├── HardcorePlaceholders.java # optional PlaceholderAPI expansion
│   └── PlayerListener.java     # death/respawn/join events
└── src/main/resources/
    ├── plugin.yml
    └── config.yml
```

## Building it (no local install required)

The project includes a GitHub Actions workflow that builds the jar for you
in the cloud — you don't need Java, Gradle, or anything installed locally:

1. Create a new (can be private) repository on GitHub.
2. Upload the contents of this `hardcore-toggle` folder to it — either drag
   and drop the folder onto the GitHub web UI's "Add file → Upload files"
   screen, or use `git push` if you're comfortable with git.
3. Go to the repo's **Actions** tab. A "Build plugin jar" run should start
   automatically (or click **Run workflow** if it didn't).
4. Once it finishes (green check), open the run and download the
   **HardcoreToggle-jar** artifact from the bottom of the page — it's a zip
   containing the built `.jar`.

## Building it locally instead

You'll need **JDK 25** installed locally (Paper 26.2 requires it) and an
internet connection (to pull `paper-api` from PaperMC's Maven repo).

```bash
cd hardcore-toggle
gradle build
```

(Or open the folder in IntelliJ IDEA Community Edition — it detects the
Gradle project automatically, offers to download a matching JDK, and you
can build via the Gradle panel or `Build > Build Project`.)

The compiled jar will show up at `build/libs/HardcoreToggle-1.0.0.jar`.

## Installing it

1. Drop the jar into your server's `plugins/` folder.
2. Restart (or `/reload confirm`, though a full restart is safer).
3. A `config.yml` will be generated under `plugins/HardcoreToggle/` — edit
   it to taste and restart again, or use a plugin like PlaceholderAPI's
   `/reload` equivalent if you add one.

## Notes / things you may want to tweak

- **Revive timing** is configurable in `config.yml`: duration, cancel
  distance, and whether the note block melody plays. A duration of `0` makes
  revives instant again. `/hardcore revive` (admin) always revives instantly.
- Right now dying in Hardcore mode doesn't change how items/XP drop — that
  still follows your server's normal Survival death rules (keepInventory
  gamerule, etc.), since you didn't ask for anything different there. If
  you'd like Hardcore deaths to *not* drop items until revived, or to add
  a revive cooldown/time limit, that's a small addition to
  `PlayerListener`/`HardcoreManager`.
- There's no `/hardcore status` command yet (e.g. to list who's currently
  downed) — easy to add if useful.
