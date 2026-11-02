# HANDOFF — branch `feature/tv-pc-together`

Notes for whoever continues this branch. Features: **A. Boot screen**, **B. Twenty new languages**, **C. Theme sync both ways**,
**D. TV and PC power together + settings switches**, **E. Update from the TV**, **F. Liftoff** (each built on the previous).
Delete this file before merging to `main`.

# A. Boot screen

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


# B. Twenty new languages

French (`fr`), Spanish (`es`), Portuguese Portugal (`pt-PT`) and Brazil (`pt-BR`), Dutch (`nl`), Polish (`pl`),
Swedish (`sv`), Danish (`da`), Norwegian Bokmål (`nb`), Finnish (`fi`), Czech (`cs`), Hungarian (`hu`),
Romanian (`ro`), Greek (`el`), Turkish (`tr`), Ukrainian (`uk`), Russian (`ru`), Arabic (`ar`), Chinese Simplified
(`zh-CN`), Japanese (`ja`), on top of en/it/de: 23 in all. Machine-translated from English; product words kept as in
the Italian/German files (OmarchyTV, Omarchy Linux, Walker, Hyprland, Cinemarchy, Grok Bot, `> search`, `> ask`).
Arabic is RTL text only (no supportsRtl, layout not mirrored); any Chinese locale shows Simplified.

## Files

| File | What |
|---|---|
| `launcher/app/src/main/res/values-{fr,es,pt,pt-rBR,nl,pl,sv,da,nb,no,fi,cs,hu,ro,el,tr,uk,ru,ar,zh,ja}/strings.xml` | New. All 397 translatable strings each (`values-pt` = Portugal, `values-zh` = Simplified, `values-no` = copy of `values-nb` for TVs that report "no"); zh/ja/hu also override `date_format` |
| `launcher/app/src/main/res/values/strings.xml` | `lang_fr` … `lang_ja` native names (translatable="false") |
| `launcher/.../LauncherConfig.java` | `LANGUAGES`, `nextLanguage()`, `speechTag()`; `wrap()` uses `Locale.forLanguageTag` (needed for pt-BR) |
| `launcher/.../MainActivity.java` | Language menu cycles `LANGUAGES`; `langLabel()` maps to `R.string.lang_*` (explicit ids: release has shrinkResources); `speechLanguage()` → `speechTag()` |
| `launcher/app/src/test/.../TranslationsTest.java` | New. Every values-xx has every string, same placeholders, escaped apostrophes; menu names; speech tags |
| `webos/app/i18n.js` | New. Strings, weather (wmo), days, months, native name for the 20 languages |
| `webos/app/lang.js` | New. `resolve()` (saved → TV language → en; pt-BR vs pt-PT), `next()`, `speechTag()`, `askTag()`, `name()` |
| `webos/app/app.js` | Merges i18n.js into STRINGS; getLang/days/months/Language menu use them; zh/ja bar date "11月2日 周一"; speech and Grok Bot lang now follow `getLang()` (before: English unless picked) |
| `webos/app/index.html` | `lang.js`, `i18n.js` before app.js |
| `webos/tests/i18n.test.js` | New. Same keys as English, same language list as Android, resolve/cycle/tags |
| `CONTRIBUTING.md` | New section "5. Fix a translation" |

## Verified

- `node --test webos/tests/*.test.js` and `tizen/*.test.cjs` pass; `tizen/verify-build.py` PASS (boot + translations together).
- TranslationsTest (4 tests) passes against the real res/ files (javac + JUnit, stub for the LauncherConfig helpers).
- LG/Samsung app rendered in headless Chromium in fr, ru, el, pt-BR, zh-CN, ja, ar, tr: no script errors ("11月2日 周一", "пн 2 ноя").

## NOT verified (to do)

- Gradle build (`./gradlew :app:testDebugUnitTest assembleRelease`): resource compile (aapt2) of the new files.
- On the TV: long strings (ru, el, pl, nl, hu, fi) in menus and hint bars; CJK and Arabic glyphs come from the TV's
  fallback fonts (Caskaydia has none); Arabic mixed with English words reads right-to-left inside a left-to-right
  layout, so a native speaker should look at it. Pick each language once in Launcher settings → Language.
- Speech recognition: the TV needs the Google voice pack for that language, else it falls back.


# C. Theme sync both ways (TV → PC)

Pick a theme with the remote and the Omarchy PC switches too. TV switch **"Also change the PC theme"**, default
**off**; PC side `themeFromTv`, default on (the TV switch is the opt-in). The PC → TV direction is unchanged: after
`omarchy-theme-set` the existing sync pushes the palette back, and since it is the same theme nothing loops.

## Files

| File | Change |
|---|---|
| `pc-plugin/omarchy-link/lib/omarchy_link/theme.py` | `installed_themes()`, `set_theme(name)`: name must match `[a-z0-9][a-z0-9._-]{0,63}` and be a folder in `~/.config/omarchy/themes`; same theme → "same"; one switch every 2 s → "busy"; `Popen(["omarchy-theme-set", name])`, no shell, detached |
| `pc-plugin/omarchy-link/lib/omarchy_link/config.py` | `DEFAULTS["themeFromTv"] = True` |
| `pc-plugin/omarchy-link/lib/omarchy_link/session.py` | Event `theme_set` → `handle_theme_set()` (honours `themeFromTv`, logs refusals) |
| `pc-plugin/omarchy-tv/lib/omarchy_tv/webos.py` | `POST /v1/theme/set` (LG/Samsung), behind the same `X-Omarchy-Token` code as Grok Bot; 200 ok/same, 404 unknown, 429 busy, 403 off, 503 no omarchy-link |
| `launcher/.../link/LinkServer.java`, `OmarchyLink.java` | `themeSet(name)` broadcasts `theme_set` to the paired PC |
| `launcher/.../LauncherConfig.java` | `themeToPc` (omarchytv.json, default false) |
| `launcher/.../MainActivity.java` | Toggle row in Themes; `themeToPc(name)` after a pick in the launcher-page cycle, stock themes and ★ custom themes |
| `launcher/.../res/values*/strings.xml` | `theme_to_pc` in all 23 languages |
| `webos/app/app.js`, `webos/app/i18n.js` | `cfg.themeToPc` (default false), third toggle row in the Themes modal, POST on pick, toast `theme_not_on_pc` on "unknown"; strings in 23 languages |
| `pc-plugin/omarchy-link/docs/PROTOCOL.md`, `README.md` | Event `theme_set`, bridge endpoint, `themeFromTv` |
| `pc-plugin/omarchy-link/tests/test_theme_set.py` (new, in `run.sh`), `pc-plugin/omarchy-tv/tests/test_webos.py` | Unknown/unsafe names never run anything, no shell, same/busy/failed, `themeFromTv` off, bridge auth and status codes |

## Verified

- omarchy-link `tests/run.sh`: 115 tests OK. omarchy-tv `test_webos`: OK (`test_mcp`, `test_menu_entry`, `test_send`
  fail the same way on `main` in this environment, unrelated).
- `node --test webos/tests/*.test.js` (110) and `tizen/*.test.cjs` (29) pass; `tizen/verify-build.py` PASS.
- LG/Samsung app in headless Chromium with a fake PC: the switch saves, a pick POSTs `{"name"}` with the code, the
  "Not on the PC" toast shows on 404, no script errors.
- Android: changed lines compile (javac against stubs); TranslationsTest passes with the new string.

## NOT verified (to do)

- Gradle build and a real TV + Omarchy PC: switch on, pick a theme, the PC changes within a second or two and the
  TV keeps the same theme (no flip-flop when the palette comes back).
- Theme names: TV theme names must match the PC folder names (stock Omarchy themes do). A ★ custom theme pushed
  from the PC has its PC name, so it works; one made only on the TV gets "unknown" and changes only the TV.


# D. TV and PC power together + settings switches

Launcher settings (Android) / All OmarchyTV settings (LG/Samsung): **Boot screen** (at start / start and wake / off),
**Also change the PC theme** (C), **TV and PC power together** (Android; LG shows "PC sleeps with the TV").
All default as before (boot screen on, the other two off).

- Android: on screen ON (and at launcher start) `WakeOnLan.send(pcMac)` + event `tv_power` on; on screen OFF `tv_power`
  standby. The MAC comes from omarchy-link's `pc_wol` after each connect, kept in omarchytv.json `link.pcWol`.
- PC (`omarchy_link/power.py`): standby → suspend after `powerGraceS` (60 s) with `systemctl suspend` / `pmset
  sleepnow`; cancelled by `on`; skipped while a liftoff runs; off with `powerFromTv: false`.
- LG: on `visibilitychange` hidden, asks the TV's power state over SSAP (needs "TV control" approved) and only when it
  is not "Active" POSTs `/v1/power` standby (keepalive); visible again → on. LG/Samsung can't send Wake-on-LAN (no UDP
  in TV web apps): said in the UI. Samsung: no power row.

# E. Update from the TV

- **Android** (`Updater.java`, `REQUEST_INSTALL_PACKAGES`, receiver `Updater$Done`): GitHub releases API → newest
  `omarchytv-release-<ver>.apk` newer than the installed versionName (drafts and non-github.com URLs ignored) → confirm
  dialog → download (redirects https only, 128 MB cap, release `digest` sha256 when present) → same package, higher
  versionCode, same signer as the running launcher → PackageInstaller session; Android shows its own confirm.
  Android 8+: first time it opens "Install unknown apps" for OmarchyTV.
- **LG/Samsung** (`pc-plugin/omarchy-tv/lib/omarchy_tv/tvupdate.py`, bridge `POST /v1/update/tv` with the ask code):
  `check` → `{current, latest}`; `install` (with `want` = the confirmed version) → background job:
  LG `omarchytv-webos-<ver>[-testing].ipk` → `update.check_ipk` → `ares-install [--device webosDevice]` → `ares-launch`;
  Samsung `OmarchyTV-tizen-<ver>-unsigned.wgt` → unpacked (config.xml must say package OmarchyTV0, no `..` paths, old
  signatures dropped) → `tizen package -t wgt -s <tizenProfile>` (the user's own certificate, same as build-wgt.sh)
  → `sdb connect <TV IP>` → `tizen install -t <TV IP>:26101`. One job at a time; `updateFromTv: false` turns it off.
- Neither side changes version numbers, signs with the release key, or publishes anything.

# F. Liftoff (build/update on the big screen)

- `omarchy-link liftoff [--name N] -- <command>`: runs it without a shell, output still in the terminal, exit code
  passed through; state in `$XDG_RUNTIME_DIR/omarchy-link-<uid>/liftoff.json`, last duration per name in
  `~/.local/state/omarchy-link/liftoff-history.json` (T-minus next time).
- Sent to Android as event `liftoff` (session ticker, on change / every 5 s); LG/Samsung read `liftoff` in `/v1/status`
  (polled every second while it runs). Updates (E) write the same state, kind "update".
- TV: `LiftoffView.java` / `webos/app/liftoff.js` + overlay in app.js: ASCII rocket with the OT badge, T-minus clock
  (blinks in the last 10 s), stage, progress bar; LIFTOFF = rocket climbs away, hides after 5 s; failure = red blinking
  ABORT, exit code, last 6 lines, three-beep alarm (ToneGenerator / WebAudio), toast if Home is not on screen. Any key
  hides it. Shared words and numbers: `LiftoffScript.java` = `liftoff.js`, same cases in `webos/tests/liftoff-cases.json`.

## Files (D, E, F)

| File | Change |
|---|---|
| `launcher/.../WakeOnLan.java`, `Updater.java`, `LiftoffScript.java`, `LiftoffView.java` | New |
| `launcher/.../OmarchyLink.java` | Screen on/off → `powerChanged()`; `pc_wol` saved to config |
| `launcher/.../link/LinkServer.java` | Incoming `pc_wol`, `liftoff` (clipped by `liftoff()`) |
| `launcher/.../LauncherConfig.java` | `pcPower`, `pcMac`, `pcBroadcast` |
| `launcher/.../MainActivity.java` | Settings rows; update flow; `liftoff` event; key hook; onDestroy |
| `launcher/app/src/main/AndroidManifest.xml` | `REQUEST_INSTALL_PACKAGES`, receiver `.Updater$Done` |
| `launcher/.../res/values*/strings.xml` | 20 new strings × 23 languages |
| `launcher/app/src/test/.../LiftoffScriptTest.java` | Countdown cases, Wake-on-LAN packet, release picker |
| `webos/app/liftoff.js` (new), `index.html` (script + CSS), `app.js`, `i18n.js` | Settings rows, update via PC, LG power, overlay, strings |
| `webos/tests/liftoff.test.js`, `liftoff-cases.json` (new) | Same cases as Java; rocket identical on both |
| `pc-plugin/omarchy-link/lib/omarchy_link/liftoff.py`, `power.py` (new) | See above |
| `pc-plugin/omarchy-link/lib/omarchy_link/session.py`, `config.py`, `cli.py` | Events, ticker push, `pc_wol` on connect, defaults, `liftoff` command |
| `pc-plugin/omarchy-tv/lib/omarchy_tv/tvupdate.py` (new), `webos.py` | Update job; `/v1/update/tv`, `/v1/power`, `liftoff` in status |
| `pc-plugin/omarchy-link/tests/test_liftoff_power.py` (new, in run.sh), `omarchy-tv/tests/test_tvupdate.py` (new), `test_webos.py` | Tests |
| `pc-plugin/omarchy-link/docs/PROTOCOL.md`, `README.md`, `CHANGELOG.md` | Docs |

## Verified (D, E, F)

- omarchy-link `tests/run.sh`: 124 OK. omarchy-tv `test_webos`, `test_tvupdate`, `test_update`: OK.
- `node --test webos/tests/*.test.js` (114) and `tizen/*.test.cjs` (29) pass; `tizen/verify-build.py` PASS.
- Java: LiftoffScriptTest (3) and TranslationsTest (4) pass; every changed Java line compiles against API 34 (javac,
  only androidx missing from the classpath).
- LG/Samsung app in headless Chromium with a fake PC: countdown, ABORT with alarm and last lines, any key hides it,
  settings rows, "Update via PC" check → confirm → install request. Real bridge + real `omarchy-link liftoff`:
  `/v1/status` shows running, then failed with exit code and last lines.

## NOT verified (to do)

- Gradle build + lint (NewApi is fatal here: new API calls are guarded by SDK_INT checks).
- On a real Android TV: Wake-on-LAN reaching the PC (Ethernet, WoL on), suspend after standby, the update from a real
  newer release (the first release after this one is the first that can be installed this way: the installed APK
  must already have the Update row), the installer confirm on Google TV / Fire OS.
- On LG: getPowerState while the TV powers off (does the app get time to POST before suspend?), ares-install with
  the user's device name (`webosDevice`). On Samsung: `tizen package` re-signing with the user's profile, `tizen
  install` target name format.
- The GitHub API is unauthenticated (60 requests/hour per IP): fine for a button, not for polling.

