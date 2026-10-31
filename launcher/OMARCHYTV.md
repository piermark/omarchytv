# OmarchyTV Launcher (`com.omarchytv.home`)

An Omarchy desktop-styled home screen for Android TV (`Copyright (c) 2026 Marco Porro (x.com/MarcoPorro · www.marcoporro.it)`, MIT). Inspired by `tv-vibehack` by Gathin.

**Package:** `com.omarchytv.home`.
`minSdk 26` (Android 8.0), `targetSdk 29`, compileSdk 34.

## What changed

| Area | Before (tv-vibehack) | OmarchyTV |
|---|---|---|
| Background | pitch black | Omarchy Tokyo Night wallpaper (`Liftoff Sunset` by Tom Ballard, 3840x2160) under a dark scrim, or an original procedural one (`wallpaper` in config) |
| Top | greeting + big clock left, frosted pills right | Waybar-like bar: Omarchy icon, numbered workspaces (= rows; 1 = dock), centred `EEEE HH:mm` clock, Inputs / Wi-Fi / Settings modules |
| Brand | none | pixel `OMARCHY TV` wordmark (Omarchy logo.txt + matching TV) + `user@omarchytv:~$ <greeting>` prompt with blinking cursor |
| Tiles | rounded 16dp, glass highlight | square (Hyprland `rounding = 0`), 2dp `#414868` inactive border, focus = 2dp blue→purple gradient border (Hyprland active border) |
| Dock | rounded lighter band | dark Hyprland window, 2dp `#292e42` border |
| App list | grid | same grid, plus a Walker-style header (`> app · N installate`) above it |
| Font | Outfit | JetBrains Mono (Omarchy's default terminal font, OFL) |
| Motion | springy glass, overshoot scale, staggered tile entrance | terminal-style: decrypt reveal on wordmark, labels and hero; typewriter prompt; stiff no-bounce focus move (~80 ms); scale 1.04 linear 90 ms |
| Inputs popup | glass dropdown | floating window, 2dp accent border, accent-block selection |
| Screensavers | Clock | **OmarchyTV** (`.OmarchyDream`, new) + Clock (recoloured) |
| Strings | en, de | + it |

Theme pieces are kept separable for porting (e.g. to nielsvanvelzen/tv-launcher, GPL-3.0):
`res/values/colors.xml`, `res/font/`, `res/drawable/*` (tile_bg, pill_*, input_row_bg, glass_panel,
bar_*, workspace_*), `res/layout/*`, and the self-contained views `WallpaperView`, `WordmarkView`,
`FocusFrameView`, `AsciiSaverView`, `BlockFont`, `TextFx`.

## Configuration

Build-time defaults: `app/src/main/res/values/config.xml` (dock, hidden apps, Live TV package =
`com.sony.dtv.tvx`, columns, `wallpaper`, `omarchy_icon`, `logo`, `screensaver_clock`).

Run-time override (no rebuild): push a JSON file, the home screen reloads it on the next Home press:

```bash
tools/venv/bin/python -I tools/b64push.py omarchytv.json /sdcard/Android/data/com.omarchytv.home/files/omarchytv.json
```

```json
{
  "version": 1,
  "theme": { "name": "tokyo-night", "accent": "#7aa2f7", "accent2": "#bb9af7" },
  "wallpaper": "omarchy",
  "dock": ["com.netflix.ninja", "com.google.android.youtube.tv", "com.amazon.amazonvideo.livingroom", "com.disney.disneyplus"],
  "hidden": [],
  "widgets": [{ "id": "omarchy.clock" }, { "id": "com.omastorm.radar", "enabled": false }],
  "logo": "omarchy-tv",
  "screensaver": { "art": "omarchy-tv", "clock": true }
}
```

`wallpaper` may also be an absolute path to an image on the TV (decoded subsampled to screen size).
See [PLUGINS.md](PLUGINS.md) for the widget list.

### Remote panes (fetched by the TV)

For data with no PC on the LAN to push it (Omarchy Link only accepts LAN peers), the TV can fetch panes
itself from an HTTPS URL:

```json
"remotePanes": { "url": "https://example.com/tv/panes.json", "refreshSec": 300 }
```

Plain `http://` is accepted only to a literal private / link-local LAN address (10/8, 172.16/12,
192.168/16, fc00::/7, link-local; no host names, never a public address), the same rule as the web tiles,
e.g. a home automation server such as Node-RED:

```json
"remotePanes": { "url": "http://192.168.1.20:1880/omarchytv/panes.json", "refreshSec": 300 }
```

The body is the one of `PUT /v1/widgets` (PROTOCOL.md §6), **panes only**: shortcuts and actions are
ignored, so a URL can only show text. Same limits and cleaning (8 panes × 8 lines, label ≤ 16, value ≤ 64,
printable text). A line may carry `"tone": "up"` or `"down"` to colour its value green or red. Up to 3 panes
are shown in a row under the home widgets.

```json
{ "panes": [ { "id": "crypto", "title": "crypto · 24h", "ttl": 900, "at": 1793112467,
               "lines": [ { "label": "BTC", "value": "$80,148  +0.3%", "tone": "up" } ] } ] }
```

Rules: `https://` only, no redirects, at most 64 KiB, fetched on a background thread at most every
`refreshSec` (300–86400, default 300: the PLUGINS.md minimum) and only while the home screen is resumed. The last good answer is
cached in the app's files dir; a pane dims after `ttl` and hides after 2 × `ttl` (counted from its `at`,
else from the fetch), so a dead source never shows old numbers as current. No url (default) = off.

### Weather in the bar

`"barWeather": true` shows the current weather as a line icon and the temperature in the bar, left of the
status icons (same data and location as the weather widget). Combine with `"homeWidgets": {"weather": false}`
to keep only the bar version.

## Screensaver

`OmarchyDream` re-creates Omarchy's screensaver (`bin/omarchy-screensaver` loops
`ttfx -i screensaver.txt --random-effect` over `logo.txt`): the logo on black, a random intro each
cycle (decrypt, rain, beams, print, slide, expand, matrix, wipe, burn; never the same twice in a row), a ~14 s hold with a slowly cycling Tokyo Night
gradient, then a dissolve. Burn-in protection: new random position every cycle, slow drift during the
hold, shifting colours, dimmed screen. ~30 fps only during the 2.6 s intro / 1.1 s outro, 4 fps otherwise.

**Since v2.6.2 the screensaver runs inside the launcher**: it shows only after N minutes (Screensaver →
Start after: 1/2/5/10/15 min, default 5) without remote input *while the OmarchyTV home is in the foreground*
(no menu open, not in move mode). Live TV, video, other apps, `> ask` and voice pause the home activity, so
the timer is off there. Any key dismisses it back to home (the key is swallowed). No wakelock, no
keep-screen-on: the TV's own sleep timer still applies.

The system `DreamService`s (`OmarchyDream`, `ClockDream`) are now **optional and disabled by default**:
Screensaver → "Also use as system screensaver" (default off) enables them. The install scripts
(`build-and-install.sh/.ps1`) do not set `screensaver_components`, and nothing in the launcher does.
Installs that already pointed `screensaver_components` at OmarchyTV keep the dreams enabled. With the
option on, Android starts them only after its idle timeout and never while video plays:

```bash
tools/venv/bin/python -I tools/tv.py 'settings put secure screensaver_components com.omarchytv.home/.OmarchyDream'
tools/venv/bin/python -I tools/tv.py 'settings put secure screensaver_enabled 1'
tools/venv/bin/python -I tools/tv.py 'settings put secure screensaver_activate_on_sleep 0'
tools/venv/bin/python -I tools/tv.py 'settings get system screen_off_timeout'        # idle time in ms
tools/venv/bin/python -I tools/tv.py 'settings put system screen_off_timeout 900000' # e.g. 15 min
```

Or pick "OmarchyTV" in Settings → Device Preferences → Screen saver. Clock: `.ClockDream`.
Logo (`logo` in config / JSON, used by home and screensaver): `omarchy-tv` (default: Omarchy's logo.txt + a matching pixel "TV"), `omarchy` (logo.txt unchanged), `original` (OmarchyTV's own block font, no Omarchy artwork - use this, `wallpaper: procedural` and `omarchy_icon=false` for a public build without Omarchy branding).

## Resolution

Layouts are in dp/sp, icons are vector drawables, the wordmark and screensaver are drawn as
rectangles, and the bundled wallpaper is 3840x2160. Most Android TVs, the Sony XE90 included, draw the
UI at 1920x1080 (density 320) and scale it up, while video plays at native 4K; the wallpaper is then
decoded at exactly 1920x1080 (power-of-two subsample), and on a native-4K UI surface at full size.

## Sony BRAVIA notes (KD-55XE9005, Android 8.0 / 9)

- Stock home is `com.google.android.tvlauncher` (the script's default `STOCK_LAUNCHER`). Some firmwares
  still carry `com.google.android.leanbacklauncher` and `com.sony.dtv.tvlaunchercustomizer`; leave them
  enabled as fallback. Android 9 needs the component: `cmd package set-home-activity --user 0 com.omarchytv.home/.MainActivity`.
- If Home still opens the stock launcher after an update, re-run the `set-home-activity` command; a
  firmware update can re-enable the stock launcher.
- Sony reports every HDMI port as connected; the Inputs menu already hides the bogus status.
- Live TV is `com.sony.dtv.tvx`: never disable it.

## v2: logo menu, move mode, layout, panes

- **Logo menu** (top-left Omarchy icon, focusable): Launcher settings (language Auto/English/Italiano/Deutsch,
  UI size S/M/L, logo, accent, panes, hidden apps, reset order), **Layout (Hyprland)**, Wallpaper,
  Reorder apps, Screensaver (preview, logo, size, clock, system settings), Settings, About & licences.
- **Live preview**: every setting is saved to `omarchytv.json` on OK, the home screen rebuilds behind the
  menu and the menu reopens on the same row, so you see the change immediately.
- **Layout (Hyprland)**: separate steps, OK = next step (wraps):
  text size 85/100/115/130 %, tile size 85/92/100/110 % (capped so 4 columns always fit), gaps 0/4/8/12/16 dp, border 1–4 dp.
- **Tile menu**: long-press OK on any app → Move · Hide from launcher · App info · Uninstall
  (Uninstall only for user-installed apps; it opens Android's own confirm dialog).
- **Move mode**: arrows move the tile (orange border), OK saves, BACK cancels. The first row is the dock
  (4 slots). Order is saved as `dock` + `order`.
- **Dock default**: Netflix, Prime Video, YouTube, Disney+ when installed; empty slots are filled by the
  next apps alphabetically.
- **Panes** (top right): `clock` + `ai` (default), `sysinfo`, `weather` (Open-Meteo, no key; needs lat/lon,
  refreshed at most every 30 min, only while Home is visible).

```json
{
  "version": 1,
  "language": "auto",
  "uiSize": "medium",
  "geometry": { "fontScale": 1.0, "tileScale": 1.0, "gaps": 8, "border": 2 },
  "panes": ["clock", "sysinfo", "weather"],
  "weather": { "lat": 45.46, "lon": 9.19, "place": "Milano" },
  "wallpaper": "omarchy-liftoff",
  "dock": ["com.netflix.ninja", "com.amazon.amazonvideo.livingroom", "com.google.android.youtube.tv", "com.disney.disneyplus"],
  "order": [],
  "hidden": []
}
```
`wallpaper`: `omarchy-liftoff`, `omarchy-road` (both from Omarchy's Tokyo Night theme, see NOTICE), `procedural`, or an absolute image path.

### Connect Omarchy Linux (Omarchy Link pairing screen)
Logo menu → *Connect Omarchy Linux* (it: *Collega Omarchy Linux*, de: *Omarchy Linux verbinden*): TV address (`ip:47800`),
6-digit code, status, one row per paired PC (*connected* / *not connected* · *forget*), *New code*, Link service state,
and the optional *Remote control (Back)* and *Usage access* rows. Since v2.5 the Link service is real (see v2.5 below).
### v2.1
- **Clock**: bar clock 20sp bold; the clock pane shows the time at 48sp (4x pane text). Both are sp, so
  they follow UI size and text size. Panes tick on the minute boundary and share one row height.
- **AI credits pane** (`"ai"`, default on; `sysinfo` now off by default). Display only: the Omarchy Linux
  plugin pushes the numbers into `omarchytv.json`; the TV never holds API keys or calls any AI provider.
  Empty state: "connect Omarchy Linux to show AI credits".
  ```json
  "ai": [
    {"name": "Grok",   "used": 12.4, "limit": 20, "unit": "$",      "resetsAt": "2026-11-01T00:00:00Z", "updatedAt": 1792361400},
    {"name": "Claude", "used": 84,                "unit": "%",      "resetsAt": "2026-10-19T03:00:00+02:00"},
    {"name": "Codex",  "used": 1900000, "limit": 2000000, "unit": "tokens"}
  ]
  ```
  Bar `[■■■□□] 62%` (green < 70 %, orange < 90 %, red ≥ 90 %), amount, `↻` time to reset, and an orange
  `stale 2h` when `updatedAt` is older than 1 h. Times: epoch s/ms or ISO-8601. At most 6 entries.
- **Wallpaper**: centre-crop to the screen aspect (never stretched, any source aspect), only the crop
  is decoded (BitmapRegionDecoder) with a power-of-two subsample that keeps it ≥ the screen, so a 4K image
  decodes at 1920x1080 on a 1080p UI and 3840x2160 on a 4K UI. A re-pushed image at the same path is
  picked up on the next resume (file mtime), so PC-side wallpaper sync works without editing the JSON.

### v2.2: TV channels
A `> tv` section under the apps (the apps grid has the matching `> apps · N installed` prompt; the prompt of
the focused section shows a blinking cursor). Channels come from Android's TvProvider
(`content://android.media.tv/channel`), grouped by source with a tag on every tile:
`DTT` (DVB-T/T2, ISDB-T, ATSC, analogue), `SAT` (DVB-S/S2, ISDB-S; e.g. Tivùsat), `CAB` (DVB-C/C2), `IP` (app/IP channels).
OK opens the channel (`ACTION_VIEW` + `TvContract.buildChannelUri(id)`, sent to Sony Live TV `com.sony.dtv.tvx`).
Long-press: Move (within its source) / Hide. Logos are loaded lazily from the provider; the list loads on a
background thread and is cached in `files/channels.json`. Channels hidden in Live TV's list are skipped.

**Access.** In AOSP's TvProvider (Android 8/9) an app sees only its own channels unless it holds
`android.permission.READ_TV_LISTINGS` (protection level *dangerous*, defined by TvProvider), which allows reading
every channel marked searchable (the default). `com.android.providers.tv.permission.READ_EPG_DATA` is deprecated and
not enforced; `ACCESS_ALL_EPG_DATA` is system-only. So: Settings (logo menu) → TV channels → *Channel access* → OK
shows the permission dialog, or over adb:
```
adb shell pm grant com.omarchytv.home android.permission.READ_TV_LISTINGS
```
If the TV's tuner marks its channels non-searchable or the vendor provider is stricter, the row stays empty;
then use *Add channel by number* (0-9 on the remote, OK). A manual favourite that matches a readable channel uses it;
otherwise OK opens Live TV (Android has no public "tune to number" intent).
```json
"channels": { "enabled": true, "sources": ["dtt", "sat", "cab", "ip"], "order": [], "hidden": [],
              "manual": [{"number": "1", "name": "Rai 1", "source": "dtt"}], "maxPerSource": 200 }
```

### v2.3: web apps, voice search, > ask
- **Web apps** (like `omarchy-webapp-install`): `"webapps": [{"name":"X","url":"https://x.com","ua":"mobile|desktop","icon":"/abs/path.png"}]`
  (default: X). Add/remove in logo menu → Web apps (the TV keyboard's mic does dictation), or push from the PC.
  Each is an app tile (move/hide like apps, "Remove web app" instead of uninstall). Opens fullscreen in
  `WebAppActivity`, **own process `:web`**, killed when you leave, so the launcher stays light.
  Remote: D-pad = links/fields (WebView spatial navigation); MENU or long-press OK = menu (cursor mode,
  desktop/mobile site, reload, exit); cursor mode: arrows move a pointer, OK clicks, edges scroll;
  BACK = history back, then exit. Cookies persist (logins). Desktop UA = Chrome on Linux.
  **WebView on this TV:** Android 8/9 get Chrome/WebView up to **138** only (139+ needs Android 10). Update
  "Android System WebView" / Chrome in the Play Store to reach 138; below 110 the web app shows a warning.
  After that it gets no security fixes, so treat logins in web apps accordingly.
- **> search** (logo menu, or the mic/Search key if `"voiceKey": "launcher"`; the default `"companion"` opens the Grok Bot companion by voice, `"assistant"` leaves it to Google Assistant):
  system speech recognizer, then fuzzy match over apps, web apps and channels ("apri Netflix", "metti Rai uno",
  "canale cinque", "open YouTube"). A clear winner opens directly, otherwise a list.
  Some remotes (the SHIELD's, for one) send their mic key as `KEYCODE_ASSIST`, which Android hands to the
  Assistant app before the launcher sees it, so `"voiceKey"` can't catch it. Make OmarchyTV the Assistant app and
  that key opens the companion (or Walker, with `"voiceKey": "launcher"`) from anywhere: `adb shell cmd role add-role-holder android.app.role.ASSISTANT com.omarchytv.home`.
  Speech recognition stays with Google. Undo: `adb shell cmd role add-role-holder android.app.role.ASSISTANT com.google.android.katniss`
  (or Settings → Apps → Default apps → Assist app).
- **Remote buttons** (Launcher settings → Remote buttons, shown only while a remote listed in `assets/remotes.json` is
  connected; or `"keys"` in the config): bind a remote key to an app,
  an intent or an OmarchyTV action, separately for a single, double, triple and long press. Example (the SHIELD
  Netflix button):
  ```json
  "keys": {
    "BUTTON_12": {
      "single": {"app": "org.smarttube.stable"},
      "double": {"action": "walker"},
      "triple": {"intent": "intent:#Intent;action=android.settings.SETTINGS;end"},
      "long":   {"action": "voice"}
    }
  }
  ```
  Key names are Android `KeyEvent` keycode names without `KEYCODE_` (`BUTTON_12`, ...); unknown names are ignored. A press type has exactly one of `app` (package name, started like its tile), `intent` (an
  `Intent.toUri(Intent.URI_INTENT_SCHEME)` string: started as an activity, sent as a broadcast if no activity
  matches) or `action` (an OmarchyTV action id); anything else counts as unbound. Action ids: `walker` (search),
  `voice` (search by voice), `home`, `ask`, `menu`, `screensaver`, `next-theme`, `workspace-1` … `workspace-5`.
  Quick presses less than about 0.35 s apart count together; a press type that isn't bound falls back to the
  highest bound count below it (double with only single bound runs single), and a lone single press runs at once
  when nothing higher is bound. A long press (about 0.5 s) runs while the key is held.
  Only buttons of known remotes (listed with their USB/BT ids in `app/src/main/assets/remotes.json`; today the
  SHIELD 2019 remote's Netflix button) are intercepted, and the settings page lists the buttons of the remotes
  that are connected. To add your remote see [REMOTES.md](REMOTES.md).
  **A key with no bindings is left alone** (system behaviour unchanged); a bound key is consumed in every app, so on
  the SHIELD remote `BUTTON_12` no longer launches Netflix. This needs the **OmarchyTV remote buttons** accessibility
  service (`KeyMapService`; it sees key presses only, no screen content): enable it in Settings → Accessibility, or
  over adb (`put` replaces any other enabled services, so list them all, colon-separated):
  ```
  adb shell settings put secure enabled_accessibility_services com.omarchytv.home/.KeyMapService
  adb shell settings put secure accessibility_enabled 1
  ```
  The config is re-read on every key press; no restart needed.
- **> ask**: terminal voice assistant pane; answers come from the paired Omarchy Linux PC (see PLUGINS.md).
  Optional colour key: `"assistant": {"provider":"grok","tts":false,"key":"red"}`.
- **Omarchy Link alignment** (PROTOCOL.md §8): pairing code in memory only (new code each time the page
  opens), paired PCs read from `link.pcs[]`, old `link.pairingCode/token/pc` removed from omarchytv.json.

### v2.4: one TV tile, cleaner home
- **TV channels row removed** (v2.2 superseded): no READ_TV_LISTINGS permission, no TvProvider queries,
  no channel cache, no `"channels"` key (dropped from omarchytv.json the next time the TV setting is changed).
- Home order: `> apps` (dock + full grid of installed apps), then `> tv`, separated by spacing and a 1px
  hairline, with **one wide tile "TV"** that starts the TV's Live TV app the way its own launcher would
  (Sony: `com.sony.dtv.tvx` leanback launch intent → last channel). The Live TV app is not repeated in
  the app grid. HDMI etc. stay in the Inputs pill. Toggle: logo menu → TV, or `"tv": {"enabled": false}`.
- Reorder, hide or rename workspaces, or show names in the bar: logo menu → Launcher settings → Workspaces, or
  `"workspaces": {"order": ["home", "apps", "pc", "system"]}` (names: `home`, `tv`, `apps`, `pc`, `system`). The bar
  numbers them 1..n in that order, the number keys and CH+/CH− follow it, and the ones left out are hidden. Home
  always stays (first when left out). `"labels": "names"` shows names in the bar instead of numbers (the keys
  still go by position), and `"names": {"pc": "cachy"}` renames a workspace (12 characters at most).
- Calmer look, same v2.1 character (wallpaper, wordmark + prompt, panes, dock): bigger tiles (198×111 dp),
  1px hairlines instead of 2px boxes, unfocused banners desaturated/dimmed (full colour on focus),
  smaller `> App` title, panes clock → weather → compact AI credits (bars and %, no amounts).
  Weather is in the default row but only appears once `"weather": {"lat":…, "lon":…, "place":"…"}` is set.
- Screensaver clock: about 30% of the wordmark height, scales with the screensaver size setting.

### v2.5: Omarchy Link service (TV side)
Implements `omarchy-link/docs/PROTOCOL.md` (v1): HTTP + WebSocket server on TCP 47800, bound to the LAN
address, own-subnet peers only, size and rate limits; mDNS `_omarchytv._tcp` (TXT `v,id,name,pair`, `pair=1`
only while the pairing page is open, max 5 min); SPAKE2 pairing over the RFC 3526 group with `BigInteger`;
signed requests/responses and sealed events (byte-exact against `docs/vectors.json`, JVM tests in `app/src/test`).
- Code: `link/LinkCrypto`, `link/LinkStore`, `link/LinkServer` (plain Java), `OmarchyLink` (Android host),
  `OmarchyRemote` (optional accessibility service, Back only).
- Storage: code in memory; tvId + per-PC tokens in `getFilesDir()/link.json`; `omarchytv.json` gets only
  `link.pcs[] {name,address,lastSeen}` (max 8), `theme`, `wallpaper`, `link.panes`, `link.shortcuts`,
  top-level `ai` + `aiAt` + `aiSources`, `link.aiProviders`, `link.aiDefault`.
- Keys: home, media keys (`AudioManager.dispatchMediaKeyEvent`), volume/mute. Back needs the accessibility service:
  `adb shell settings put secure enabled_accessibility_services com.omarchytv.home/.OmarchyRemote` and
  `adb shell settings put secure accessibility_enabled 1` (this replaces the list; append if you use others).
- Current app in Status: `adb shell appops set com.omarchytv.home GET_USAGE_STATS allow` (otherwise the last app the
  launcher opened).
- AI credits: `null` used/limit shows `?`. Logo menu → *AI credits*: one switch per source the PC sent in
  `aiSources` (e.g. Omarchy Agents panel, Omacash). A flip sends the `ai_sources` event with the full enabled list;
  the switch only changes when the PC re-pushes; `ai_sources_error` shows the PC's message. Provider and source ids are
  never hard-coded.
- `> ask`: `ask` event → streamed `answer` chunks (typewriter) → `done` (+ `final` for TTS), or `answer_error`.
  New `session` per visit of the screen.
- Off switch: `"link": {"enabled": false}` in `omarchytv.json` (no socket, no mDNS, nothing scheduled).

**PC off or unreachable = the TV behaves exactly as without Link.** The TV never connects out, never polls and
never retries: the PC opens the event socket. With no PC connected there is no timer at all (the WebSocket ping timer
and the 10 s foreground-app check exist only while a PC socket is open), and no wakelock. Start-up work (store
load, bind, mDNS) runs on one low-priority worker thread that exits after 10 s idle; the UI thread only queues it.
The home screen never waits on the PC: the AI pane shows the last data with **not connected** (or hides, *AI
credits → When not connected*), and `> ask` says **not connected** at once.

Measurements: JVM `LinkServerTest.idleWithoutPcIsPassive`: construct + bind 0-1 ms, no ping thread, `idle()` true,
`ask()` answers `not_connected` instantly. On the TV, compare cold start with Link on and off:
```
adb shell am force-stop com.omarchytv.home && adb shell am start -W -n com.omarchytv.home/.MainActivity   # TotalTime
# then set "link": {"enabled": false}, push omarchytv.json, repeat; expect the same TotalTime (±noise)
```

### v2.6: PC panes and the `> omarchy` shortcuts row
- **PC panes** (`link.panes`, from `PUT /v1/widgets`): rendered in the same minimal pane style (tiny `─ title ─`,
  compact 10sp text, `label value` lines, text only). Row 1 keeps clock / weather / AI credits; PC panes join it only
  while they fit next to the wordmark (max 5 per row, measured, never overflows). The rest go to a **second row**
  (right-aligned, only if it clears the dock and hero line; it fades out once the grid scrolls under it) or are
  **skipped** (setting). Connected: dimmed after `ttl`, hidden after 2×`ttl`. Not connected: last data with
  *not connected* on top, or hidden (same *When not connected* setting as AI credits).
- **`> omarchy`** row (`link.shortcuts`) between `> apps` and `> tv`, app-sized tiles, hidden when there are none.
  `app` launches the package, `view` opens an `https://` / `vnd.youtube:` / `nflx://` URI (optionally in its package),
  `pc` sends the `shortcut` event; with the PC off a `pc` tile is dimmed and says *not connected* (nothing is sent).
  D-pad: Down from the last app row enters the row, Down again goes to `> tv`; voice search matches shortcut labels.
- Logo menu → *Omarchy widgets*: PC panes on/off, each PC pane on/off, *Too many panes* (second row / skip),
  shortcuts row on/off, *When not connected*. Stored as
  `"pcWidgets": {"panes": true, "shortcuts": true, "overflow": "row2", "hidden": []}`.
- Previews (HTML/CSS mocks, sample data): `(dev preview, not published) `v26-home-omarchy-focused.png`,
  `v26-ai-sources.png`.

## v2.6.1 — focus line, section headers, readable transparency

- **Fixed focus line (leanback).** Every focused row (dock, app grid, `> Omarchy`, `> TV`) comes to rest
  at the same height: the dock's place. `onTileFocused` scrolls by `item.top − FadeTopFrame.focusLine()`;
  the layout manager's own "scroll just enough" is disabled; bottom padding lets the last row reach the line.
- **No more rows over the header.** `FadeTopFrame` (the grid's wrapper) clips scrolled content to an 88dp
  strip above the focus line (never above the panes) and fades its top 48dp out (DST_OUT gradient,
  layer only while scrolled, zero cost otherwise). Rows above the line never reach logo/prompt/panes.
  From app row 3 (or a second shortcut row) the section prompt is pinned at the top of the strip.
- **Section headers** read `> Apps`, `> Omarchy`, `> TV` (prompt style, accent colour, always visible as
  dividers). `> Apps` is drawn just above the dock window; the focused app's name is a separate, smaller
  (18sp, plain) line above it. The walker bar under the dock now reads `> all · N installed`.
- **Menus:** info-only rows (`sources mirrored by Omarchy Linux`, `no AI data yet`, `no PC panes`,
  `no TV inputs`) are hint rows: dimmed, non-focusable, D-pad skips them.
- **Transparency, readability first** (`Surfaces`). Only backgrounds are see-through Tokyo Night bg
  (Waybar, panes, walker, tile surface; menus ≥ 95 % because they float over banners). No blur.
  The big band behind the dock is gone (hairline frame only). Banners/icons stay opaque; unfocused
  banners lose at most 15 % brightness (was 28 % + heavy desaturation); greyed PC shortcuts are opaque.
  Subtle text shadow on text that sits on the wallpaper. Settings → Layout → Transparency 0/15/30/45/60 %,
  default 30 % (surfaces at 70 %).
- **Contrast ≥ 4.5:1 on the worst-case wallpaper area.** The scrim is no longer baked into the wallpaper:
  `WallpaperView` keeps the brightest sampled pixel per 1/48 screen band, and MainActivity reports where
  text sits (Waybar, wordmark = large text 3:1, prompt line, panes, dock/grid strip). Those bands get the
  least scrim that keeps every text colour ≥ 4.5:1 (≥ 4.7 with headroom) over that brightest pixel, text on
  plates counts the plate; bands without text keep the light floor so the wallpaper shows. Dim text is
  now `#7f88b3` (5.2:1 on bg) instead of `#565f89` (2.9:1); text straight on the scrim never uses it.
  `SurfacesTest` checks white (worst case) for every transparency step, plates and menus.
- Previews (HTML/CSS mock, labelled): `v26-home.png`, `v26-home-omarchy-focused.png`, `v26-ai-sources.png`,
  `v26-scroll-focusline.png` (home / app row 3 / `> Omarchy` / `> TV` with the focus line drawn: the focused
  tile sits on the same line in all four, nothing above the strip).

## v2.6.2 — "imagine Omarchy were installed on the TV"

- **Wallpaper untouched**, like the Omarchy desktop: no scrim, no darkened strips. Layout → Wallpaper dimming
  off / light (10 %) / medium (25 %), default **off**.
- **Readability from the windows**, not the wallpaper: panes, tiles, the walker bar and the Waybar strip are
  Hyprland-style windows in the theme background at Layout → Transparency (default 10 % = Omarchy's inactive
  window opacity 0.9). `updatePlates` raises a pane's background only as far as its dimmest text needs for
  ≥ 4.5:1 over the brightest wallpaper pixel behind *that* pane. Menus stay ≥ 95 % (they float over banners).
  Text straight on the wallpaper (wordmark, prompt, `> Apps` / `> Omarchy` / `> TV`, app name) keeps only a
  subtle text shadow, so its contrast depends on the wallpaper (use dimming for very bright ones).
- Panes use the same window style as the tiles (theme border colour, border width from Layout → Border).
- Screensaver faithful to omarchy-screensaver: black background, tte-style effects, pixel wordmark; gradient
  and clock colours now come from the synced Omarchy theme (accent, accent2, colors.cyan, colors.foreground)
  with Tokyo Night defaults. Additions only: the clock under the logo and TV scaling.
- In-launcher idle screensaver + optional system dreams (see Screensaver above).
- Previews (mock): `v26-home.png`, `v26-scroll-focusline.png`.

## v2.6.3 — fidelity pass: real Omarchy values, every colour from the theme

Values are taken from the Omarchy source (github.com/basecamp/omarchy, MIT, commit `60f5b49`, 2026-10-18).
1 Hyprland px is mapped to 1 dp (the TV UI runs at 1920×1080 dp-equivalent, so sizes match a 1080p desktop).

| Omarchy source | Value | Launcher |
|---|---|---|
| `default/hypr/looknfeel.lua` `general.gaps_in` | 5 | Default tile/pane gap 5 dp (was 8). Layout → Gaps offers 0/5/8/12/16 |
| `default/hypr/looknfeel.lua` `general.gaps_out` | 10 | Not used for the screen edge: TV overscan safe area (56 dp sides) wins |
| `default/hypr/looknfeel.lua` `general.border_size` | 2 | Border 2 dp on panes, tiles, the focus frame and the menus |
| `default/hypr/looknfeel.lua` `decoration.rounding` | 0 | Square corners |
| `default/hypr/looknfeel.lua` `decoration.shadow` / `blur` | disabled | No glow on the focus frame (removed the halo and fill), no blur |
| `default/hypr/looknfeel.lua` `col.inactive_border` | `rgba(595959aa)` | Inactive pane/tile border |
| `default/themed/hyprland.lua.tpl` | active border = `hyprland_active_border` or `accent`; inactive = `hyprland_inactive_border` or `rgba(595959aa)` | Focus frame = theme active border (its gradient at 45° where the theme sets one, e.g. hackerman) |
| `default/hypr/helpers.lua` `transparent_window` (applied in `default/hypr/windows.lua`) | opacity `0.985 0.96` | Pane/tile background 0.96 (Transparency default 4%), focused 0.985; panes are raised only as far as text needs for 4.5:1 |
| `default/themed/shell.toml.tpl` `[bar]` | background = theme background, alpha 1.0, text = foreground | Top bar opaque theme background |
| `default/themed/shell.toml.tpl` `[menu]` | bg alpha 1.0, border = active-border-foreground, selected bg fg α0.08, selected text accent, selected border fg α0.25 | Logo menu card and rows |
| `default/themed/shell.toml.tpl` `[controls]` | hover/focus fill fg α0.08 + 1px border fg α0.25 | Bar buttons, menu items, inputs |
| `default/themed/shell.toml.tpl` `[popups]` | bg alpha 1.0, text fg, border active border 2 | Inputs, About, `> ask` |
| `default/themed/shell.toml.tpl` `[font]` | base 12 (title 14, heading 16, display 24) | Kept TV scaling (body 13 sp, bar 34 dp ≈ 26 px × 1.3 for sofa distance) |
| `config/alacritty/alacritty.toml`, `config/ghostty/config` | `JetBrainsMono Nerd Font` | JetBrains Mono (bundled, OFL) |
| `bin/omarchy-screensaver`, `default/alacritty/screensaver.toml` | black bg, `tte --random-effect` on `branding/screensaver.txt`, any input exits | Unchanged (v2.6.2) |
| `themes/*/colors.toml` (23 themes) | palettes | Bundled in `assets/omarchy_themes.json` |

Omarchy no longer ships Waybar/Walker files: the bar and launcher are the "Omarchy shell", configured in
`default/themed/shell.toml.tpl`, so those values replace the old waybar.css/walker.css.

**Colours.** `Theme.java` resolves the current theme in this order: the full `theme.colors` pushed by the PC,
then `theme.accent` / `accent2` / `mode`, then the bundled palette for `theme.name`, then Tokyo Night. The UI's
Tokyo Night literals are mapped to their `colors.toml` roles (background, dark/darker/lighter background,
selection, muted, foreground, dark/bright foreground, accent, magenta, cyan, green, orange, red, yellow), so bar,
menus, panes, tiles, focus frame, wordmark, prompt and screensaver all follow the theme. Light themes
(e.g. rose-pine) drop the text shadow, and their plates are checked against the darkest pixel behind them.
Logo menu → Layout → **Theme** cycles the bundled themes on the TV; a theme pushed from the PC takes priority.

Previews: `(dev preview, not published) `(dev preview, not published) (tokyo-night, catppuccin, gruvbox,
rose-pine; HTML mock `(dev preview, not published) not emulator screenshots). The theme wallpapers
are third-party artwork and are not committed; `(dev preview, not published) builds them from an Omarchy checkout.

## v2.7 — Send to TV (Omarchy Link cast, TV side)

Implements omarchy-link `docs/PROTOCOL.md` §6 "Cast" and its launcher checklist (PC side: `docs/CAST.md`).

**Status.** `capabilities` gains `cast` and `cast-upload`; `castKinds` lists what opens right now (`youtube` only when
the YouTube TV app or a browser is installed; `url` always, because the launcher's own web view is the fallback);
`castUploadMax` = 64 MiB.

**`POST /v1/cast`** (`link/Cast.java` validates, `CastRouter` opens):

| kind | opens | how |
|---|---|---|
| `youtube` | YouTube TV app | `https://www.youtube.com/watch?v=<id>&t=<s>s` in `com.google.android.youtube.tv`, then `vnd.youtube:<videoId>?t=<s>` in it, then a browser; `409 no_app` if none |
| `video`, `audio` | `CastPlayerActivity` | Media3 ExoPlayer (MP4, MKV, WebM, MPEG-TS, MP3/AAC/FLAC/Ogg over http(s) or an upload). `live:true`: 0.5 s buffer (`DefaultLoadControl` 500/2000/250/500 ms), no seek, no "ended" |
| `image` | `CastViewActivity` | full screen on the theme background, decoded at most at screen size, title in a `> ` prompt header |
| `text` | `CastViewActivity` | a pane in the theme colours, JetBrains Mono 26 sp, scrollable with the D-pad, plain text only (no links/HTML) |
| `url` | the TV browser | `ACTION_VIEW` + `CATEGORY_BROWSABLE`; without a browser, the launcher's web view (`:web` process) |

Player keys: OK / play-pause toggles, left −10 s, right +30 s, up/down shows the pane, Back or Stop ends the cast.
Leaving the player (Home, another app, screen off) stops it: a cast never plays on behind the user's back. The screen
stays on only while playing (window flag; no wakelock). The pane shows the title, "from <PC>", a progress line in the
accent colour and the time, or "● live".

**`PUT /v1/cast/file`.** Before reading the body the TV checks that `Authorization` names a paired PC with a fresh
timestamp (strangers cannot make it write anything). The body is then streamed to `getCacheDir()/cast/.up-*.tmp`
while hashing. The signature is verified against that hash, and the file is renamed to `<sha256>` and answered
`{file, size}`. If the body is larger than `castUploadMax` the TV answers `413` without reading it. If less than
64 MiB would stay free it answers `507 no_space`. The cache keeps at most 8 files / 512 MiB (oldest first). A file
is deleted when its cast stops, and any upload older than 24 h is deleted at the next upload or home screen visit.

**`POST /v1/cast/control`.** `play`/`pause`/`seek`/`stop` on the player; `stop` closes the viewer and text card.
YouTube and the browser get media keys (`stop` on the browser goes back home). `409 nothing_playing` when no cast is open.

**`cast_state` events.** `opening` on accept, then `buffering`/`playing`/`paused`/`ended`/`stopped`/`error` on
every change, and `playing` with position every 10 s while playing. A new cast sends `stopped` for the old one.
Image and text report `playing` then `ended` once shown (nothing left to fetch), and `stopped` on Back.

**Security.** Only paired, signed requests; only the fixed intents above (no `Intent.parseUri`, no PC string in any
other intent; YouTube gets only the validated 11-character id). URLs must be http/https with a host. File ids must
be 64 hex characters, resolved only inside the private cache. The player and viewer activities are not exported.
Logs never contain a URL (the path is the cast token). The launcher has no PIN or parental lock to bypass.

**PC off.** Nothing new runs without a PC: no receivers, timers or threads. The 24 h sweep is a single directory
check on home resume.

**APK.** Release builds now use R8 (`minifyEnabled`, `shrinkResources`). Only `androidx.media3:media3-exoplayer`
1.4.1 is added (no `media3-ui`: the player UI is drawn in Omarchy style; no HLS/DASH modules, so `.m3u8`/`.mpd`
casts report `error`).

**Tests.** `LinkServerTest.castValidationUploadAndControl` and `castCacheSweep` run on the JVM.
`tools/link-e2e/run_pc_suite.py` now also runs omarchy-link's `test_cast.CastE2E` Link tests (fallback to Link,
kinds + upload, `--serve` big file, `--screen` live stream, old launcher + clipboard) against the real LinkServer.

## v2.7.1 — plain terminal prompt, custom prompt name

- **Prompt:** the home prompt no longer shows a greeting. It reads `<user>@<host>:~$` followed by a block cursor.
  - The cursor is one JetBrains Mono cell wide, in the theme foreground, after one space.
  - It blinks every 530 ms, like xterm/VTE, and only while home is resumed. The `> Apps` / `> Omarchy` / `> TV` cursors use the same rhythm.
  - Typing effects (menu headers, `> ask`, move hint, voice/search) are unchanged.
- **Prompt name:** Launcher → **Prompt name** / **Host name** opens the TV keyboard. Stored as
  `"prompt": {"user": "user", "host": "omarchytv"}` in omarchytv.json.
  - Input is lower-cased and reduced to `[a-z0-9._-]`, at most 16 characters; empty brings back the default.
- **From the PC:** `PUT /v1/theme` accepts an optional `"prompt": {"user", "host"}` (e.g. `$USER` and the
  hostname), sanitised the same way. PROTOCOL.md documents it (test_agents.test_theme_prompt).

## v2.8 — agent monitor (TV side of PROTOCOL.md §6 Agents)

The PC (omarchy-link) watches the AI agents running on it (Claude, Codex, Grok, Hermes, Grok Bot; names
and ids come from the PC, nothing is hard-coded) and mirrors them on the TV.

- **Link (LinkServer, plain Java, harness-tested):**
  - `status.capabilities` includes `agents`.
  - `PUT /v1/agents`: more than 8 agents → 400 `bad_agents`. The TV keeps ≤ 8 agents and ≤ 8 steps per agent,
    plain text (no control characters; step ≤ 72, question ≤ 160), and drops unknown fields (cwd, session…).
    The result is stored as `link.agents` + `link.agentsAt`. `GET /v1/agents` returns it (else
    `{"agents":[],"at":null}`). Rate limit: 2 per 5 s per PC.
  - Events: `agent_activity` (same bounds, kept in memory only, never written to disk at 2/s);
    `agent_ask_state` / `agent_answer` (≤ 1200 chars) / `agent_ask_error` are reduced to their fields.
  - The TV sends `agent_snapshot` when the pane or the view becomes visible.
  - `agent_ask` sends only `{id, agentId, text}`, with text 1–2000 characters, at most 1 per 3 s, and goes to the PC
    that reports the agents. Refusals on the TV (empty, busy, not connected) come back as a local `agent_ask_error`.
- **Pane `─ agents ─`** (toggle: Launcher → Agents pane; in the default list, but it only appears once a PC has sent
  agent data):
  - One line per agent: a glyph in the theme colour (thinking ● accent2, running ● accent, waiting ! yellow,
    idle ○ dim, done ✓ green, error ✗ red), name, current step, and time in that status (counted on the TV clock
    from when the data arrived, so PC clock skew doesn't matter).
  - **needs input** row: yellow on a yellow tint.
  - Hidden when the PC reports the monitor off or no agents.
  - Placed by the same fit as the PC panes: row 1 if it fits next to the wordmark, else row 2, else skipped.
  - Focusable: Hyprland active border; OK opens the agent view.
  - A PUT every 60 s updates it in place: no pane rebuild, focus is kept.
- **Agent view (`AgentActivity`):** one Omarchy window, terminal style.
  - Tabs (◀ ▶) with the agents.
  - Body: the agent's last steps scrolling live with times, the pending question with "Answer on your PC", and
    what was asked from the TV:
    - `you>`
    - `sys> Confirm on your PC` (awaiting_approval)
    - `sys> running…`
    - `<agent>>` with the answer typed out
    - errors in plain words (EN/IT/DE)
  - `> ` input line: keyboard, or the remote mic (system speech recognizer, same as `> ask`; no RECORD_AUDIO
    permission).
  - Input is enabled only when the PC says `canAsk` and the agent is done/idle. Otherwise the line says why: presence
    only (e.g. Grok Bot), answer on your PC, working, not connected.
- **PC off:** the pane says "not connected" (if this TV ever got agent data, else it stays hidden). Nothing is sent,
  no timer runs. The elapsed ticker runs once a second only while home (or the view) is in front and an agent is
  working.
- **Tests:**
  - LinkServerTest: `agentsPutIsBoundedPlainTextAndServed`, `agentAskRefusesLocally`, `agentTimersAndTimes`.
  - `tools/link-e2e/run_pc_suite.py` now runs `test_agents.OverLink` (4 tests) against the real LinkServer. JavaTV gained
    `seen`, `wait_event`, `agent_ask`, `agents()` and `broadcast("agent_snapshot")`.
- **Previews (HTML mock, sample data):** `(dev preview, not published) `v28-agent-view.png`,
  `v28-agent-question.png` (`(dev preview, not published) `(dev preview, not published).

## v2.8.1 — App size presets

- **Layout → App size:** large / medium / small / compact = **4 / 5 / 6 / 7 tiles per row** (OK steps through them).
  Stored as `"geometry": {"appSize": "large"|"medium"|"small"|"compact"}`. Missing or unknown values mean
  large (`R.integer.columns`, 4), so existing setups look the same as before.
- **Tile size:** the base 16:9 banner shrinks with the column count, keeping the same 14dp gutter between banners
  as Large: about 198 / 155 / 127 / 107dp wide (`AppAdapter.PRESET`). Tiles stay centred in their column, and the
  gaps and border follow Layout.
- **Tile scale:** still there, renamed **Tile fine adjust** (0.85–1.1). It multiplies the preset and is still
  capped to the column minus a 12dp gutter.
- **Rows that follow the size:** the dock (the first row: as many tiles as columns), the app grid, `> Omarchy`
  shortcuts and `> TV`. The TV tile is still one column plus one tile wide, so it spans 2 columns.
  - The fallback icon (apps without a banner) scales with the tile.
- **Fixed focus line:** the row height (`AppAdapter.rowHeightDp`) uses the same scale, so the dock placement and
  the fixed focus line move with the size.
- **Tests:** `AppSizeTest` checks that every preset fits its column and keeps 16:9.
- **Previews (HTML mock, `(dev preview, not published) `home26.html?cols=N`):**
  - `(dev preview, not published) 2×2, 4/5/6/7 per row.
  - `v281-compact-rows.png`: compact in the dock, app grid, `> Omarchy` and `> TV`, with the focus line shown.

## v2.8.2 — reboot check, state backup, optional Private DNS

Policy, unchanged: debloating uses `pm disable-user --user 0` only. No script uninstalls a system app, and every
step can be undone with `tools/restore.sh`.

- **`tools/backup-state.sh`** (read-only) saves a snapshot to `backup/state-<time>/` and copies it to
  `backup/state-latest/`. `backup/` is git-ignored because it holds your TV's own data. The snapshot has:
  - getprop
  - the package list and the disabled list
  - the resolved HOME activity
  - global/secure/system settings
  - `private_dns_mode` / `private_dns_specifier`, recorded only
  - memory: `/proc/meminfo` and the `dumpsys meminfo` totals

  Run it **before debloating**: it becomes the baseline for the checks below.
- **`tools/reboot-check.sh [debloat list] [--yes]`**: takes a snapshot, asks before rebooting, waits for
  `sys.boot_completed`, then waits 30 s so boot receivers have run. It then checks:
  - the HOME intent still resolves to `com.omarchytv.home`;
  - `pm list packages -d`, compared with the snapshot from just before the reboot and with `backup/state-latest`
    (or `BASELINE=`): anything re-enabled, such as the Sony/Google launchers or debloated apps, is reported;
  - every package in the debloat list is still disabled;
  - Private DNS is unchanged (read-only);
  - memory: a before-debloat / before-reboot / after-reboot table (`tools/mem-compare.sh`; MemAvailable is the
    fair number).

  It never fixes anything: it prints the diffs and the commands to fix them, and exits 1 if there are any.
- **In the launcher (`HomeCheck`)**:
  - At BOOT_COMPLETED there is one PackageManager lookup. If OmarchyTV is no longer the default home, it opens once
    with a gentle `> home` Omarchy menu card. On later resumes it asks at most once per boot.
  - The card offers: **Make OmarchyTV the home screen** (the system home settings or the HOME chooser; nothing is
    set by the app), **Not now**, or **Don't ask again**.
  - "Not now", "Don't ask again" and Back return to whatever home the TV has.
  - Nothing else runs at boot. It never enables, disables or changes anything.
- **`tools/private-dns.sh`** is optional and never run by any other script. Without a flag it only prints the current
  values.
  - `--private-dns <host>` (e.g. `dns.adguard-dns.com`): needs `ro.build.version.sdk` ≥ 28, asks first, and saves the
    previous values to `backup/private-dns-before.txt`.
  - `--restore`: puts those values back exactly (deleted if they were unset; `off` if there is no backup).
  - Leave it alone if Private DNS is already set (e.g. ControlD): the default flow only reads it.


## v2.8.3 — Sony home flow, targetSdk 34, crash watchdog, "Originale"

- **Home on Sony BRAVIA (Android 9).** The stock launcher's HOME filter has priority 2, so
  `set-home-activity` prints `Success` while Home keeps opening `com.google.android.tvlauncher`.
  `build-and-install.sh` now follows upstream cleanyourtv: install → `am start` OmarchyTV →
  disable the stock launcher (asks first; `CONFIRM_DISABLE_STOCK=y` to skip the prompt) →
  `set-home-activity` **last** → verify with `cmd package resolve-activity` HOME → if Home is not
  OmarchyTV, `pm enable` the stock launcher again and exit 1. Every command runs in its own
  shell call (`tv.py --batch`). Reboot check / restore unchanged. (`build-and-install.ps1` still
  has the old order: use the .sh on Mac/Omarchy.)
- **targetSdk 29 → 34** (minSdk 26). Upstream hit Play Protect `INSTALL_FAILED_VERIFICATION_FAILURE`
  with 29. Audit: every component already declares `exported`; no foreground services (the Link
  keep-alive is a plain started service); runtime-registered receivers only take system broadcasts;
  Link/cast use plain sockets and app-private storage (`getCacheDir()/cast`, `getExternalFilesDir`),
  no WebView cleartext needs. Android 9 itself does not apply the 31–34 behaviour changes.
- **Crash watchdog.** Crashes are recorded (`files/launcher-crashes.txt`); on the 3rd within 5 min a
  service in its own process (`:watchdog`) turns off the `> home` prompt (stop forcing OmarchyTV) and
  puts the stock home back through "Originale" if set up, else opens the system home picker.
- **"Originale"** (Settings → Launcher). One press: stock launcher re-enabled + selected as home,
  `screensaver_components` set to the system default (its package enabled only if it was off), our
  values saved. Press again: what it enabled is disabled again, our home set last, verified, reverted
  if not ours. Uses the TV's own ADB on 127.0.0.1:5555 (LocalAdb, port of Free My TV): **opt-in**,
  needs Developer options → Network debugging left on and "Allow debugging?" accepted once on the
  TV. Fixed commands only (pm enable / disable-user --user 0, set-home-activity, screensaver setting);
  never DNS, never uninstall. Security trade-off: while network debugging is on, port 5555 is open on
  the LAN (each new client still needs on-screen approval). Without it the toggle opens the home
  picker and points to `tools/restore.sh` — a plain app cannot re-enable a disabled launcher.
- `examples/sony-kd55xe9005/debloat.txt`: a tested 27-package example list (default for this TV).
- Credits: see root `THIRD-PARTY.md` and `NOTICE` (`Copyright (c) 2026 Marco Porro (x.com/MarcoPorro · www.marcoporro.it)`, MIT; Free My TV MIT).
