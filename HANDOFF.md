# HANDOFF — branch `feature/omarchytv-v214-pack`

Notes for whoever continues this branch. Features: **A. Boot screen**, **B. Twenty new languages**, **C. Theme sync both ways**,
**D. TV and PC power together + settings switches**, **E. Update from the TV**, **F. Liftoff**, **G. Living screensaver**, **H. X app**, **I. Retro CRT**, **J. OmaFire wallpapers**, **K. Omastorm from the PC**, **L. Living animation**, **M. Live screensaver**, **N. Retro CRT set**, **O. Liftoff translations**, **P. Omastorm radar in Live** (each built on the previous).
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


# G. Living screensaver

Screensaver settings (Android: Screensaver page; LG/Samsung: Screensaver modal): **Living screensaver** (off),
**PC notifications on the screensaver** (off), **X on the screensaver** (off / PC notifications / X API), **GitHub** (on).

- Drawing: `webos/app/living.js` (ES5, pure `render()` + `mount()`), the same file copied to
  `launcher/app/src/main/assets/saver/living.js` (test enforces identical). Right-hand `<pre>` over the screensaver art.
- Android: `Living.java` keeps the PC feed in memory (event `living`, clipped by `LinkServer.living()`) and hands
  `{enabled, data, labels, colors}` + the TV's cached weather to the saver page through `OmarchySaver.living()`
  (TteSaver JS interface); the page polls it every 2 s. Prefs go to the PC as `living_prefs` on connect and on change.
  Not in the native fallback saver (TVs without a WebView).
- LG/Samsung: app.js mounts it in `#saver-overlay` and POSTs `/v1/living` every 3 s while the screensaver runs.
- PC: `omarchy_link/living.py` (`Living` collector, one per process; `dbus-monitor` for notifications; GitHub via
  `gh`; X API via `x.json`), session events, `omarchy-link x setup|status|forget`, config defaults; bridge
  `POST /v1/living`. If an Android TV and an LG TV both use the same PC, each process polls on its own.

# H. X app

- Android: `LauncherConfig.X_WEBAPP` (x.com/home, desktop UA so X's keyboard shortcuts exist), Walker entry
  "X (x.com)", `XKeys.JS` injected by `WebAppActivity.onPageFinished` (capturing keydown → j/k/l/r/t/n/'/'/g h/g n/g m,
  skipped while a text field has focus), autoplay off, a one-time hint line. Pointer mode (menu) still works.
- LG/Samsung: "X" first in Web apps (opens the TV browser; no key mapping possible in another app).

## Verified (G, H)

- omarchy-link `run.sh` (incl. `test_living`), omarchy-tv `test_webos`: OK. Real `dbus-monitor` on a private session
  bus: a `Notify` call arrives at once with app/title/body.
- `node --test webos/tests/*.test.js` (120) pass; TranslationsTest passes; every changed Java line compiles (API 34).
- Headless Chromium: the Living boxes with a fake PC (screenshot), prefs POSTed; `XKeys.JS` maps ▼/1/0 to j, l, g h and
  leaves keys alone inside a textarea.

## NOT verified (to do)

- Gradle build; Android saver WebView with the panel on a real TV (font size 1.25vw, readable from the sofa?).
- X login inside the TV web view (username/password; Google sign-in is usually refused in web views) and whether X's
  shortcuts fire from synthetic key events on every X build.
- X API with a real token: endpoint access on the account's plan, real billing vs the estimate in `living.py`.
- Notifications from Omarchy's mako on a real Omarchy PC (dbus-monitor permission is per user, no root needed).


# I. Retro CRT plugin

`plugins.retroCrt` "off" (default) | "light" | "strong". Android: `CrtOverlay.java` on R.id.root (scanline 1×3 shader,
RGB mask 3×1 shader, radial vignette, rounded bezel: theme bgDarker body, accent edge + glow, flicker every 120 ms);
"strong" + Android 13 (API 33) adds `RenderEffect.createRuntimeShaderEffect` with a small AGSL barrel shader on the
root (guarded by SDK_INT and @TargetApi(33)). LG/Samsung: `#crt-overlay` CSS (index.html) toggled by `applyCrt()`
in app.js, colours from the theme CSS variables, so it follows theme changes. Plugins page/modal row.

# J. OmaFire animated wallpapers

`webos/app/vendor/omafire/` and `launcher/app/src/main/assets/omafire/` (identical, test enforces): Wes Grimes'
`fire.js` (only `.pragma library` dropped and wrapped as `window.OmaFire`, header says so), `campsite-approved.webp`,
LICENSE (MIT); `omafire-wall.js` (ours) runs it full screen at 30 fps, modes campfire/embers. Wallpaper values
`omafire:campfire` / `omafire:embers`. Android: `OmaFireLayer.java` adds a WebView (assets only, no network) above
the still wallpaper on resume and removes it on pause. NOTICE + assets/licenses/omafire-MIT.txt.

# K. Omastorm from the PC (Android TV)

- PC `omarchy_link/omastorm.py`: summon the window (title "Omastorm"), resize 1280×720, cast its rectangle
  (`cast.screen_argv` honours `OMARCHY_LINK_SCREEN_REGION`), remote keys → `hyprctl dispatch sendshortcut` to that
  window only (names = Omastorm ui/Keys.js defaults), stop = Escape. Events `omastorm` / `omastorm_state`.
- Android: plugin toggle + "Open Omastorm" (Plugins page, Walker), `OmastormKeys.java` (remote → names, test checks
  every name exists on the PC), CastPlayerActivity forwards keys when the cast title is "Omastorm" and the plugin is on.
- LG/Samsung: not in this package (their app has no live-cast player); the native view comes next.

# L. Living animation

`living.js` `mount()`: lines typed in at 45 ms each, changed lines (ignoring the "3m"→"4m" ticks) decrypt from
random glyphs over 0.9 s (`scramble()`), then the panel glows in the accent colour.

## Verified (I–L)

- Headless Chromium (LG/Samsung page): OmaFire campfire + Retro CRT strong render together (screenshot), no errors.
- node 123 tests (incl. omafire.test.js, living), omarchy-link run.sh incl. test_omastorm, Java OmastormKeysTest,
  LiftoffScriptTest, TranslationsTest; every changed Java line compiles against API 34.

## NOT verified (to do)

- Gradle build + lint; CRT overlay and AGSL curve on a real Android TV (GPU cost on low-end boxes).
- OmaFire in the Android WebView on a TV (30 fps, memory), and on webOS 3/4 (no `image-rendering: pixelated`: soft).
- Omastorm with a real Omastorm install: window title/size, `hyprctl sendshortcut` reaching a Quickshell
  FloatingWindow, gpu-screen-recorder `-region` syntax on the installed version, latency.


# M. Live screensaver (replaces the L overlay)

With "Live screensaver" on (cfg/omarchytv.json `living.enabled`), the screensaver is the Live screen only:
LG/Samsung `startScreensaver()` leaves the tte iframe blank and mounts `OmarchyLiving.fullscreen()`; Android's saver
page skips tte (`window.__otvLive`) and mounts the same, and `TteSaver` keeps the WebView even when the art is the
native aquarium. `living.js`: `scene()` (pure, 112×32 grid: block clock `bigClock()`, `sky()` frames, notifications,
X, GitHub, PC box with meters + `radar()`, ticker, status line) and `fullscreen()` (glass panels in ch/em units,
half-resolution canvas: glyph rain + synthwave grid, refresh sweep, INCOMING banner with shake + `scramble()`,
DOM rewritten only when the text changes, pauses when hidden, adaptive lite mode after 40 slow frames, burn-in
drift). PC stats: LG/Samsung from `/v1/status`, Android from `LinkServer.stats()`. Test: `living.test.js` (grid sizes,
sky frames, waiting text, HTML escaping).

# N. Retro CRT set

LG/Samsung: `fitStage()` shrinks the 1920×1080 stage to 88 % and centres it; `#crt-overlay` covers exactly that area
(its 100vmax box-shadow paints the bezel), `#crt-badge` shows OMARCHY TV + LED; "strong" adds `body.crt-strong`
(saturation/contrast filter + red/blue text fringes). Android: `CrtOverlay` on the decor view, root scaled to 0.88 with
the same pivot, even-odd path for the bezel, rim/glow/LED in `Theme.cur().accent`.

# O. Liftoff translations

`liftoff_title`, `liftoff_stage_{download,verify,sign,install,launch}`, `liftoff_exit` in 23 languages; LiftoffView
`stageLabel()` and app.js map the PC's stage keys; a build's own stage text (its name) is shown as sent.

# P. Omastorm radar in the Live screensaver

Prefs `radar` (Live settings "Omastorm radar", off). PC: `living.Living` calls `omastorm.snapshot()` every 90 s while
the screensaver runs: summons Omastorm if closed, `grim -g <window> -s 0.5 -t jpeg -q 70 -`, JPEG ≤ 300 KB, sent as
`radar: {img: data URL, at}`; Android clips it in `LinkServer.living()` (JPEG data URL, base64 only, ≤ 420 KB).
The TV puts it in the weather box in place of the ASCII sky. Test: `test_omastorm.Snapshot`.

## Verified (M–P)

- node 124, Tizen 29, verify-build PASS, omarchy-link run.sh, omarchy-tv tests, TranslationsTest, Android compile
  (API 34) of every changed line.
- Headless Chromium: Live screen with a fake PC (screenshots + video), INCOMING banner, radar image in the weather box,
  CRT set on Tokyo Night and Gruvbox (screenshots).

## NOT verified (to do)

- Gradle build + lint. Live screen on a real Android TV saver WebView (font fit, 10 fps backdrop cost, lite switch).
- CRT on Android: decor-view overlay + scaled root with AsciiMenu popups and dialogs (they are separate windows,
  not scaled). Omastorm snapshot with a real Omastorm window and grim on Hyprland.

