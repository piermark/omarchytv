# OmarchyTV 2.20 — user guide

This guide is for people who use OmarchyTV 2.20: what is new, where to turn it on, which keys to press, and what
stays as it was in 2.15.1. 2.20 is built on the published 2.15.1. For 2.14 features (boot screen, languages, Arcade,
OmaFire, Cinemarchy), see [OMARCHYTV-2.14-GUIDE.md](OMARCHYTV-2.14-GUIDE.md).

Platforms: **Android TV** (the launcher APK), **LG webOS** and **Samsung Tizen** (the TV web app). The PC side is
an **Omarchy Linux** PC (or a Mac) with the `omarchy-link` and `omarchy-tv` plugins.

Where settings live:

- Android: **Launcher settings** (the menu), plus the **Plugins**, **Screensaver**, **Wallpaper**, **Grok Bot & AI**
  and Radiomarchy **Settings** pages.
- LG/Samsung: **System menu → All OmarchyTV settings**, plus the **Plugins**, **Screensaver**, **Wallpaper** and
  **Companion settings** (Blue key) modals.

---

## 1. Home: still-fractal slideshow (the default)

**Radiomarchy fractals** is the default Home background. It is a slideshow of 12 still images (our own art,
1280×720): a slow crossfade and a very gentle Ken Burns pan, with the music playing underneath if a song is on
(or the wallpaper starts one). There is no live GL on Home.

The UI accent colours follow the current still's palette, with contrast kept readable against the theme
background. The slideshow pauses when Home is not on screen, or a dialog is in front.

OmaFire stays in Settings → **Wallpaper**. A wallpaper you already picked is left alone (OmaFire, a still, or
"omarchy" with random locked off).

---

## 2. Radiomarchy on a weak TV, and Animated fractals

**Radiomarchy** is Omarchy Radio on the TV, a music app of its own, like Cinemarchy.

- Android TV: its banner in the TV's app row, the ♫ icon in the top bar, Walker, or the key action `radio`.
- LG and Samsung: the Omarchy menu, or the R key.

Songs come from [radio.omarchy.org](https://radio.omarchy.org) and stream from the site; nothing is copied into
the app. Lyrics light up when there is a sheet. **Omarchy Stories** is the community podcast. Explicit songs have
an **E** badge; Settings (or the BLUE key in Radiomarchy) hides them. Shuffle is on by default.

On a **weak TV** (Mali-T8xx, including the Sony KD-55XE9005's Mali-T860, older Mali-400/450, 2 GB of RAM or less,
or after a previous stall kill) the normal Radiomarchy view opens on a **plain static theme background**, like
Cinemarchy, so the app appears at once. There is no live GL at open. Capable TVs keep the live shader.

**Animated fractals** (Italian: **Frattali animati**), default **auto**, is in Radiomarchy **Settings** and in
Settings → **Wallpaper**. It cycles auto → yes → no (Italian: auto / sì / no).

| Setting | Normal Radiomarchy view | Demo mode |
|---|---|---|
| **auto** (default) | Plain on a weak TV, live on a capable one | Live beauty fractals on every TV |
| **yes** | Live GL even on a weak TV (720p beauty path) | Live beauty fractals |
| **no** | Plain (no live GL) | High-quality still slideshow, not live |

On a weak TV the equaliser bars and the progress redraw run at 12 fps. The audio analysis is unchanged.

---

## 3. Demo mode: live beauty fractals

**Demo mode** (the RED key, or a minute without keys while music plays) hides the panels. Live fractals fill the
screen with a sine scroller of what is playing, on **every** TV, weak ones included.

On a weak TV the shader stays the **full** one (not the cheap light bake). It is drawn at 720p, about 18 fps,
upscaled with linear filtering, with smooth time so motion does not jump. The picture stays rich; the frame rate
is what gives way. Stalls step down (quality, then fps down to 15). If the TV still cannot keep up, demo falls
back to the high-quality still slideshow. It never becomes a black or text-only screen.

◀ ▶ picks another fractal, ▲ ▼ the colours. Leave demo with BACK (or the same key that entered it). GL starts
after the first UI frame, so the texts are on screen first.

If **Animated fractals** is **no**, demo uses the still slideshow instead of live GL.

---

## 4. Adaptive 1080p cap and 720p floor

Live fractals (the full-screen Radiomarchy view and the fractals screensaver) are drawn into a fixed buffer:

- never larger than **1080p** (the Home UI is 1080p; a 4K panel does not get a 4K GL surface)
- **high** fills that 1080p cap; **medium** and **low** use a **720p** floor
- the watchdog measures present cost and drops a level on GPU backpressure
- below the floor, the full shader can be drawn into a smaller offscreen buffer and linearly upscaled

Capable GPUs start at **high**. Settings can still pin ultra / high / medium / low. **ultra** is the 1080p cap
with 2×2 supersampling. The cheap **light** bake (a small still, then a pan and a palette cycle) is only a later
step-down on the live path, not the demo default.

A stall during the first layout or the first seconds after open is ignored (a cold start on a Sony Mali-T860 is
about 600 ms and is normal). Grace always ends after 8 s. After that, a long stall or repeated stalls at the
floor fall back to the plain theme background for the session (or, in demo, to the still slideshow). Never a
black screen.

Bundled photo wallpapers are still the 4K files, with 1080p copies used on 1080p panels. The 1080p cap applies
to live GL (and to Pulse's backing store; see below), not to those stills.

---

## 5. Pulse, the default screensaver (adaptive and sharper)

**Omarchy Pulse** stays the default screensaver (Settings → **Screensaver**). "Previous screensaver" puts back
the one you had. Radiomarchy fractals, the Aquarium, the Live screensaver and the others stay in the picker.

The vendored Pulse engine is unchanged. The 2.20 **host** around it:

- starts at Pulse 1.8's high look at **full device pixels**, so a 4K panel is not drawn at CSS size and looking soft
- measures frame time and steps down, CSS-upscaling, never below a **720p** floor
- caps fps at 30 at high, 20 after stutter, 15 at the floor

Settings → Screensaver → **Pulse graphics** can pin `low`, `medium` or `high`. The same wrapper is on Android TV,
LG and Samsung. The Home fractals and OmaFire layers do not keep running under the saver.

---

## 6. SpaceX TV is off by default

The SpaceX TV top-bar pill (`com.sighmon.spacex-tv`) is **off** until you turn it on in Settings → **Plugins**.
A setting you already saved as on is kept.

---

## 7. Wider Motorsport widget

The Home racing column is **382 px** (it was 268 px), enough for a full Grand Prix name and session time such as
"Las Vegas  sun 22 nov 05:00". Weather, PC and AI share the rest of the row. The same widths are on LG and
Samsung.

---

## 8. The song title no longer covers the clock

When Radiomarchy is playing, the now-playing label in the top bar sits to the right of the date. It shrinks with
an ellipsis to the space left by the icons (and SpaceX, or the racing countdown) and hides if there is no room.
The clock stays visible.

---

## 9. Media keys after YouTube

If YouTube (or another app) takes the sound and Radiomarchy pauses, coming back to Home keeps the media session,
so PLAY and PLAY_PAUSE resume the song without opening Radiomarchy again.

- Android: the MediaSession stays the media-button receiver.
- LG/Samsung: the app claims the Media Session and wakes the AudioContext.

---

## 10. Fractal screensaver audio

**Radiomarchy fractals** in Settings → **Screensaver** plays with the music: it keeps a song that is already on,
or starts one if none is, and stays audible after another app or the saver itself had taken audio focus (or an
AudioContext left suspended on LG/Samsung). Pulse stays the default screensaver; this is an option in the picker.

The Home fractal wallpaper starts or keeps the music in the same way.

---

## 11. GrokBot Office

A pixel-art office (our own art, no logos) where your Grok Bot bots sit at their desks. **Optional, off by
default.** At most 30 frames a second; nothing is drawn while it is hidden or on its start screen. The start
screen fits 720p and 1080p; the usage card sits above the "Anything else?" bar and does not cover it.

Only real data: no bots means an empty office with a hint; no numbers means no usage card.

### Turn it on and open it

**Android TV**

1. Open **Settings → Grok Bot & AI** (Italian: **Grok Bot e AI**).
2. Turn **GrokBot Office (optional)** on.
3. Open it with **Open GrokBot Office** on that page, the **GrokBot Office** banner in the TV's app row, **Walker**,
   or the remappable key action `office`.

**LG webOS and Samsung Tizen**

1. Press **Blue** → **Companion settings**.
2. Turn **GrokBot Office (optional)** on.
3. Open it with **Open GrokBot Office** on that page, the Omarchy menu, or the **O** key.

If it is still off, opening it only tells you to turn it on first.

### Ask, Hire, and keys

- **Grok Bot** answers and hires through **Grok Bot diretto** (the same webhook, key and reply server). The
  request carries an `office` object; the reply can change the list. Details: [GROKBOT-DIRECT.md](GROKBOT-DIRECT.md).
- **Claude** and **ChatGPT**: a chip in the Ask box picks who answers (Grok Bot is the default). They answer
  from the paired PC (omarchy-link). **The API keys stay on the PC** and are never sent to the TV.
- Hire a Claude or ChatGPT bot from the office (the PC checks the key with the provider, then saves the bot).
  Or on the PC: `omarchy-link office hire NAME --provider claude|chatgpt`, `fire`, `show`, `usage`.
- Spend this month shows only when an admin key is set on the PC (`ANTHROPIC_ADMIN_KEY`, `OPENAI_ADMIN_KEY`, or
  `ai.json`). A percentage only when you set a budget.

Where the keys live and how to set them: [pc-plugin/omarchy-link/docs/OFFICE.md](../pc-plugin/omarchy-link/docs/OFFICE.md).

---

## Defaults at a glance

| Setting | Default in 2.20 |
|---|---|
| Home wallpaper | Radiomarchy fractals (still slideshow); an already saved wallpaper is left alone |
| Radiomarchy on a weak TV | Plain theme background (instant open) |
| Animated fractals | auto |
| Demo mode | Live beauty fractals (even on a weak TV); still slideshow if the setting is no |
| Screensaver | Omarchy Pulse (adaptive host, 720p floor) |
| SpaceX TV | off (a saved on stays on) |
| GrokBot Office | off |

English, Italian, German, French and Spanish cover the new Radiomarchy and Office strings (other languages fall
back to English for those rows until they are translated).
