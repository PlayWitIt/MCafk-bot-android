# AFK Bot

A tiny Android app that keeps your Minecraft: Java Edition account online on a server
(default: `constantiam.net`) and jumps every few seconds so you aren't kicked for idling.
No graphics or controls: it signs in with Microsoft, joins, and shows status plus a log.

## Download

Get **AFKBot.apk** from the [latest release](../../releases/tag/latest) and open it on your phone.

## Features

- Microsoft sign-in with a short code at microsoft.com/link (sign in once; it remembers you)
- Supports Minecraft 1.12.2 and 1.20.2 - 26.1 (auto-detects the server version)
- Jumps on a timer (default 30 s), looks around a little, swings its arm
- Auto-respawns, auto-reconnects, accepts server resource packs without downloading them
- Runs as a foreground service with a notification so it keeps going with the screen off
- Offline/cracked server mode with a chosen username

## Build

GitHub Actions builds it on every push (`.github/workflows/build.yml`).
Locally: `gradle :app:assembleRelease` with the Android SDK installed.

`tools/gen_proto.py` regenerates `Proto.java` (packet IDs per version) from the
[minecraft-data](https://github.com/PrismarineJS/minecraft-data) npm package.
