# OmarchyTV — what's new (user guide)

This guide is for people who use OmarchyTV: what each new feature does, where to turn it on, which keys to press
and what it cannot do yet. The technical notes for developers are in `HANDOFF.md` (sections A–U).

Platforms: **Android TV** (the launcher APK), **LG webOS** and **Samsung Tizen** (the TV web app). The PC side is
an **Omarchy Linux** PC with the `omarchy-link` plugin (Android TV) and the `omarchy-tv webos` bridge (LG/Samsung).

Where settings live:

- Android: **Launcher settings** (menu), **Plugins**, **Screensaver**, **Wallpaper** pages.
- LG/Samsung: **System menu → All OmarchyTV settings**, plus the **Plugins**, **Screensaver** and **Wallpaper** modals.

---

## 1. Boot screen

An Omarchy-style start-up: CRT power-on line → BIOS screen (`OmarchyBIOS`, real version, display mode, TV model,
OTV badge) → systemd lines as Arch prints them → automatic login → the OMARCHY TV wordmark → Home. About 7 seconds.

- The network and omarchy-link lines really wait (up to 2.5 s each) and show your Wi-Fi/Ethernet and the paired PC.
- **Any key skips it**; no key reaches Home while it plays.
- Settings: **Boot screen** = at start (default) / start and wake / off. **Boot effects** (scanlines and glow) on by
  default: turn off on slow TVs. **Boot sound** on by default.
- Android TVs mostly go to standby instead of rebooting: choose **start and wake** to see it every time you turn the TV on.
- Privacy: it shows the Wi-Fi name and the TV's local IP. Keep that in mind when filming.

## 2. Languages (23)

English, Italian, German, French, Spanish, Portuguese (Portugal and Brazil), Dutch, Polish, Swedish, Danish,
Norwegian, Finnish, Czech, Hungarian, Romanian, Greek, Turkish, Ukrainian, Russian, Arabic, Chinese (Simplified),
Japanese. The TV's language is used automatically; change it in **Launcher settings → Language**.
Voice search and Grok Bot follow the chosen language. Translations are machine-made: fixes are welcome
(`CONTRIBUTING.md`, section 5).

## 3. Theme sync both ways

Pick a theme on the TV and the Omarchy PC switches too. TV switch **"Also change the PC theme"** (off by default).
The PC side can refuse with `themeFromTv: false`. A theme made only on the TV is not on the PC: the toast says so
and only the TV changes.

## 4. TV and PC power together

**"TV and PC power together"** (Android, off by default): turning the TV on wakes the PC (Wake-on-LAN, the PC needs
WoL enabled, best on Ethernet); TV standby suspends the PC after 60 s. On LG the row is **"PC sleeps with the TV"**
(TV web apps cannot send Wake-on-LAN). Samsung: not available.

## 5. Update from the TV

- Android: **Launcher settings → Update**: checks the GitHub releases, shows the version, downloads and lets Android
  install it (the first time Android asks to allow "Install unknown apps").
- LG/Samsung: **Update via PC**: the PC bridge checks, downloads and installs the new TV app on your TV with your own
  developer tools and certificate. Turn off with `updateFromTv: false` on the PC.

## 6. Liftoff (builds on the big screen)

On the PC: `omarchy-link liftoff --name myapp -- <command>`. The TV shows an ASCII rocket with a T-minus countdown
(based on the last run), stage and progress. Success = the rocket takes off; failure = red ABORT, exit code, last
6 lines and an alarm. Any key hides it.

## 7. Live screensaver

**Screensaver → Live screensaver** (off by default). A full-screen "control room": big clock, weather sky,
notifications from the PC, GitHub, X, PC meters, ticker. Options: **PC notifications** (off), **X on the
screensaver** (off / PC notifications / X API with your own token: `omarchy-link x setup`), **GitHub** (on),
**Omastorm radar** (off: a snapshot of Omastorm's radar every 90 s in the weather box).
New items arrive with an INCOMING banner and a decrypt effect. It slows itself down on weak TVs.

## 8. X app

- Android: **X (x.com)** in Walker and Streaming. The remote keys are mapped to X's own keyboard shortcuts (next/previous
  post, like, home…); the menu key switches to pointer mode. Log in with username and password (Google sign-in is often refused inside TV web views).
- LG/Samsung: "X" opens x.com in the TV browser (remote mapping is not possible there).

## 9. Retro CRT

**Plugins → Retro CRT** = off (default) / light / strong. The whole launcher is drawn inside an old CRT set:
bezel in the theme colour, scanlines, RGB mask, vignette, LED badge. "Strong" adds colour fringes and, on
Android 13+, a curved screen. Games and Cinemarchy play inside the set too.

## 10. OmaFire wallpapers and sound

**Wallpaper → OmaFire campfire / embers**: Wes Grimes' OmaFire (MIT) as a live wallpaper at 30 fps.
With **Random wallpaper** on (the default) it also shows up by itself now and then, next to the animated intros.
Pick a wallpaper by hand to keep one, or turn Random wallpaper off on a slow TV.
**OmaFire sound** (off): the fire's own crackle and night ambience; starts on the first key press (TV rules).

### Campfire, the OmaFire theme (TV and PC)

**Themes → campfire**: colours taken from the OmaFire scene (night lake blue, moonlight text, fire orange, embers),
made to let the fire stand out. With the wallpaper following the theme, the campfire burns as the wallpaper; turn on
**OmaFire sound** for the crackle. When the random wallpaper picks a fire and Random theme is on, it comes with campfire.

The same theme exists for the **Omarchy PC**, so TV and PC match:

1. On the PC, in a terminal:
   ```
   omarchy-theme-install https://github.com/piermark/omarchy-campfire-theme
   ```
   (by hand: copy the theme folder to `~/.config/omarchy/themes/campfire/`, then `omarchy-theme-set campfire`).
2. With omarchy-link paired, pick **campfire** on the PC and the TV follows; with **Also change the PC theme** on,
   picking it on the TV switches the PC too.
3. The PC theme sets colours, the window border and a still wallpaper (a frame of the fire). For the burning fire
   with its sound on the PC, install Wes Grimes' OmaFire: https://github.com/wesleygrimes/omafire

## 11. Omastorm from the PC (Android TV)

**Plugins → Omastorm**, then **Open Omastorm** (Plugins page or Walker): the PC opens Omastorm and casts its window
to the TV; the remote drives it. Back/stop closes it. LG/Samsung: not yet.

## 12. OmarchyTV Arcade

Five games of our own, made for the remote: **only arrows, OK and Back** (they never leave the launcher).
Open: the **game pad icon in the top bar** (right, next to the Cinemarchy film icon) on every TV; also LG/Samsung
**Omarchy menu → 🕹 OmarchyTV Arcade**, Android **Plugins** or Walker, or a remote button bound to it (Android).

- **Hall**: ◀ ▶ choose a cartridge, OK plays, ▼ (or 25 s idle) shows the high scores. **Back** in a game = pause
  (resume / restart / hall).
- **V-HOLD**: run in the terminal (▲▼ lanes, avoid ▓, grab ◆) while the TV breaks. ◀▶ turn the knob to fix
  colour, OK OK slaps the set. More faults = more points.
- **RICE WARS**: only the bugs of the current theme are visible. OK re-themes the screen, ◀▶ move, ▲ fire. Boss
  every third level. Option "RICE WARS changes the PC theme too" (off).
- **FORK BOMB**: each arrow moves every process; OK forks them. Reach the exit before the OOM killer. Level 1 is the
  Omarchy logo maze.
- **TILT**: arrows tilt the TV, the marble rolls; OK levels it. Avoid the /dev/null holes.
- **HYPRMAZE**: a Hyprland desktop as a maze. Glide through the gaps, press OK next to the window marked SUPER+Q.
  Wrong window = one more window. 12 windows = game over.
- **Records** stay on the TV. **Sound** on by default ("Arcade sound").
- **Game over**: a QR code with your score. Scan it with the phone: X opens with a ready post in the TV's language
  ("Can you beat me?"). Nothing is posted by the TV; you decide on the phone.

## 13. QR codes

Setting **OmarchyTV QR code** = Live (default) / boot + Live / off. Small, in the theme's colours, never over the
OmaFire wallpaper. It links to the project page.

## 14. Cinemarchy, the media hub

Open it from Home (🎬 Cinemarchy) on every TV. A full-screen hub in the active Omarchy theme, Omarchy font and
wordmark. It is light: only the visible rows are drawn, so even 20 000 IPTV channels scroll smoothly.

### Sources (keys 1–8 on LG/Samsung)

1. **@dhh37** — DHH's YouTube videos (new ones marked).
2. **Omarchy PC** — browse the PC's own folders: Videos, Music, Pictures, Downloads (the localised names too, e.g.
   "Video", "Scaricati") and ~/Movies. Read-only. Subtitles `film.srt` / `film.vtt` next to `film.mkv` are used
   automatically.
3. **Streaming apps** — your installed apps, plus X.
4. **TV & inputs** — HDMI and other inputs.
5. **IPTV playlists** — add your M3U/M3U8 URL (the TV keyboard opens). Channels grouped as in the list, favourites,
   the last channel remembered.
6. **Open movies** — four Blender open films (labelled as demo streams) to try the player.
7. **Up next** — your queue.
8. **Player & tools** — modes, sleep timer, info.

### Keys

- ▲▼ ◀▶ move, OK opens/plays, Back goes back (up a folder on the PC).
- **Red**: add to / remove from Up next. **Green**: ★ favourite. **Yellow**: next IPTV group. **Blue**: surprise channel.

### Player

- The TV's own video player (MP4, HLS, WebM: what your TV supports; MKV/HEVC depend on the model).
- **Resume**: it remembers where you stopped (also on Android).
- ▲▼ in the player: previous/next (channel zapping on IPTV).
- **Blue** cycles: normal → **VHS mode** (tape scanlines, tracking bar, VCR counter) → **ASCII mode** (the film drawn
  as coloured text; only for streams that allow it, otherwise a message) → normal.
- **Yellow**: sleep timer 15/30/60/90 min.
- PC photos open full screen.

### From the PC

- **LG/Samsung**: start the bridge on the PC (`omarchy-tv webos`, same ask code as Grok Bot). Files are streamed with
  signed links that only work for that file.
- **Android**: the paired omarchy-link does it; in Cinemarchy open **omarchy-link://pc → 📁 Browse the PC's folders**.
- Turn it off on the PC with `mediaFromTv: false`. Choose other folders with `OMARCHY_MEDIA_DIRS=/path/a:/path/b`.
- Hidden files, the home folder itself and links pointing outside the shared folders are never shown.

### Open it with one key (Android TV)

**Launcher settings → Remote buttons**: bind a button to **🎬 Cinemarchy** (or **🕹 OmarchyTV Arcade**). Other
apps or adb can open it too: `adb shell am start -n com.omarchytv.home/.MainActivity --es com.omarchytv.home.extra.ACTION cinemarchy`.

### Legal sources only

Cinemarchy plays what you own, free/open content and playlists you add. It has no torrent or pirate add-ons and
does not ship any channel list.

### Limits (for now)

- XMLTV programme guide only on Android.
- ASCII mode needs streams that allow it (CORS) and a reasonably fast TV; it is opt-in.
- Large PC libraries over Wi-Fi may take a moment to list (2000 entries per folder max).

---

## Defaults at a glance

| Setting | Default |
|---|---|
| Boot screen / effects / sound | at start / on / on |
| Also change the PC theme | off |
| TV and PC power together | off |
| Live screensaver | off |
| Retro CRT | off |
| OmaFire sound | off |
| Arcade sound / RICE WARS changes PC theme | on / off |
| OmarchyTV QR code | Live |
| PC folders in Cinemarchy (`mediaFromTv`, PC) | on |
