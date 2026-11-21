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
- **Mods**: JavaScript add-ons (sandboxed) installed from examples, a file, pasted code or a link — see [MODS.md](MODS.md)
- **Chat**: read server and player chat; send messages and /commands
- **Alerts**: phone notifications for kicks, disconnects, deaths, players nearby and safety logouts
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
