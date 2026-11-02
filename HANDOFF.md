# HANDOFF — branch `feature/boot-screen`

Notes for whoever continues this branch. Delete this file before merging to `main`.

## What was done

A boot screen in Omarchy style, on **Android TV, LG webOS and Samsung Tizen**, with the same script and
timing everywhere (~7 s, any key skips, no key reaches Home while it plays):

1. **CRT power-on**: a bright line grows across the middle, then opens vertically into the screen (520 ms).
2. **BIOS POST**: `OmarchyBIOS v<real version>`, `Display Mode` (Android: physical mode + Hz; web: UI resolution), TV model (the real one: Build.MODEL on Android, the model name when webOS/Tizen expose it, else a generic name), the TV's real RAM counting up in 64 MB steps,
   "Detecting ads ... none", and the **OTV block badge top right** (as the old Energy Star logos).
3. Short **flicker**, then **systemd** as Arch prints it: `Welcome to Omarchy TV!` and nine lines. The
   **network** and **omarchy-link** lines show the start-job spinner `[ *** ] A start job is running for …` and
   **really wait for the TV** (`BootScript.Live`, script clock held by `clock()`), up to 2.5 s each, then show
   `[  OK  ] Connected to Wi-Fi "<ssid>", <ip>.` / `Connected via Ethernet, <ip>.` and
   `omarchy-link: connected to <PC name>.` (or `no PC paired yet`), or `[ WARN ] …` in yellow on timeout.
   `Indexed N apps.` is the real count. `Masked ads.service` (spinner, fixed) and `Reached target Couch` are jokes.
4. `omarchytv login: user (automatic login)`, the prompt types **`omarchy-tv`** (blinking cursor), and the
   **OMARCHY TV block wordmark** draws row by row, as on Home ("OMARCHY" accent, "TV" accent2),
   with `theme <name> · v<version>` under it.
5. Hold, fade to Home.

Colours from the active Omarchy theme, Caskaydia font. With `bootFx` (default on): scanlines and a light glow.
Block letters are drawn as solid rectangles/runs, not U+2588 glyphs, so there are no seams between cells.
Nothing keeps running after it ends.

## Files

| File | What |
|---|---|
| `launcher/app/src/main/java/com/omarchytv/home/BootScript.java` | New. Pure Java script (lines, timings, spinner, memory count, wordmark rows from BlockFont) |
| `launcher/app/src/main/java/com/omarchytv/home/BootScreenView.java` | New. Canvas View overlay: CRT, scanlines (1×3 BitmapShader), glow (shadow layer), MainActivity hooks, "boot"/"wake" |
| `launcher/app/src/main/java/com/omarchytv/home/MainActivity.java` | 4 one-line hooks: onCreate, onResume, onDestroy, top of dispatchKeyEvent |
| `launcher/app/src/main/java/com/omarchytv/home/LauncherConfig.java` | `bootScreen`, `bootFx` fields + `bootScreenMode()` parser |
| `launcher/app/src/test/java/com/omarchytv/home/BootScriptTest.java` | New. 16 JUnit tests (incl. live wait / timeout / failing probe) |
| `webos/app/boot.js` | New. Same script in ES5 + DOM player + `install()`; Tizen packs it automatically (build-wgt.py takes all of webos/app) |
| `webos/app/index.html` | `<script src="boot.js">` before app.js |
| `webos/app/app.js` | `bootScreen: "boot"`, `bootFx: true` in DEFAULT_CFG + `OmarchyBoot.install(...)` right after `applyTheme()` |
| `webos/tests/boot.test.js` | New. 16 node tests: same lines and timings as BootScript.java, same glyphs as BlockFont.java |
| `CHANGELOG.md` | Entry under [Unreleased] |

## Settings

- `bootScreen`: `"boot"` (default), `"wake"`, `"off"`.
  - Android: omarchytv.json. "boot" = once per TV boot (same boot-id trick as HomeCheck). "wake" = also on every
    SCREEN_ON (receiver registered only while MainActivity lives). Most Android TVs go to standby instead of
    rebooting, so **for a "turn on the TV" video use "wake"**.
  - webOS/Tizen: `cfg.bootScreen` (localStorage). "boot" = every app start; "wake" = also when the app becomes
    visible again after ≥ 60 s hidden.
- `bootFx`: scanlines + glow, default `true`. Turn off if a slow TV stutters.

## Verified

- `node --test webos/tests/*.test.js` → 104 pass (16 new). `node --test tizen/*.test.cjs` → 29 pass.
- `python3 tizen/verify-build.py` → PASS; the built .wgt contains boot.js.
- BootScriptTest (16 tests) compiled and passed with javac + JUnit 4.13.2 (stub for `LauncherConfig.bootScreenMode`).
- **BootScreenView.java compiles** against the Android 7.0 (API 24, = minSdk) framework jar, with stubs for
  Theme / CastUi / LauncherConfig / OmarchyLink / LinkStore.Pc.
- Web version rendered and recorded in headless Chromium at 1920×1080 and 1280×720: CRT, BIOS badge, spinner,
  wordmark all look right; the overlay removes itself and calls `done()`.

## NOT verified (to do)

- **Android live probes on a real TV:** the Wi-Fi name needs the location permission on Android 8.1+ (the launcher
  already asks for it for the Wi-Fi pill); without it the line says just "Wi-Fi" + IP. Check that
  `OmarchyLink.running()` is true by the time the omarchy-link spinner runs, else it waits 2.5 s and warns.
- **Privacy when filming:** the boot screen shows the Wi-Fi name and the TV's local IP.

- **Full Gradle build not run** (no Android SDK in the sandbox): run `./gradlew :app:testDebugUnitTest assembleRelease`.
- **Not tested on a real TV.** On the Sony check: shows after a reboot (or on wake with "wake"), any key skips and
  does not trigger Home, the wallpaper intro still plays after it, D-pad focus normal afterwards, smooth on 1 GB
  RAM with `bootFx` on. On LG/Samsung check the CRT transform and scanline gradient on old engines.
- On Android < 9 the glow around block letters is not drawn (shadow layers on rectangles need API 28 with
  hardware acceleration); text glow works everywhere.
- No menu entry yet for the settings (Android menu and webOS/Tizen System menu): needs strings in every language.
- Version numbers NOT bumped (versionCode, versionName, APP_VERSION, appinfo.json, tizen/config.xml).

## Do not touch

Signing, release, tags: unchanged and not part of this branch.
