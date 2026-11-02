# Changelog

## [Unreleased]

### OmarchyTV Arcade: three games of our own (draft)

- **OmarchyTV Arcade** on Android TV, LG and Samsung: a games room with a light-bulb marquee and cartridges (insert one and the channel changes), high scores, pause, in the active theme's colours and inside the Retro CRT set when that plugin is on. Arrows, OK and Back only, so nothing leaves the launcher. All text-mode and light, for old TVs too.
- **V-HOLD**: run through a terminal while the old TV falls apart: the picture rolls, leans, loses its colour or collapses into a line. Turn the blinking knob with ◀ ▶ until it locks, slap the set with OK OK. The more is broken, the more each metre is worth.
- **RICE WARS**: you only see the bugs of the theme you are ricing. OK re-themes the whole screen with a real Omarchy theme; every third level the BLOATWARE boss changes theme on you. Optional: a cleared level re-themes the Omarchy PC too (off by default; the PC's theme comes back when you leave).
- **FORK BOMB**: OK forks every process, the arrows move them all together; lead them out before the OOM killer, which hunts faster the more of you there are.
- Sound effects synthesised live (on by default, "Arcade sound"); every string in 23 languages.

### Sounds: OmaFire and the boot screen (draft)

- **OmaFire sound** (Wallpaper settings, off by default: with it off you get only the animated wallpaper): Wes Grimes' own sounds and mixing, as in his plugin: the embers, crackle and roar loops follow the fire's heat, a log and a stoke land when the fire is fed, and crickets and frogs call across the lake. Only while Home is on screen.
- **Boot sound** (Launcher settings, on by default, quiet): the CRT's power-on thump and degauss hum, the POST beep, the memory count, a disk seek per systemd line, keystrokes as `omarchy-tv` is typed, and a whoosh with a soft chord when the wordmark appears. Synthesised live, no audio files; the same cues on Android TV, LG and Samsung.

### Live screensaver, Retro CRT set, liftoff in every language (draft)

- **Live screensaver** (Screensaver settings, off by default): a screensaver of its own, no art under it. Big glowing block clock, an animated ASCII sky (rain falls, the sun's rays turn, snow, storms), the PC's notifications, X mentions, GitHub, the PC's CPU/RAM bars and a turning ASCII radar, a ticker and a status line, on dark glass panels so everything stays readable. Behind them: theme-coloured glyph rain, a synthwave grid rolling in at the bottom and a refresh sweep. Every new notification or mention crashes in as an INCOMING banner across the bottom (shake + decrypt, never over the boxes), then settles in its box. Light: half-resolution backdrop, redraws only what changed, pauses when hidden, drops the rain on slow TVs by itself; drifts a few pixels a minute against burn-in.
- **Omastorm radar in the Live screensaver** (its own switch, off): every 90 s the PC takes a small snapshot of the real Omastorm window (it opens it if needed) and the TV shows it in the weather box.
- **Retro CRT, now unmistakable:** Home shrinks into a CRT set: dark body, a glowing rim in the theme's accent, an OMARCHY TV badge with a power LED, deeper scanlines, phosphor mask, vignette and a glass highlight; "strong" adds phosphor bloom and colour fringing (and the curved tube on Android 13+).
- **Liftoff in the TV's language:** title, the update's stages (downloading, checking, signing, installing, starting) and the exit code, in all 23 languages.

### Retro CRT, OmaFire wallpapers, Omastorm on the TV, livelier Living screensaver (draft)

- **Retro CRT plugin** (Plugins: off / light / strong): scanlines, phosphor mask, vignette, a gentle flicker and a thin rounded bezel in the active theme's colours (it follows every theme change). "strong" on Android 13+ also curves the picture like a tube.
- **OmaFire animated wallpapers** (Wallpaper: "OmaFire · campfire" / "OmaFire · embers"): Wes Grimes' OmaFire campsite (MIT) exactly as in his Omarchy bar plugin, the fire and the moonlit lake, full screen; kept burning (campfire) or low (embers). Runs only while Home is on screen.
- **Omastorm on the TV** (Plugins, Android TV, off by default): the real Omastorm radar window from your Omarchy PC (Wes Grimes, MIT), unchanged, streamed to the TV and driven by the remote (pan, frames, play, Pixels/Glyphs/Stipple, mosaic, aviation…). Needs Omastorm installed on the PC. LG/Samsung: next step (native view).
- **Living screensaver:** the boxes are typed in line by line and every new line decrypts in like the tte effects, then glows; colours from the active theme.

### Living screensaver and the X app (draft, Android TV + LG webOS + Samsung Tizen + PC)

- **Living screensaver** (Screensaver settings, off by default): next to the screensaver art, Omarchy-style ASCII boxes with the weather (ASCII sky), your GitHub activity (pushes, PRs, stars and forks on your repos), **every notification from the Omarchy PC** (its own switch, off by default) and **X mentions**, live while the screensaver runs. The newest item blinks when it arrives.
- **X on the screensaver:** off / "PC notifications" (free: X notifications your browser shows on the PC) / "X API" (opt-in, X charges per use: `omarchy-link x setup` stores your token on the PC only, polled only while the screensaver runs, only new mentions, stops at `xMonthlyUsd`, 1 USD a month by default).
- **X app:** x.com in OmarchyTV's own web view (nothing extra, so it stays light) with the remote mapped to X's shortcuts on Android TV: ▲▼ posts, OK opens, 1 like, 2 reply, 3 repost, 4 new post, 5 search, 0 home, 9 notifications, 8 messages (never while typing); videos wait for OK. In Walker as "X (x.com)"; on LG/Samsung first in Web apps (TV browser).
- Protocol: `living_prefs`, `living`, bridge `POST /v1/living`. Tests: `test_living.py` (incl. the D-Bus notification parser), `test_webos.py`, `webos/tests/living.test.js` (Android's copy of living.js must be identical).

### TV and PC together, updates from the TV, liftoff (draft, Android TV + LG webOS + Samsung Tizen + PC)

- **TV and PC power together** (Launcher settings, off by default). Android TV: turning the TV on wakes the Omarchy PC (Wake-on-LAN; the PC tells the TV its address over Omarchy Link), turning it off suspends the PC after 60 s, unless the TV comes back on or a build is running. LG: the PC follows the TV into sleep (needs "TV control" approved); waking from LG/Samsung is not possible, TV web apps can't send Wake-on-LAN. Samsung: not available. PC side: `powerFromTv`, `powerGraceS`.
- **Settings on/off:** Boot screen (at start / start and wake / off), "Also change the PC theme" and the power switch are now in Launcher settings (Android) and All OmarchyTV settings (LG/Samsung).
- **Update OmarchyTV (Android TV):** checks the official GitHub releases, and when a newer `omarchytv-release-<version>.apk` exists asks, downloads it, checks the release checksum, the package and that it is signed with the same key as the installed launcher, then hands it to Android's installer (Android asks to confirm; the first time it asks to allow installs from OmarchyTV).
- **Update via PC (LG/Samsung):** the TV asks the PC (`omarchy-tv webos-serve`); the PC downloads the newest official `.ipk` / `.wgt`, checks it and installs it: `ares-install` on LG, signed with your own Samsung certificate profile (`tizenProfile`) and `tizen install` on Samsung. PC side: `updateFromTv`.
- **Liftoff on the big screen:** `omarchy-link liftoff -- <build command>` and every update show an Omarchy-style rocket countdown on the TV: T-minus from the last run of the same build, stage and progress, LIFTOFF when it worked, a red blinking ABORT with the last lines and an alarm when it fails. Any key hides it.
- Protocol: events `pc_wol`, `tv_power`, `liftoff`; bridge `POST /v1/update/tv`, `POST /v1/power`, `liftoff` in `/v1/status` (PROTOCOL.md). Tests: `test_liftoff_power.py`, `test_tvupdate.py`, `test_webos.py`, `LiftoffScriptTest`, `webos/tests/liftoff.test.js` (Android and LG/Samsung checked against the same cases).

### Theme sync both ways (draft, Android TV + LG webOS + Samsung Tizen + PC)

- **Change the theme from the remote and the PC follows.** New switch in Themes: **"Also change the PC theme"** (off by default). With it on and a PC paired, picking a theme on the TV also runs `omarchy-theme-set` on the Omarchy PC; the PC then sends its palette back as usual, so TV and PC stay the same.
- **Safe by design:** the PC only accepts names of themes installed in `~/.config/omarchy/themes` (no shell, no paths), at most one switch every 2 s. A theme the PC doesn't have changes only the TV (LG/Samsung show "Not on the PC: only the TV changed"). The PC can refuse it entirely with `"themeFromTv": false` in omarchy-link's `config.json`.
- Android sends the new `theme_set` event over Omarchy Link; LG/Samsung call `POST /v1/theme/set` on `omarchy-tv webos-serve` with the same code as Grok Bot. Documented in PROTOCOL.md. Tests: `omarchy-link/tests/test_theme_set.py`, `omarchy-tv/tests/test_webos.py`.

### Twenty new languages (draft, Android TV + LG webOS + Samsung Tizen)

- **OmarchyTV now speaks 23 languages.** New: French, Spanish, Portuguese (Portugal and Brazil), Dutch, Polish, Swedish, Danish, Norwegian (Bokmål), Finnish, Czech, Hungarian, Romanian, Greek, Turkish, Ukrainian, Russian, Arabic, Chinese (Simplified) and Japanese, on top of English, Italian and German. Every launcher string on Android TV (about 400) and on LG/Samsung (about 85, plus weather, days and months) is translated.
- **Language menu:** Launcher settings → Language cycles Auto → English → Italiano → … → 日本語, each shown in its own name. "Auto" follows the TV's language (Brazilian vs European Portuguese, any Chinese → Simplified, "no" → Bokmål).
- **Dates:** Chinese and Japanese show "11月2日 周一"; Hungarian, Chinese and Japanese use their own date order on Android.
- **Arabic** is right-to-left text in the same terminal layout (the screens are not mirrored).
- **Voice and Grok Bot follow the language:** speech recognition and spoken replies use the matching voice (fr-FR, pt-BR, ja-JP …). On LG/Samsung, "Auto" now also follows the TV for voice (before it always used English unless a language was picked).
- The new languages are machine-translated from English: corrections from native speakers are very welcome (see CONTRIBUTING.md). Tests check that every language has every string with the same placeholders (`TranslationsTest`, `webos/tests/i18n.test.js`).

### Boot screen (draft, Android TV + LG webOS + Samsung Tizen)

- **Omarchy TV boot screen.** When OmarchyTV starts, the screen powers on like a CRT (a bright line that opens to full screen), runs a 90s BIOS POST with an OTV block badge top right, then systemd lines as Arch prints them, auto-logs in, types `omarchy-tv`, draws the OMARCHY TV block wordmark row by row (as on Home) and fades to Home. About 7 s; any key skips it and no key reaches Home while it plays. Theme colours, Caskaydia font, light scanlines and glow.
- **Real data from your TV:** launcher version, TV model, RAM (counting up), display mode, active theme, number of apps. The network and omarchy-link lines really wait for the TV while their `[ *** ]` spinner turns (up to 2.5 s): `Connected to Wi-Fi "<name>", <ip>.` / `Connected via Ethernet, <ip>.` and `omarchy-link: connected to <PC name>.`; if the answer doesn't come in time the line shows `[ WARN ]` with what is known. On webOS/Tizen the network line says online/offline (the web app can't read the Wi-Fi name or IP) and the model is the real one when the TV exposes it. The ads and Couch lines are jokes.
- **Settings:** `bootScreen` = `"boot"` (default: once per TV boot on Android, at app start on webOS/Tizen), `"wake"` (also when the TV comes back from standby) or `"off"`; `bootFx` = scanlines and glow (default on). Android: omarchytv.json; webOS/Tizen: saved settings `cfg.bootScreen` / `cfg.bootFx` (no menu entry yet).
- One script on all platforms: `launcher/.../BootScript.java` and `webos/app/boot.js` (Tizen packs the same file). Tests: `BootScriptTest`, `webos/tests/boot.test.js` (also checks both platforms say the same lines with the same timings, and that the block font matches `BlockFont.java`).

### LG webOS / Samsung Tizen 2.13.11 (draft, not released)

LG and Samsung only. Android TV is unchanged in this build: Android users stay on the current Android release (2.13.8); Android work resumes separately.

- **Weather: search any city (#88).** "Search city…" now uses what you type: it asks Open-Meteo's geocoding (no key, the same service and query as the Android launcher) and lists up to 6 matches. A curated list of 58 cities (world capitals and key APAC cities, plus the old 5) filters as you type and stays as the offline fallback.
- **Pair the PC you type (#5, point 4).** In "Pair Omarchy Linux PC", a valid typed `host[:port]` adds "Connect to …" as the first item. The old items are still there.
- **No placeholder data (#5, point 10).** The terminal pane shows OmarchyTV's real uptime (no fixed +42 min) and online/offline instead of a fixed "Wi-Fi"; the AI pane shows "—" instead of made-up amounts when the PC doesn't send them.
- **Screensaver fixes by @OldJobobo:** block art without glyph gaps ([#98](https://github.com/piermark/omarchytv/pull/98)), text effects across the full screen ([#100](https://github.com/piermark/omarchytv/pull/100)); top-bar clock vertically centred ([#99](https://github.com/piermark/omarchytv/pull/99)).
- **Docs:** webOS 25 reported working on an LG 55QNED80T6A by @Imacx-maria (#95).

### Earlier unreleased notes

- **Top bar focus loops.** ◀ on the menu logo goes to the last icon, and ▶ on the last icon goes to the menu logo (the floating companion, when shown, is still the stop after the last icon). Hidden items are skipped as before.
- **Home widgets keep one column.** With only one top-row widget on, it no longer stretches across the whole row.
- **webOS 4.0: dock and grids lay out in a row (#72).** compat.js now also rewrites the stylesheet when CSS grid is missing (Chrome 53), not only when CSS variables are; the stage is re-fitted after load for engines that report the window size late.
- **Tizen 5.5: HDMI inputs open the HDMI source (#87).** On Tizen 5.5 and older, selecting HDMI hides OmarchyTV instead of launching the TV viewer, which restored Samsung TV Plus. TV tuner and Tizen 6+ keep the viewer hand-off.

## [2.13.8] — 2026-10-31 (`versionCode 25`; webOS 2.13.8, Tizen 2.13.8)

Final 2.13.8 release for Android TV, LG webOS and Samsung Tizen. It is the `2.13.8-testing2` build that I tested on my Sony BRAVIA, with only the version number changed.

- **Grok Bot Direct mode, with the PC off.** When no PC is linked or the PC can't be reached, the TV asks your own Grok Bot webhook routine and gets the reply back through ntfy.sh on a random `otv-…` topic. Your webhook URL and key stay on your TV. Guide: [docs/GROKBOT-DIRECT.md](docs/GROKBOT-DIRECT.md).
- **Fix: the in-app mic.** Direct mode listens with the in-app SpeechRecognizer, so the words you say on Android 9 (Sony) now reach the chat. The old recognizer intent is the fallback.
- **Fix: video playback.** `play_video` plays the video the routine sends. When it only has a title or query, it becomes a YouTube search (`search_youtube`).
- **Spoken replies in your language.** The reply's `lang` picks the voice language.
- **Optional "Grok voice (xAI)"**, off by default: it reads replies with xAI's text-to-speech and falls back to the system voice on any error or after 8 s.
- **The Blue key launches Grok Bot.** The docs now explain PC mode (Tom Ballard's Grok Bot Desktop Companion on the PC, unchanged) vs Direct mode.
- **Lights / Ambilight tested on a Sony BRAVIA:** OmarchyTV finds the Philips Hue hub on the LAN, syncs the lights with the theme colour, and turns them off when you leave the launcher. How it works and how to connect it: [docs/LIGHTS.md](docs/LIGHTS.md).

### Testing builds that led to 2.13.8

Testing build `2.13.8-testing2` (`versionCode 24`, Android only): Grok Bot diretto now listens with the in-app SpeechRecognizer (`RECORD_AUDIO`, asked at first use) and shows "🎤 Listening…" with partial results. On Android 9 (Sony), Google's recognizer activity heard the words but never returned them. The old intent is still the fallback. On all three platforms, the reply's optional `lang` picks the voice language. A `play_video` with only a `query`/`title` becomes a YouTube search, `open_app` accepts `app` as an alias for `name`, and the chat shows a line when an action is dropped.
- Lights: tested on real hardware on Oct 31, 2026 (Sony BRAVIA 4K GB, Android TV 9, 2.13.7-testing). OmarchyTV finds the Philips Hue hub (bridge) on the LAN automatically and the lights sync to the active theme's colour; theme colour while the launcher is on screen, off when leaving OmarchyTV ("Quando esco dal launcher"). No other TV models tested yet.
- Grok Bot diretto: optional "Voce di Grok (xAI)" (off by default) on Android TV, webOS and Tizen; falls back to the system voice on any error or after 8 s.

Testing build `2.13.8-testing` (`versionCode 23`; webOS 2.13.8, Tizen 2.13.8).

- **Grok Bot diretto (senza PC)** on Android TV, LG webOS and Samsung Tizen. With no PC companion linked, the TV asks your own Grok Bot webhook routine and gets the reply back through ntfy.sh on a random `otv-…` topic. It shows and speaks the reply (a "Leggi risposte ad alta voce" toggle, on by default) and runs one action: `play_video`, `open_app` or `search_youtube`. Local app commands still run first. The PC companion, Ask and its agents are unchanged. The webhook URL and key stay on the TV, and the key is masked. Guide: [docs/GROKBOT-DIRECT.md](docs/GROKBOT-DIRECT.md). Privacy: anyone who knows the topic can read the replies on ntfy.sh.

Testing build `2.13.7-testing` (`versionCode 22`; webOS 2.13.7, Tizen 2.13.7). It is the first compiled build of the 2.14 work below. All 110 JUnit tests and 72 node tests pass.

- **Grok Bot is now OmarchyTV's default assistant, built on Tom Ballard's Grok Bot Desktop Companion v0.1.0.** Thank you, Tom ([@tcballard](https://x.com/tcballard)), for the companion and for the MIT licence that lets me bring it to the TV. I built against his tag `v0.1.0` (commit `a2efb00`).
  - **webOS and Tizen:** the companion in a corner now draws Tom's own avatar: his 14 shapes, his 11-colour palette and his capsule eyes. I vendored his `qml/shapes.js`, `palette.js` and `face.js` unchanged (MIT header) into `webos/app/vendor/grokbot/` (regenerate with `webos/tools/vendor-grokbot.py`). It bobs, blinks (sometimes twice), glances around, falls asleep after two idle minutes and drifts a few pixels to avoid burn-in. Settings: shape, colour (or follow the PC bot), size, corner, name badge, animation, agent, and on/off.
  - **Ask it from the TV:** Blue opens the chat, holding Blue opens it by voice (Web Speech API where the TV has it). On Tizen, the remote's Search/voice key opens it when the TV lets apps register that key. On webOS the Magic Remote mic stays with the system. Replies come from the Grok Bot on your PC through a new bridge endpoint (`POST /v1/grokbot/ask` on `omarchy-tv webos serve`, protected by an 8-character ask code printed by the bridge). The chat keeps per-bot history and shows an unread dot. You can switch bots from the PC roster. grok.com stays available as the other agent and as the fallback.
  - **Android TV, same avatar as webOS/Tizen:** the corner companion and the chat header now draw Tom's 14 shapes, 11-colour palette and eyes, with his poses (thinking while it waits, happy or alert on the reply). It bobs, blinks (sometimes twice), glances around, sleeps after two idle minutes (any key wakes it) and drifts a few pixels against burn-in. An unread dot shows replies and PC bubbles that arrived while the chat was closed. Settings → Grok Bot gains shape, colour (or follow the PC bot), size, name badge, animation and agent (Grok Bot or Ask). The geometry is generated from the same vendored files (`launcher/tools/gen-grokbot-avatar.py` → `GrokbotAvatarData.java`, MIT header kept), and `GrokbotAvatar.java` ports his `face.js`. `launcher/tools/test_grokbot_avatar.py` checks the data and the Java face math against his JS. The home tile keeps the orb unless it mirrors a PC bot.
  - **Android TV:** the mic/Search key now opens the companion by voice by default (`"voiceKey": "companion"`). `"launcher"` (Walker) and `"assistant"` (Google Assistant) are still one click away in Settings → Assistant. `VOICE_COMMAND` and `SEARCH_LONG_PRESS` now reach it too. The chat keeps the last 30 lines per bot. Ask and its AI providers (Claude Code, Codex, Hermes and the others) are unchanged.
  - **Everywhere:** "open Netflix", "apri YouTube" or "starte Spotify" opens a matching installed app right away, with fuzzy matching on the app names. When a reply contains a link you get a button for it: YouTube links play in the YouTube app (or in Cinemarchy's embed when that's on), other links open in the browser, and nothing opens until you press OK.
  - **PC (`omarchy-link`):** built against his v0.1.0 tag. It handles his exit code 10 (the webhook accepted the question and the answer arrives on the PC companion later; the TV says so), reads `grokbot-desktop.json` (webhook or agent dispatch), uses his new palette, returns the links in each reply, and adds `sync_roster()` for his `bin/grokbot-sync`. Tests for all of it.
- **Fix (Android TV): the home app row now follows Layout → App size.** The v2.x home row always showed 6 tiles; it now shows 4 / 5 / 6 / 7 (large / medium / small / compact), as the menu label says. Tiles stay 16:9, the app window gets lower with more per row, and the grokbot box stays just above it. ◀▶ still steps tile by tile and scrolls to apps past the edge. Test: `AppSizeTest.homeRowFollowsAppSize`. Thanks, @optip ([#67](https://github.com/piermark/omarchytv/pull/67)).
- **Fix (Android): ◀▶ in the top bar no longer gets stuck on the last workspace when some are hidden.** Hidden workspace slots are skipped, so with 4 workspaces ▶ from "4" now reaches the right-hand icons (Cinemarchy, Wi-Fi, Bluetooth, volume, inputs, screen). The 🚀 SpaceX TV link is now reachable with the D-pad too, between the workspaces and the icons, when the plugin is on. No wrap-around at the ends, as before. Test: `BarFocusTest`. Thanks, @optip ([#68](https://github.com/piermark/omarchytv/pull/68)).
- **Remote panes over LAN http:** `remotePanes.url` in `omarchytv.json` now also accepts `http://` to a literal private or link-local address (10/8, 172.16/12, 192.168/16, fc00::/7, link-local), the same rule as the web tiles, e.g. Node-RED at `http://192.168.1.20:1880/omarchytv/panes.json`. Host names and public addresses over http are still refused; HTTPS works as before. The rule is checked when the config is read and again before each fetch (`RemotePanes.urlAllowed`, `LanHttpDataSource.lanHost`, `RemotePanesUrlTest`). Thanks, @optip ([#69](https://github.com/piermark/omarchytv/pull/69)).
- **Android TV: the Blue key opens the Grok Bot companion chat, as on webOS/Tizen**, for remotes whose Menu key never reaches apps (Sharp's Tools key). If `"assistant": {"key": "blue"}` gives Blue to Ask, Ask keeps it. Test: `CompanionPlacementTest`. Thanks, @andersonleite ([#65](https://github.com/piermark/omarchytv/pull/65)).
- **Fix (webOS/Tizen): Weather → "Automatic (from IP)" really locates the TV now** instead of always setting Alessandria. It asks ipapi.co, then ipwho.is (the same services as Android, no key), keeps only the city and coordinates, and keeps your current city if both fail. Test: `webos/tests/geo.test.js`. Reported by @Gybra ([#5](https://github.com/piermark/omarchytv/issues/5)).
- **Fix (Android, Fire OS): the HomeOverride home button no longer gives up after two Home presses.** Fire OS sends two window events per press; events for the same home activity within 1 s now count once, and only a takeover that actually starts counts against the loop guard (4 in 15 s), so a burst of stock-home events no longer keeps it tripped. Test: `TakeoverGuardTest`. Reported by @podzpau ([#58](https://github.com/piermark/omarchytv/issues/58)).
- **webOS/Tizen app on other TV browsers:** after the Vestel/Novatek field report by ekollof1 ([#66](https://github.com/piermark/omarchytv/issues/66), GitLab #4), a platform script loaded before `app.js` can now set `window.OMARCHY_LAUNCH(app)` (its own app launcher), `window.OMARCHY_PREFERS_24H` (what Clock "auto" means when the browser says `en-US`) and `window.OMARCHY_FIT_SCREEN` (size the stage from `screen / devicePixelRatio` when `innerWidth` ignores the real viewport). None of them is set on webOS or Tizen, so nothing changes there. Test: `webos/tests/hooks.test.js`.

### Fixed (Sony BRAVIA / Android TV report)
- Cinemarchy YouTube: "Play DHH inside YouTube embed" is now on by default (it works on Sony; an explicit off stays off). Videos go to the YouTube TV app (watch URL, then `vnd.youtube:`), the phone app, SmartTube, any handler, then the in-app embed, then the WebApp. Queued, PC-library and "From PC" YouTube links no longer go to the video player.
- Video player: a YouTube or googlevideo source that answers HTTP 4xx (expired signed stream, 403/410) opens the YouTube app when the video id is known. Every other error now shows a readable, localized message instead of `io_bad_http_status`.
- YouTube embed: a WebView that fails to start, fails to load, or loses its renderer hands off to the YouTube app instead of crashing.
- Grok Bot companion: ▶ past the last top-bar icon selects the floating face (ring + "OK: chat") and OK opens the chat. The Blue key still opens it directly. The duplicate "companion · ask" home tile only shows when the floating companion is off.
- Top bar: the SpaceX TV pill is centred in the same 44 px row as the Cinemarchy and Wi-Fi icons, and ◀▶ reach it.
- webOS/Tizen: the YouTube embed is on by default, and without a YouTube app the video opens in the embed, then the browser, instead of a toast.

### Fixed (web apps on Android 9+)
- SpaceX TV "Launches & Streams", Euronews Live and the other web apps no longer crash on Sony BRAVIA (Android 9). The `:web` process now has its own WebView data directory (`WebView.setDataDirectorySuffix`, set in the new `OmarchyApp`), so it no longer clashes with the WebView used by the YouTube embed (crbug 558377). Web app cookies and logins persist in that directory, but logins saved before this change have to be entered again once.
- If a WebView still can't start, the web app shows a toast and opens the page in the browser instead of crashing.
- Cinemarchy entries that point to a direct stream (`.m3u8` HLS or plain media) play in the native player. The WebApp is kept for web pages.

### Added (themes)
- Four MIT themes by Erik Johansson (@erikrjohansson): Neon Glow (7 neon wordmark wallpapers), Norrsken, Green Mosaic and Blue Mosaic, on Android, webOS and Tizen. That makes 32 bundled themes. The wallpapers are WebP at 1920x1080, which adds about 0.4 MB per package. I reviewed the other community themes, but their artwork is not clearly MIT (SpaceX/Audi photos, Unsplash, uncredited art, GPL), so I left them out. They are listed in THIRD-PARTY.md.

### Added (wallpaper)
- New "Always-animated wallpaper" setting, off by default. When it is off, the animated wallpaper plays once and then settles on the still image, as before. When it is on, it loops while Home is on screen and pauses behind video, Cinemarchy, the screensaver and other apps. Android: Settings → Wallpaper. webOS/Tizen: All settings.

### Changed (lights hub)
- Hue / Home Assistant lights now carry the active theme's colour only while OmarchyTV is on screen. Leaving it, whether for another app (Netflix, the YouTube app), screen off or standby, applies the new "When leaving the launcher" setting: Turn off (default), Restore previous (the state read before OmarchyTV painted them; off when none was read) or Keep on. On Android, Cinemarchy, the player and the other launcher screens count as inside, and a 2.5 s debounce covers the hand-over between them. On webOS/Tizen it follows visibilitychange, pagehide and relaunch. Nothing happens when Ambilight is off, and the Philips Ambilight TV LEDs are unchanged.

## [2.13.0-beta] — 2026-10-29 (pre-release, `versionCode 21`)

Artifacts: `omarchytv-release-2.13.0-beta.apk` (Android TV), `omarchytv-webos-2.13.0-testing.ipk` (LG webOS), `OmarchyTV-tizen-2.13.0-beta-unsigned.wgt` (Samsung Tizen, unsigned testing), `omarchytv-mobile-2.13.0-beta.apk` (phone), `omarchy-tv-pc-plugin-2.13.0-beta.tar.gz` plus separate `omarchy-tv` and `omarchy-link` tarballs, and `SHA256SUMS`.

- **PC plugin update (2.13.0-beta tarballs refreshed): Send to TV (Cinemarchy) in the Omarchy menu:** I added a "Send to TV (Cinemarchy)" row to the Omarchy menu (`Super+Alt+Space`, Omarchy 4.5+). It opens a file picker (zenity, kdialog or yad, otherwise a list of your newest videos in the Omarchy menu), finds a subtitle file with the same name (`.srt`, `.vtt` or `.ass`, which I convert to SRT) and runs `omarchy-tv send`; the result arrives as a notification. `share/install-file-manager.sh` adds it and `--uninstall` removes it, or use `omarchy-tv menu-entry install | remove | status`. I only touch my own `omarchytv-send` id in `~/.config/omarchy/extensions/omarchy-menu.jsonc`: your other entries stay, a `.bak` is kept, and a file that does not parse is left alone. Only the PC plugin changed.
- **README:** new "See it in action" section with an Aquarium GIF, a short clip with audio, and Android TV, webOS and Tizen screenshots (`docs/media/`).
- **Fix (webOS/Tizen): the Companion chat no longer opens the TV keyboard on open.** Focus starts on 🎤 Speak (or Send when the TV has no voice); the keyboard opens only when you move ▲ onto the text field. Other menus with a text field (Walker, city, PC address) still focus the field right away.
- **Tizen:** the page title now says Tizen instead of webOS.
- **Mobile 2.13.0-beta (`versionCode 21300`):** the TV address field accepts `IP:port` for Android 11+ Wireless debugging, a clearer message when the TV asks for ADB TLS, and the updater looks for `omarchytv-release-2.13.0-beta.apk`.
- **NOTICE:** credits brought in line with THIRD-PARTY.md (Last Horizon "Eyes Wide" permission from Erik Johansson, Tokyo Roadster wallpapers by Tom Ballard, Grok Bot ball mark, SpaceX TV plugin, Aquarium homage).

- **Cinemarchy 0.1.0-alpha (preview):** Cinemarchy now carries its own version inside OmarchyTV v2.13.0-beta, shown as an ALPHA badge next to the wordmark on Android, webOS and Tizen. It's an early preview: if something breaks or you'd like a feature, I'd love your feedback in a GitHub issue. — Marco

- **@dhh37 in Cinemarchy, live from the channel feed:** the old @dhh37 rows only opened the YouTube channel. Cinemarchy now reads DHH's public uploads feed (`youtube.com/feeds/videos.xml?channel_id=UCUkM9uMpWatT7gVWShgtKFw`) on open and every 6 h, caches it, merges it with a curated list of real uploads and marks videos since the last visit `NEW`. Offline it shows the cache or the curated list. Links and metadata only; nothing is downloaded. Android (`DhhFeed.java`) and webOS/Tizen (`webos/app/dhh.js`, new `[1] @dhh37` tab; the fake "Continue: Blade Runner…" rows and fake snippets are gone). Tests against a saved sample feed (`DhhFeedTest`, `webos/tests/dhh.test.js`).
- **Optional "Play inside Cinemarchy" (default off):** the official YouTube IFrame embed (`YoutubeEmbedActivity` on Android, an iframe overlay on webOS/Tizen) with a chapter rail (Start/25/50/75 %, ±30 s, keys 1-4) via `seekTo`. When YouTube refuses the embed (errors 101/150/153 and others) or it does not start in 20 s, it opens the YouTube app instead. No stream extraction. Toggle: Settings row, `:embed on|off`, or Cinemarchy `[4] Player & tools`; config `cinemarchy.embedPlayer`.
- **Grok Bot Companion (TV), experimental:** a floating bot in a launcher corner (mirrored shape and colour from the PC, idle blink, a 4 dp bob). It never takes D-pad focus by itself: on Android long-press Menu arms it, OK opens the chat, any other key disarms; on webOS/Tizen the Blue key or a click opens it. Settings: on/off, corner, offset (`plugins.companionOverlay`, `companionCorner`, `companionOffsetX/Y`). The chat panel takes the on-screen or a Bluetooth keyboard and, on Android, voice through the system speech recogniser. Answers come from Tom Ballard's companion through omarchy-link when the PC companion is present, otherwise from my Ask screen. On webOS/Tizen there is no omarchy-link channel, so questions open grok.com in the TV browser; voice there only works if the TV's web engine has the Web Speech API, otherwise keyboard only.
- **Grok Bot ball mark:** the floating companion, the chat header and the home tile now show Grok Bot's round orange ball with capsule eyes that blink and narrow when idle, in the active bot's colour when bridged. Redrawn from Tom Ballard's MIT `grokbot-desktop.svg` (credit in THIRD-PARTY.md).
- **Fix: Android crash when the wallpaper intro stops** (`stopWallpaperIntro` re-entered from the TextureView callback during `removeView`).
- **webOS build script:** `build-ipk.sh` reads the version from `appinfo.json` instead of a hard-coded 2.11.0.
- **PC bridge to Tom Ballard's Grok Bot Desktop Companion (experimental, tracks @tcballard's in-development branch):** `omarchy-link` detects his companion, mirrors the selected bot to the TV tile, relays TV questions over his IPC and returns the answers. Written against his v0.1.0: the version is read from his `manifest.json` or `VERSION`, and both the current branch and v0.1.0 are accepted. `OMARCHY_GROKBOT_DESKTOP_REF` re-points it to the `v0.1.0` tag when it is published. Tests use mocked IPC.
- **webOS/Tizen fixes:** placeholder text removed (fake SpaceX countdown, "approved on X" line in the companion dialog); `appinfo.json` version 2.13.0; Cinemarchy digit keys 1-4 now work; feed titles are HTML-escaped; Tizen `config.xml` gains `<access>` and the internet / application.launch privileges.
- **Android polish:** the Companion chat no longer pops the on-screen keyboard on open (focus starts on 🎤 Speak; the keyboard opens when you select the text field; typing on a Bluetooth keyboard jumps into the field). The fake "Starship · T-02:14:00" countdown in the top bar is gone: the SpaceX TV plugin link now just reads "🚀 SpaceX TV".
- **Fix:** Grok Bot rows on Android passed the question to Ask under the wrong extra, so it arrived empty.
- **A personal homage:** I made the ASCII Aquarium as my personal homage to the 1990s demoscene and to Future Crew's *Second Reality* (1993). All of the code, graphics and audio are original. Nothing was taken from their demo. — Marco Porro
- **Aquarium homage, "asciiquarium × Second Reality":** I rebuilt the default Aquarium (`webos/app/saver/homage.js`, `launcher/.../AquariumHomage.java`) as a demo in five parts (Surface, Descent, Mid waters, Abyss with a glenz vector, end credits with greetz to Future Crew, Kirk Baucom, DHH, @erikrjohansson, @tcballard). It has 8+ fish species, bubbles, a shark that eats fish, a moving OMARCHY castle, a porthole lens, copper bars, a VGA 8×16 scroller, Omarchy theme colours, depth lanes with parallax, and one depth value shared by the header and the ruler. It runs at a 30 fps cap; the web build uses a glyph atlas. Audio is an original tracker-style song at 48 kHz stereo with L R R L panning, attack/release envelopes, a part-dependent lowpass and reverb, rare arpeggios, and VU meters from real levels. Anti burn-in: slow pixel shift, HUD fade, nothing static. Inspired by asciiquarium by Kirk Baucom (GPL, no code/art copied).
- **Aquarium Classic kept:** the previous aquarium and its ST3 tune are still selectable as `aquarium-classic` ("Aquarium Classic") on Android TV, webOS and Tizen, with only bug fixes: full OMARCHY logo, removed the green `[OMARCHY · ABYSS CORE]` text, no overlaps (ST3 line on its own row, ticker/copper/scroller separated), sonar column dimmed behind the fish, creatures clipped before the ruler, anti burn-in pixel shift and HUD fade.
- **Fix: Aquarium `ABYSS CORE` logo missing the final `Y`:** the half-block art in `SUNKEN_TERM` (`AsciiSaverView.java`, `webos/app/saver/index.html`) had only 6 glyphs (`OMARCH`). I added the `Y` glyph and widened the frame so the full `OMARCHY` now renders on Android TV, webOS and Tizen.
- **Fix: webOS/Tizen Aquarium never started:** unescaped backslashes in the sprite strings of `webos/app/saver/index.html` were a JavaScript syntax error, so the web Aquarium showed a black screen. All sprite backslashes are now escaped.
- **Official Omarchy plugin catalog (browse-only):** `omarchy-tv plugins catalog [QUERY] [--url URL]` reads the real desktop registry `https://plugins.omarchy.org/catalog.json` (`stateSchemaVersion` 2). Entries are Omarchy desktop plugins, so they are listed with repo link and their PC install command (`omarchy plugin add … --enable`) only. By default only TV-relevant entries (media/visual tags or a TV manifest) are shown; desktop-only bar/shell/quickshell plugins are hidden unless `--all`. An entry is marked `TV ✓` only when a TV manifest with the same id exists (built-in or `plugins add`). Unknown fields are ignored and bad entries skipped. Tested against a saved sample (`pc-plugin/omarchy-tv/tests/fixtures/omarchy-catalog-sample.json`).
- **Subtitles for queued Cinemarchy items:** `omarchy-tv send URL --queue --sub FILE.srt|.vtt|URL`. Subtitle links are stored with the queue item; a local subtitle file is sent inline (max 1 MiB) and saved on the TV, because the PC is not serving it later. Queue playback and CH+/CH- skipping load each item's own subtitle track (Media3).
- **Wireless debugging pairing (Android 11+):** `omarchy-tv adb-pair HOST:PAIRPORT CODE [--connect HOST:PORT]` wraps platform-tools `adb pair` (6-digit code) and then `adb connect`.
- **Credits corrected:** SpaceX TV and Grok Bot companion descriptions now match the upstream READMEs (no invented stream lists); Tokyo Roadster wallpapers credited to Tom Ballard's MIT repo without the old review caveat.

### Added
- **Default Screensaver & Intro Wallpaper (`v2.13.0-beta`):**
  - Default screensaver is now **Second Reality Cyber-TUI Aquarium** (`saverMode = "aquarium"`, `saverArt = "aquarium"`) with **4-channel ScreamTracker 3 (`ST3·PM/FC`) procedural synth audio enabled by default** (`saverSound = true`, `M` to mute/unmute).
  - Initial default theme and intro wallpaper is **`last-horizon`** (`wallpapers/last-horizon/01-eyes-wide.webp` + `01-eyes-wide.mp4`, credited to **Erik Johansson** `@erikrjohansson` with explicit permission on X, Oct 26, 2026).
  - Added two independent toggles in **Settings → Themes** and **Settings → Wallpaper**: **Random theme** (`randomTheme`, default `true`) and **Random wallpaper / intro** (`randomWallpaper`, default `true`), which rotate across the 5 bundled animated intros (`last-horizon` `01-eyes-wide` and `cumulus` `01..04`) on Home resume and automatically lock when a user manually picks a theme or wallpaper.
- **Sandboxed Declarative Plugin Engine (`omarchy-tv plugins` + TV `/v1/plugins`):**
  - Added `omarchy-tv plugins [list|enable|disable|add|remove|sync|items|play|grokbot]` and sandboxed TV HTTP endpoints (`GET/PUT /v1/plugins`, `GET/POST/PUT /v1/plugins/grokbot`).
  - **SpaceX TV (`com.sighmon.spacex-tv`):** Built-in plugin based on **sighmon's** (`@sighmon`) MIT Omarchy bar widget [`sighmon/omarchy-spacex-tv`](https://github.com/sighmon/omarchy-spacex-tv). It reads the same hosted cache (`sighmon.com/spacex-tv/x-cache.json`) and shows a top-bar countdown pill plus links to SpaceX launches, SpaceX on X and Starship pages on Android TV, LG webOS and Samsung Tizen. (sighmon's separate Apple TV app `sighmon/spacex-tv` has no licence file, so nothing was taken from it.)
  - **Omarchy Grok Bot Desktop Companion (`io.github.tcballard.grokbot-desktop`):** Built-in plugin named after **Tom Ballard's** (`@tcballard`) MIT repo [`tcballard/omarchy-plugin-grokbot-desktop`](https://github.com/tcballard/omarchy-plugin-grokbot-desktop) (approved on X Oct 27, 2026). That repo is currently an Omarchy plugin starter; the corner companion with blinking capsule eyes (`[ ◉ ◉ ]` / `[ ▬ ▬ ]`) is OmarchyTV's own code.
- **Universal Android TV & Fire TV Compatibility (`minSdk 24`):**
  - Lowered `minSdk` from `26` to `24` (Android 7.0 Nougat / Fire OS 6+) with API-guarded font loading (`CastUi.font(Context)`), so Fire TV Sticks on Fire OS 6/7/8, Sony Bravia, Google TV, Nvidia Shield, Xiaomi, TCL, Hisense, and Sharp AQUOS all work out of the box from a single APK.
- **Send to TV** (`omarchy-tv send FILE|URL [--sub FILE.srt|.vtt|URL] [--queue] [--title T] [--at MM:SS]`): casts local files or links with optional subtitles; `--queue` adds to the Cinemarchy "From PC" row. File-manager integration: `share/applications/omarchy-tv-send.desktop` (by Marco) and a Nautilus script, installed with `share/install-file-manager.sh` (`--uninstall` to remove).
- **Samsung Tizen build (experimental, untested on hardware):** `tizen/build-wgt.sh` packages the webOS app as a `.wgt` with a Tizen key/launch shim; see `tizen/INSTALL-TIZEN.md`. The Tizen app IDs for YouTube/Netflix are unverified.
- **webOS hidden apps:** the Hidden apps menu now has localized labels, a "Show all hidden apps" entry, and drops ids of apps that no longer exist.
- **Phone companion:** the TV address field accepts `IP:PORT`, so ADB can use a port other than 5555 (saved as `adb_port`). Android 11+ Wireless debugging pairing (SPAKE2/TLS) is not implemented; the app now explains how to pair from a PC instead.
- **`omarchy_tv.wireguard` module:** pushes a WireGuard `.conf` to the TV and toggles a tunnel via the official WireGuard app broadcasts (needs "Allow remote control apps"). Not yet wired into the `omarchy-tv` command.

### Fixed
- `omarchy-tv iptv add --epg URL` no longer also saves the EPG URL as a playlist.
- M3U parsing: titles with commas inside quoted attributes.

### Credits
- Corrected wallpaper credits: Cumulus (erikrjohansson/omarchy-cumulus-theme, MIT, © 2026 Erik Johansson) and Tokyo Roadster (tcballard/omarchy-theme-tokyo-roadster, MIT, © 2026 Tom Ballard). The Eyes Wide intro was marked REVIEW (since cleared with author permission; Tokyo Roadster confirmed as Tom Ballard's MIT work on Oct 29, 2026) in `launcher/WALLPAPER-PROVENANCE.csv` because their media licence is not established. Removed incorrect attributions to repositories that do not exist.

## [2.12.0-beta] — 2026-10-25 (versionCode 20 — 26 MIT Themes & Original Wallpapers, Grok Bot & AI Hub, Native MCP Server, Sharp AQUOS & Scrollable Menus)

### Release Artifacts (`v2.12.0-beta`)
- **Android TV Launcher APK (`omarchytv-release-2.12.0-beta.apk`):** `versionCode 20`, SHA-256 `9dedc335fb1150afa6027f4123bd295bec8241a4c654842a6be65caff993f035`
- **PC / Mac Plugin (`omarchy-tv-pc-plugin-2.12.0-beta.tar.gz`):** `2.12.0-beta`, SHA-256 `2aef33b30e38eba6eb4355ad597bf382bb5e7210be59d7070d6b5f509765bd0c`
- **Android Phone Companion (`omarchytv-mobile-2.12.0-beta.apk`):** `versionCode 21200`, SHA-256 `42bb62aa6169c52afacc9b477d25f6f95fffe2eeea9f779394606e1a4e5ad0a4`
- **Signing Certificate SHA-256:** `90aa68d9f45e623eadfd349ab1eb66042356f95ff6347a4568c3f3a78f8ea33d`

### Added
- **26 MIT-Only Themes & Original Bundled MIT Wallpapers:**
  - Added 3 new 100%-MIT community themes with their original MIT wallpapers:
    - **`jade-horse`** ([dhh/omarchy-jade-horse-theme](https://github.com/dhh/omarchy-jade-horse-theme), MIT, © 2026 David Heinemeier Hansson): `01-ink-horse.webp`, `02-carved-jade-horse.webp`, `03-mountain-horses.webp`, `04-omarchy.webp`.
    - **`year-of-the-horse`** ([dhh/omarchy-year-of-the-horse-theme](https://github.com/dhh/omarchy-year-of-the-horse-theme), MIT, © 2026 David Heinemeier Hansson): `01-ink-horse.webp`, `02-paper-horse.webp`, `03-mountain-horses.webp`, `04-omarchy.webp`.
    - **`hermarchy`** ([archer-clawbot/omarchy-hermarchy-theme](https://github.com/archer-clawbot/omarchy-hermarchy-theme), MIT, © 2026 Archer Clawbot): `01-hermarchy-command.webp`, `02-hermarchy.webp`, `03-hermarchy.webp`, `04-omarchy.webp`.
  - Generated 3840×2160 flat two-color Omarchy wordmark wallpapers (`01-omarchy.webp`) for `last-horizon`, `solitude`, and `starship` using each theme's exact MIT `background` and `accent` colors, so all **26 bundled MIT themes** have at least one bundled 100%-MIT wallpaper.
- **Native Grok Bot · Omarchy TUI (`AskActivity`) + AI WebApps:**
  - **Grok Bot Omarchy TUI on Home Shelf:** Clicking the **Grok Bot** tile (`grokbot@tv:~$ > GROK BOT [ omarchy · tui ]`) on the Home screen opens the native Omarchy-styled TUI window (`BoxView` 2 px border, `grok bot · omarchy tui [<theme>]` legend, live 4K `WallpaperView` backdrop, `grokbot@omarchytv:~$` prompt, D-pad quick-action chips, voice & keyboard input).
  - **Standalone Local TV Commands + Paired PC AI Bridge:** Control themes (`theme jade-horse`, `next theme`, `themes`), 4K wallpapers (`wallpaper`), ASCII screensavers (`screensaver`, `speed slow|normal|fast`), installed TV apps (`open YouTube`, `apps`), and system/MCP status (`status`) directly on the TV without a PC, or stream answers from a paired PC's CLI (`grok`, `claude`, `codex`, `gemini`, `hermes`, `ollama`) over `OmarchyLink`.
  - **Built-in AI WebApps (`[ 🌐 grok.com ]` / `[ 🌐 web ▾ ]`):** Open **Grok Web** (`grok.com`), **Grok (X)** (`x.com/i/grok`), **ChatGPT**, **Claude**, or **Hermes** in the sandboxed `:web` process (`WebAppActivity`) with persistent cookies/DOM storage and D-pad cursor.
  - **Grok Bot & AI Hub** reachable from the Main Menu, Workspace 4 (`PC`), Workspace 5 (`System`), and `AI & terminal`.
  - Added `grok` (`Grok Bot`) and `hermes` (`Hermes Agent`) CLI/API providers to `omarchy-link` (`AUTO_ORDER` and `clis.py`).
- **Native MCP Server (TV `:8999/mcp` + PC `omarchy-tv mcp`):**
  - **TV Native MCP Server (`POST /mcp` & `POST /v1/mcp` in `LinkServer.java`):** JSON-RPC 2.0 MCP endpoint (`protocolVersion: "2024-11-05"`) authenticated via `OTV1` HMAC or `Authorization: Bearer <token>`, exposing `tv_get_status`, `tv_list_themes`, `tv_set_theme`, `tv_send_key`, `tv_launch_app`, `tv_cast_media`, `tv_show_notification`, and `tv_open_ai_webapp`. Advertises `"mcp"` in `/v1/status` capabilities.
  - **PC MCP Bridge (`omarchy-tv mcp [--stdio|--http] [--port 8990]`):** Stdio and HTTP JSON-RPC 2.0 MCP server with 256-bit Bearer token auth (`~/.config/omarchy-tv/mcp-token`, `0600`) and strict TV-only tool whitelist.
- **Screensaver Effect Speed:** Added `"screensaverSpeed": "slow" | "normal" | "fast"` (`Effect speed` in the Screensaver menu), scaling both `tte.js` WebView effects and the native ASCII fallback.

### Fixed
- **Issue #3 — Bundled MIT Wallpapers Overwritten by PC Sync & Non-Scrollable Theme Menu:**
  - Wrapped `AsciiMenu` rows in a `<ScrollView>` (`popup_inputs.xml` + `AsciiMenu.java` `scrollIntoView`) so long menus (including the 26-theme list) scroll smoothly past `matte-black` all the way to the bottom on 1080p TVs.
  - Added `Sync PC wallpaper` toggle (`syncPcWallpaper`, default `false`) in the Themes and Wallpaper menus so PC pairing/sync never overwrites the TV's bundled MIT wallpaper unless explicitly enabled.
- **Issue #4 — Sharp AQUOS & Multi-Brand Live TV Detection:**
  - Expanded `live_tv_packages` (`com.sony.dtv.tvx`, `jp.co.sharp.av.android.aquostvapp`, `com.google.android.tv`, `com.android.tv`, `org.dtvkit.inputsource`, `com.tcl.tv`, `com.hisense.tv.livetv`, `com.philips.tv.tvapp`) and added explicit `jp.co.sharp.av.android.aquostvapp/.TvActivity` fallback when TIF returns only internal tuner services.
- **Issue #5 — App Hiding & Unhiding Broken:**
  - Fixed `appTileMenu()` storing `pkg/activity` instead of `packageAt(position)` when hiding apps from the Home shelf or grid, updated `LauncherConfig.isHidden()` and `hiddenPage()` to normalize legacy `pkg/activity` entries and support `web:<id>` WebApp tiles, and added a direct **Hidden apps** row to Workspace 5 (`System`).

### Thanks
- **Anderson Leite** ([@x_andersonleite](https://x.com/x_andersonleite) / [@anderson-leite](https://gitlab.com/anderson-leite)): Sharp AQUOS testing, Issue #3, Issue #4, Issue #5 reports, and MR !1.
- **David Heinemeier Hansson** ([@dhh](https://x.com/dhh)): `jade-horse` and `year-of-the-horse` MIT themes & wallpapers.
- **Archer Clawbot**: `hermarchy` MIT theme & wallpapers.

## [2.11.0-beta-mobile] — 2026-10-24 (OmarchyTV Mobile — Universal Smart TV Phone Installer & Companion)

### Added (`mobile-companion/` — `omarchytv-mobile-2.11.0-beta.apk`)
- **OmarchyTV Mobile (`com.omarchytv.mobile`, `2.11.0-beta`):** Official MIT-licensed Android smartphone companion and wireless installer for **Android TV / Google TV, LG webOS, Samsung Tizen, and Hisense VIDAA** (`minSdk 26`, `targetSdk 34`, signed with the official OmarchyTV release certificate `90aa68d9f45e623eadfd349ab1eb66042356f95ff6347a4568c3f3a78f8ea33d`).
- **Pure-Java Wireless ADB over TCP (`AdbTcpClient.java`):** 2048-bit RSA keypair generation, 524-byte Android `RSAPublicKey` struct encoding, `CNXN`/`AUTH` handshake over `:5555`, binary `sync:` (`SEND`/`DATA`/`DONE`/`OKAY`) `.apk` upload, `pm install -r`, and 1-tap default Home launcher pinning.
- **HMAC-SHA256 `omarchy-link` Client (`OmarchyLinkClient.java`):** Parallel `/24` LAN subnet scanner (`:8999`, `:5555`, `:47800`, `:8001`), dual-header HMAC-SHA256 signing (`Authorization: OTV1` + `X-Omarchy-Signature`), live switching across all **23 official Omarchy themes**, tactile D-Pad & media remote, AI/terminal prompt sender, custom wallpaper upload, and custom app banner upload.
- **Safe Reversible Brand Debloat (`DebloatProfiles.java`):** Non-destructive `--user 0` bloatware disable & 1-tap restore profiles for **Sony Bravia**, **Google TV Streamer**, **Sharp AQUOS**, **TCL**, **Hisense**, and **Xiaomi**, guarded by a hardcoded `PROTECTED_PACKAGES` denylist.
- **LG webOS Phone Bridge & Universal WebTV Server (`WebOsBridgeServer.java`):** Embedded `:8765` HTTP bridge server implementing `/v1/status`, `/v1/themes`, `/v1/wallpaper`, `/omarchytv.ipk` (with Debian `!<arch>` and `appinfo.json` inspector), and `/tv` Universal 1080p HTML5/Canvas OmarchyTV Launcher for Samsung Tizen (`:8001` SmartView auto-launch) and Hisense VIDAA TVs.
- **Dual-Mirror Release & Theme Client (`GitLabReleaseClient.java`):** Resolves `.apk` and `.ipk` releases and live `omarchy_themes.json` across both GitLab (`projects/87958120`) and GitHub (`piermark/omarchytv`) with SHA-256 verification.

All OmarchyTV releases stay available on both [GitHub Releases](https://github.com/piermark/omarchytv/releases) and [GitLab Releases](https://gitlab.com/piermark/omarchytv/-/releases).
Every APK is signed with the same certificate (SHA-256 `90aa68d9f45e623eadfd349ab1eb66042356f95ff6347a4568c3f3a78f8ea33d`),
so each version installs over the previous one.

## 2.11.0-testing (LG webOS Edition) — 24 October 2026

Experimental **`testing`** release (not beta) bringing OmarchyTV `2.11.0` to **LG webOS TVs** as an `.ipk` package (`com.omarchytv.home`), along with an updated dual-platform PC plugin (`omarchy-tv-pc-plugin-2.11.0-testing.tar.gz`). The Android TV `2.11.0-beta` release is unchanged. Community feedback from LG webOS TV users is welcome!

- **LG webOS App (`omarchytv-webos-2.11.0-testing.ipk`)**:
  - All 23 official Omarchy themes (`omarchy_themes.json`), Tom Ballard's *"Liftoff Sunset"* default wallpaper for Tokyo Night, and the 20 MIT Omarchy wordmark wallpapers.
  - Workspaces `1`–`5` (`home`, `tv`, `apps`, `pc`, `sys`), animated ASCII `OMARCHY` logo, and all 37 `tte.js` screensaver effects.
  - Includes all `2.11.0` improvements: `°C / °F` temperature unit switch in the Weather menu, full English / Italian / German localization (`weather`, `omarchy · pc`, `ai credits · pc`, `system`, `theme`, `up`), and non-overlapping top box titles.
  - Native LG webOS Luna Bus (`luna://com.webos.applicationManager/launch`) app launching and full Magic Remote (D-pad, pointer/cursor, number keys `1`–`5`, and webOS Back `461`) support.
- **PC Plugin (`omarchy-tv-pc-plugin-2.11.0-testing.tar.gz`)**:
  - Added `.ipk` package validation and `ares-install` support to `omarchy-tv update`.
  - Added `omarchy-tv webos-serve` (`--port 8765`) LAN HTTP + CORS bridge (`/v1/status`, `/v1/themes`, `/v1/wallpaper`) for live PC telemetry and theme sync on LG webOS TVs.
  - Full details and installation guide: [webos/INSTALL-WEBOS.md](webos/INSTALL-WEBOS.md) and [webos/RELEASE-2.11.0-testing-webos.md](webos/RELEASE-2.11.0-testing-webos.md).

## 2.11.0-beta — 23 October 2026 (versionCode 19)

Defaults are unchanged from 2.10.2-beta: every new option is off until you turn it on. Same bundled wallpapers as
2.10.2-beta (default: Tom Ballard's "Liftoff Sunset" for Tokyo Night).

### Fixes
- **Apps missing on Android 11+ TVs** (issue #2): the manifest now declares `<queries>` for `MAIN` + `LEANBACK_LAUNCHER`
  and `MAIN` + `LAUNCHER`, so Netflix, Prime Video, Disney+ and other apps show in the dock and grid on Android 11+.
  No `QUERY_ALL_PACKAGES`. No change on Android 8/9.
- **Google TV Wi-Fi**: `com.google.android.tungsten.setupwraith` is never disabled; `tools/disable.sh` / `disable.ps1`
  skip it even if a list includes it, and docs/DEBLOAT.md lists it as "never touch". Home is set with
  `cmd package set-home-activity`. DNS / `private_dns_*` are never touched.
- **TV tile**: `"tv": {"enabled": false}` now also hides the TV tile in the home row.
- **Translations**: the "weather", "system", "theme" and "up" labels were hard-coded in Italian; they now follow the
  TV language (English, Italian, German). An English TV shows no Italian text.
- **Box titles**: the titles of the top boxes (weather, omarchy · pc, ai credits · pc) no longer overlap the border line.

### New (off by default)
- **°C/°F**: Weather menu → "Temperature unit" (`"tempUnit": "F"` in omarchytv.json; default °C). Works with the
  manual city ("Search city…"); "Automatic (from IP)" stays the default.
- **Custom app banners**: `banners/<package>.png` in `Android/data/com.omarchytv.home/files/`, or pushed from the PC with
  `omarchy-tv icons list | push | delete` (PNGs in `~/.config/omarchy-tv/banners/<package>.png`). TV endpoints
  `PUT/DELETE /v1/banners/<package>`, `GET /v1/banners`, capability `banners`. Without a file the app's own banner is used.
- **Pane timestamps**: `PUT /v1/widgets` panes may carry `"at"` (unix seconds); without it the push time is used, as before.
- **PC plugin**: Italian/English UI following the system language (or the `language` config key); the new
  `icons` subcommand. All other commands are unchanged; with an older launcher the plugin says banners are unsupported.

### Unchanged
- Default theme, wallpapers, layout, menus, screensaver, Omarchy Link protocol and all existing settings.

### Thanks
- Michael Pfeifer ([@TheFifeDawg](https://x.com/TheFifeDawg)): Google TV feedback, the setupwraith Wi-Fi issue, the
  `<queries>` diagnosis and the TV tile bug.
- [@ilafayajose](https://gitlab.com/ilafayajose): issue #2 report (Noblex/Hisense, Android 11), diagnosed by @TheFifeDawg.
- @wielorzeczownik: the box title / border overlap report.

## Older versions
- 2.10.2-beta, 2.10.1-beta, 2.10-beta: see [launcher/docs/RELEASE-NOTES-2.10-beta.md](launcher/docs/RELEASE-NOTES-2.10-beta.md).
