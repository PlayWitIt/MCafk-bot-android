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

Amethyst is LGPL-3.0, and its components carry their own licenses (OpenJDK GPLv2+CE, LWJGL BSD, GL4ES/Mesa MIT, OpenAL-Soft GPLv2, ...; see Amethyst's README). Our changes to it are exactly `amethyst.patch`, published here under the same LGPL-3.0, together with the upstream commit they apply to. Minecraft itself is downloaded from Mojang on the phone and needs an account that owns the game. Nothing from Mojang is bundled.
