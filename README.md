# AFK Bot

A tiny Android app that keeps your Minecraft: Java Edition account online on a server
(default: `constantiam.net`) and jumps every few seconds so you aren't kicked for idling.
No graphics or controls: it signs in with Microsoft, joins, and shows status plus a log.

## Download

Get **AFKBot.apk** from the [latest release](../../releases/tag/latest) and open it on your phone.

## Features

- **Home**: live status, health, nearest player, uptime, position, anti-AFK counter, mini radar, recent activity
- **Map**: a terrain minimap (top block of every column, height-shaded like a vanilla map) with players (with names), hostile mobs, animals and items on top; pinch to zoom,
  filters, a "Nearby" list, and a render distance slider. Only keeps one pixel per block column and redraws at 2 fps while visible.
- **Modules** (inspired by utility clients like Meteor):
  - Anti-AFK: jump / spin / swing / sneak, optional random timing
  - Auto Log: leave on low health or when a non-friend player gets close; optional auto-rejoin
  - Visual Range: alert when a player comes into view
  - Auto Respawn, Auto Reconnect, Friends list
- **Map**: drag to pan, pinch/+/- to zoom, double-tap or ⌖ to re-centre; Surface or **Layer (Y level)** view to see caves and bases
- **3D view** tab (off by default): real Minecraft block textures (downloaded from Mojang on first use, ~1.2 MB via partial downloads; nothing from Mojang is bundled), left click (mine / hit) and right click (use / place / interact), hotbar; first-person or orbit camera, shaded blocks with ambient occlusion, players with name tags;
  **Drive** mode with joystick, jump, sneak and sprint (vanilla-style physics and collisions); tap a block to select it and
  **open chests/barrels/shulkers**, see their contents and take items; inventory viewer. Uses the GPU only while open.
- **Play (experimental)**: the full game view, built into the app and driven over the bot's own connection: landscape and fullscreen, the game's real block models and textures (downloaded from Mojang on first use, about 3 MB), smooth lighting with day and night, and the game's HUD (hotbar with item icons, hearts, hunger, XP, chat). Touch controls: a floating stick to move (push all the way to sprint), drag to look, tap to use or place, hold to break, plus jump/sneak, inventory and container screens and a pause menu with render distance, FOV and sensitivity. The light 3D tab stays as the low-power quick view.
- **Mods**: JavaScript add-ons (sandboxed) installed from examples, a file, pasted code or a link — see [MODS.md](MODS.md)
- **Chat**: read server and player chat; send messages and /commands
- **Naughty or Nice add-on** (switch it on in Modules → Add-ons; it adds a **NoN** tab): player intel while you're AFK, like the PC mod.
  **People**: everyone who joins, leaves, comes near or talks, with your lists (★ Friend, ✔ Nice, ? Look Into, ✖ Naughty), groups (split by group, foldable),
  notes, where and when they were last seen near you, time near you, and what they said. **DMs**: every whisper is a conversation; type and send, it goes
  as `/w name …` for you (the command can be changed), with sending / not-sent marks. **Intel**: coordinates posted in chat, your name mentioned, Naughty and
  Look Into players near you. **Log**: joins, leaves and sightings. Phone alerts for Naughty players joining or coming near, whispers and your name.
  **Import** your lists, groups and notes from the PC mod with a share link (`NN2:…`) or code (`NN1:…`). Kept per server, on the phone only.
  On servers that enforce secure chat it won't send whispers (an unsigned `/w` would get you kicked there); reading still works.
- **Alerts**: phone notifications for kicks, disconnects, deaths, players nearby and safety logouts
- **Several bots at once**: up to 4 bots, each with its own Microsoft (or offline) account and settings, all able to run at the same time,
  each with its own notification. The bar under the title shows every bot with a live status dot; tap one to see its Home, Map, 3D,
  Chat and Play screens. "+ Add bot" adds one (it opens on its Account tab); remove an added bot from its Account tab.
  The same account can't run in two bots at once (the server would kick one).
- Microsoft sign-in with a short code at microsoft.com/link (remembers you)
- Supports Minecraft 1.12.2 and 1.20.2 - 26.1 (auto-detects the server version)
- Runs as a foreground service so it keeps going with the screen off

## Build

GitHub Actions builds it on every push (`.github/workflows/build.yml`).
Locally: `gradle :app:assembleRelease` with the Android SDK installed.

`tools/gen_proto.py` regenerates `Proto.java` (packet IDs per version) from the
[minecraft-data](https://github.com/PrismarineJS/minecraft-data) npm package.

## Credits & licenses

- [Mozilla Rhino](https://github.com/mozilla/rhino): JavaScript engine for script mods, used unmodified ([MPL 2.0](app/src/main/assets/licenses/rhino-MPL-2.0.txt))
- [minecraft-data](https://github.com/PrismarineJS/minecraft-data) by PrismarineJS: protocol, block and item data that `Proto.java` and `assets/proto` are generated from ([MIT](app/src/main/assets/licenses/minecraft-data-MIT.txt))
- Block textures and mob models are downloaded from Mojang on the phone at runtime; none are included in this repository or the app

Minecraft is a trademark of Mojang Synergies AB. This project is not affiliated with or endorsed by Mojang or Microsoft.
