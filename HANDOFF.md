# Handoff: AFK Bot (Android) — continue on a Linux PC

Read this first if you're picking this project up.

## Status (2026-11-20, v7.7)

Constantiam runs Folia 1.21.11 with a strict, prediction-based anticheat. It pings the client every tick and replays
every move against vanilla physics, so anything not bit-exact gets set back.

1. **Walking: fixed in v7.4** and verified live: walk, sprint, jump, step-ups and wall contact all had 0 corrections,
   and the position is kept after a rejoin. Causes, all in `Bot.java`:
   - The player box must be vanilla's float sizes: half width `0.6F/2` (0.30000001...) and height `1.8F`. With 0.3,
     touching a wall leaves you 1.2e-8 inside it, which counts as moving into a block.
   - Collision maths must be in double. `int + float` is float maths, which only has 0.25-block steps past
     x/z = 2 million, and Constantiam bases are at about -2.7M.
   - Physics runs every tick, also while idle, so onGround and velocity are right when you start walking.
     Nothing is simulated before your chunk has loaded.
   - Step-up works like vanilla: it uses onGround from before the move, tries candidate heights, and moves Y first,
     then the larger horizontal axis last.
   - Teleports apply the 1.21.2+ velocity and relative flags. Each tick sends keys, then sprint/sneak, then the move.
2. **Copper chest / shulker opening: not re-tested on Constantiam.** It was probably the same root cause: the server
   never accepted our movement, so it still thought the player was at the join spot, too far away to open anything.
   That would also explain why a nearby axe scrape worked. It works on the local vanilla server (`copper`, `shulker`).
   **The user's base is sensitive: on Constantiam only walk, jump and sprint unless the user explicitly allows more.**
3. **Play mode (v7.8, experimental):** built entirely in this app (the user wants no third-party launcher; the
   Amethyst-based add-on from v7.6/v7.7 was removed). It's separate from the light 3D tab, which must stay as is.
   - `GameModels`/`GameAtlas`/`GameMesher`/`GameLight` are pure Java and run in `desktop/run.sh preview`, which
     renders real server data to PNGs with a software rasterizer that matches the shader. Use it to check visual
     changes before building.
   - `GameRenderer` (GLES 2.0), `GameHud` (HUD and touch controls), `GameActivity` and `GameAssetStore`
     (downloads about 3 MB once) are the Android side.
   - Light data and the extra packets only exist while the Play screen is open (`MapData.keepLight`, `GameState.open`).
   - It has not been seen on a real phone yet.

Commits are authored as "PlayWit Creations". Don't add tool attribution or co-author lines anywhere.

On the PC you can run the **app's real bot code** against Constantiam with the user's account. That's what
`desktop/` is for. The user must stop the AFK app on their phone first: one account can only be online once.

## Quick start on the PC

Needs Java 17+, curl and python3 (Debian/Ubuntu: `sudo apt install openjdk-17-jdk curl python3`).

```bash
git clone https://github.com/PlayWitIt/MCafk-bot-android.git && cd MCafk-bot-android
desktop/run.sh live                      # connects to constantiam.net with the real bot code
```

- The first run prints `Sign-in code: XXXXXXX`. **The user** enters it at https://www.microsoft.com/link.
  Tokens are saved in `desktop/state/` (git-ignored). **Never commit or print that folder.**
- Then type commands (they're also readable from a file: `desktop/run.sh live < steps.txt`):
  `where`, `walk`, `go <yaw> <ms> [jump]`, `hold <keys> <ms> [yaw]` (keys: f b l r j s n), `containers`,
  `open <x> <y> <z>`, `take <slot>`, `close`, `trace on` (prints every packet, each move with position and flags,
  and each server teleport), `say <text>`, `wait <ms>`, `quit`. See `desktop/src/app/afkbot/LiveTest.java`.
- Every `Server corrected your position ...` log line means the server rejected our movement and says which
  blocks were around. `trace on` prints every packet we send.
- Don't spam the real server. Constantiam rate-limits joins and Mojang returns 429 on fast reconnects (the bot
  waits 2 minutes when that happens).

Local vanilla server for regression tests (offline mode, port 25601):

```bash
desktop/realserver.sh                                    # downloads Mojang's 1.21.11 server.jar on first run
desktop/run.sh real walk        # also: far, course, physics, diag, copper, shulker (see RealTest.java)
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
