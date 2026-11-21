# AFK Bot Play

The real Minecraft: Java Edition on the phone, opened from AFK Bot's **Play** button.

It's a second app (`app.afkbot.play`) built from [Amethyst](https://github.com/AngelAuraMC/Amethyst-Android), the maintained successor of PojavLauncher, which runs Mojang's own game on Android with a mobile Java runtime, LWJGL and OpenGL ES renderers. We don't change how the game runs. `amethyst.patch` only adds the hand-off with AFK Bot:

1. AFK Bot sends `HandoffActivity` your current Minecraft login (name, UUID, a fresh access token), the server address and the server's Minecraft version. The bot leaves the server first, since an account can only be online once.
2. The launcher installs that login as the current account and an "AFK Bot server" profile on that version, then presses Play itself after its normal start-up.
3. The game gets `--quickPlayMultiplayer <server>`, so it joins the server straight away (only for that launch).
4. When the game closes, it reopens AFK Bot (`app.afkbot/.ResumeAfk`), and the bot rejoins if it was running before.

Both directions are protected by `app.afkbot.permission.HANDOFF` with `protectionLevel="signature"`. Only apps signed with the AFK Bot key (`app/afkbot-release.p12`) can send or receive the login. The token is stored only for the moment of the hand-off and in the launcher's account file, which Amethyst needs to start the game.

## Building

CI does it (`.github/workflows/play.yml`) and publishes `AFKBotPlay.apk` to the `play-latest` release:

1. Clone Amethyst at the commit in `AMETHYST_COMMIT`, with submodules.
2. `git apply play/amethyst.patch` and copy the signing key in.
3. Download Amethyst's prebuilt JRE 8 artifact. Java 17/21 are downloaded by the app on first use.
4. `gradle :app_pojavlauncher:assembleRelease` (Gradle 9.6.1, JDK 21, NDK from the Android Gradle plugin).

To update Amethyst, change `AMETHYST_COMMIT`, check that `git apply --check` still passes, and fix the patch if it doesn't.

## License

Amethyst is LGPL-3.0 ([LICENSE.LGPL-3.0](LICENSE.LGPL-3.0), which builds on the GPL-3.0 in [COPYING.GPL-3.0](COPYING.GPL-3.0)). Its components keep their own licenses: OpenJDK GPLv2+CE, LWJGL BSD, GL4ES/Mesa MIT, MobileGlues LGPL-2.1, OpenAL-Soft GPLv2 and more, listed in Amethyst's README and shipped in the app's `assets/licenses`.

AFK Bot Play is a modified version of Amethyst. Our changes are exactly `amethyst.patch`, applied to the upstream commit in `AMETHYST_COMMIT`, and are released under the same LGPL-3.0. The patch:
- adds `assets/licenses/AFKBOT_PLAY_NOTICE.txt` to the app, saying what was changed, when, and where this source is
- ships Amethyst's LGPL-3.0 and the GPL-3.0 texts in the app
- marks every modified file with a "Modified for AFK Bot Play" line at the top

Credit for the launcher, the Android Java runtime and the rendering work goes to the [Amethyst](https://github.com/AngelAuraMC/Amethyst-Android), [PojavLauncher](https://github.com/PojavLauncherTeam/PojavLauncher) and [Boardwalk](https://github.com/zhuowei/Boardwalk) authors and the projects they build on.

Minecraft is a trademark of Mojang Synergies AB. The game is downloaded from Mojang on the phone and needs an account that owns it; nothing from Mojang is bundled. Not affiliated with or endorsed by Mojang, Microsoft or the Amethyst project.
