# Handoff: AFK Bot (Android) — continue on a Linux PC

Read this first if you're picking this project up.

## Why the PC

The previous work happened in a cloud container that **cannot connect to constantiam.net** (only HTTPS goes out).
Everything was tested against a local *vanilla* 1.21.11 server, where walking, sprinting, jumping, opening
chests/copper chests/shulker boxes and taking items all work with 0 server corrections. On **Constantiam**
(Folia 1.21.11 + its own anticheat) the user still reports:

1. **Can't walk** (again, in v7.3; v7.2 walked fine for a while).
2. **Can't open a copper chest**, even with an empty hand. Scraping it with an axe worked (so "use item on block"
   packets do reach the server).
3. An earlier "Connection reset" right after trying to open a shulker box. v7.3 changed the click point.
4. After reconnecting, the player was back exactly where they first joined. The server may be silently not
   accepting movement, or it just didn't save on a reset.

On the PC you can run the **app's real bot code** against Constantiam with the user's account and iterate until
it works. That's the whole point of `desktop/`.

## Quick start on the PC

Needs Java 17+, curl and python3 (Debian/Ubuntu: `sudo apt install openjdk-17-jdk curl python3`).

```bash
git clone https://github.com/PlayWitIt/MCafk-bot-android.git && cd MCafk-bot-android
desktop/run.sh live                      # connects to constantiam.net with the real bot code
```

- The first run prints `Sign-in code: XXXXXXX`. **The user** enters it at https://www.microsoft.com/link.
  Tokens are saved in `desktop/state/` (git-ignored). **Never commit or print that folder.**
- Then type commands (they're also readable from a file: `desktop/run.sh live < steps.txt`):
  `where`, `walk`, `go <yaw> <ms> [jump]`, `containers`, `open <x> <y> <z>`, `take <slot>`, `close`,
  `trace on`, `say <text>`, `wait <ms>`, `quit`. See `desktop/src/app/afkbot/LiveTest.java`.
- Every `Server corrected your position ...` log line means the server rejected our movement and says which
  blocks were around. `trace on` prints every packet we send.
- Don't spam the real server. Constantiam rate-limits joins and Mojang returns 429 on fast reconnects (the bot
  waits 2 minutes when that happens).

Local vanilla server for regression tests (offline mode, port 25601):

```bash
desktop/realserver.sh                                    # downloads Mojang's 1.21.11 server.jar on first run
desktop/run.sh real walk        # also: course, physics, diag, copper, shulker (see RealTest.java)
desktop/run.sh live 127.0.0.1:25601 --offline Tester     # interactive, against the local server
```

`RealTest` places blocks through the server console FIFO `desktop/realsrv/cmd`, e.g.
`echo "setblock 10 -60 10 minecraft:copper_chest" > desktop/realsrv/cmd`.

## Suggested plan

1. `desktop/run.sh live`, then `where` and `walk`. If the server corrects you, the log says from/to and the blocks
   around. Compare our position, onGround and the y we send with what vanilla would do at that spot.
2. Walk to the user's copper chest and run `open x y z` with `trace on`. Does the server answer with an
   open_window? With nothing? With a block update? Try from different sides and distances.
3. If Constantiam behaves unlike vanilla, look at what a vanilla client sends that we don't (ordering of
   move/look vs use_item_on, `player_loaded`, `client_tick_end`, swing timing, sequence acks, sneaking state,
   held-item sync). Don't try to evade an anticheat. Make the client behave exactly like a vanilla client.
4. After each fix: run the vanilla regression scripts, then bump `versionCode`/`versionName` in
   `app/build.gradle` and push to `main`. GitHub Actions builds the APK and replaces the `latest` release:
   https://github.com/PlayWitIt/MCafk-bot-android/releases/download/latest/AFKBot.apk

## Code map (app/src/main/java/app/afkbot/)

| File | What it does |
|---|---|
| `Bot.java` | The Minecraft client: connect, login/config/play, keepalive, chunks, entities, **movement physics** (`physicsStep`, `move`, `sweep`), block use/mining, containers, `testWalk`, reconnects, "last things sent" log. |
| `Shapes.java` | Block shapes. **Collision uses Minecraft's exact per-state shapes** (minecraft-data `blockCollisionShapes`, since v7.2), falling back to `compute()`. Outlines (drawing/tapping) still come from `compute()`. |
| `ProtoDef.java` | Interprets minecraft-data protocol definitions (item slots etc.) and loads `assets/proto/<protocol>.json`. |
| `Proto.java` | **Generated** packet ids/tables. Don't edit; run `python3 tools/gen_proto.py app/src/main/java/app/afkbot/Proto.java` from inside the minecraft-data npm package dir (`npm pack minecraft-data`). |
| `MapData.java` | Chunk storage and parsing, `stateAt`, colours for the map. |
| `Inv.java` | Inventory/container state and item parsing. |
| `Auth.java` | Microsoft device-code login, then Xbox, XSTS and Minecraft tokens, plus the Mojang session join. |
| `World3DView.java`, `TexturePack.java`, `EntityModels.java`, `Geo.java` | 3D view (GLES2) and real textures/models downloaded on the phone. |
| `MainActivity.java` | All the UI (tabs Home/Map/3D/Modules/Chat/Account). |
| `ModEngine.java`, `ModStore.java` | Sandboxed JavaScript mods (Rhino). |
| `AfkService.java`, `Hub.java` | Foreground service, shared state and log. |

Facts worth knowing:
- Protocol 774 = 1.21.11. Constantiam's status reply gives protocol -1, so the version is parsed from its name.
- Movement on 768+: we send `player_input` flags and `client_tick_end` every tick, like vanilla. Physics constants
  match vanilla: 0.6 default slipperiness, 0.98 input scale, 0.91 drag. Corrections are counted in `Bot.corrections`.
- `assets/` must not contain `.gz` files (the Android build un-gzips them silently).
- The APK is signed with `app/afkbot-release.p12` so updates install over each other. Keep it.

## Building the APK locally (optional)

CI builds it on every push to `main`. To build locally you need the Android SDK (platform 35, build-tools) and
Gradle 8.11.1: `gradle assembleRelease`. The output is `app/build/outputs/apk/release/`.
