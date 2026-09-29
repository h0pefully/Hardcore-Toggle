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


