# OmarchyTV widgets and plugins (design, not implemented yet)

Status: **contract only.** This build ships the built-in bar modules; `widgets` in `omarchytv.json`
is parsed (`LauncherConfig.widgets`) but not yet used to build the bar. Nothing here polls the network.

## Manifest (modelled on Omarchy's plugin format)

Same shape as Omarchy plugins (`manifest.json`, reverse-DNS `id`, `kinds`, `barWidget`), plus a
`tv` block. `omarchy.*` ids are reserved for built-ins that mirror Omarchy's own modules.

```json
{
  "schemaVersion": 1,
  "id": "com.omastorm.radar",
  "name": "Omastorm (TV)",
  "version": "0.1.0",
  "author": "...",
  "license": "MIT",
  "kinds": ["bar-widget", "panel"],
  "barWidget": { "displayName": "Weather", "category": "Weather", "allowMultiple": false, "defaultSection": "right" },
  "tv": {
    "minLauncher": 3,
    "refreshSeconds": 900,
    "network": ["api.open-meteo.com", "s3.waw3-1.cloudferro.com"],
    "permissions": ["INTERNET"],
    "panel": "fullscreen"
  }
}
```

Built-in ids: `omarchy.menu`, `omarchy.workspaces`, `omarchy.clock`, `omarchy.network`,
`omarchytv.inputs`, `omarchytv.settings`.

## Java contract (planned)

```java
interface TvWidget {
    String id();                                   // manifest id
    View createBarView(Context c, ViewGroup bar);  // small, monospace, Tokyo Night
    @Nullable View createPanel(Context c);         // opened with OK; Back closes
    void onConfig(JSONObject cfg);                 // its block from omarchytv.json
    void onResume(); void onPause();               // no work while paused
    long refreshMillis();                          // 0 = never; the host schedules, at least 5 min
    void onData(String source, JSONObject payload);// pushed data (e.g. from Omarchy Link)
}
```

Rules: no polling faster than the manifest's `refreshSeconds` (minimum 300), nothing in the
background, focusable only through the bar's left/right order, and the Inputs and Settings modules
cannot be removed.

## Widget 1: weather / radar (port of Omastorm's idea)

[Omastorm](https://github.com/wesleygrimes/omastorm) (MIT, Wes Grimes, id `com.omastorm.radar`) is
Rust + Quickshell and cannot run on Android, so only the idea gets ported:

- Bar: icon + temperature from [Open-Meteo](https://open-meteo.com) (`/v1/forecast?latitude=..&longitude=..&current=temperature_2m,weather_code`), every 15 min.
- Panel (OK): current conditions, 5-day forecast, and a radar map in the Tokyo Night palette.
- Radar for Europe/Italy: the same source as Omastorm, the EUMETNET OPERA COMP DBZH mosaic from Open Radar
  Data (24 h cache, CC BY 4.0) at `https://s3.waw3-1.cloudferro.com/openradar-24h`, listed by
  `?list-type=2&prefix=YYYY/MM/DD/OPERA/COMP/`, keys `…/OPERA@YYYYMMDDTHHMM@0@DBZH.tiff`
  (Cloud-Optimised GeoTIFF, several MB each, all of Europe).
  That is too heavy for a 1 GB TV every few minutes. Options: (a) Omarchy Link renders a
  cropped Italy PNG on the PC and pushes it; (b) the TV reads only the Italy window of a COG
  overview with HTTP range requests; (c) refresh at most every 10–15 min, and only while the panel is open.
- Credits to show: Omastorm (MIT), EUMETNET OPERA / Open Radar Data (CC BY 4.0), OpenStreetMap (ODbL) if a basemap is used.

## Omarchy Link (future companion, docs only)

A LAN link between an Omarchy PC and the TV launcher.

- **PC side:** an Omarchy plugin (`id` e.g. `com.omarchytv.link`, kinds `bar-widget` + `service`) that runs a
  small HTTP/WebSocket daemon, advertised over mDNS as `_omarchytv._tcp`.
- **Pairing:** the TV shows a 6-digit code; the PC sends it once and gets a token (stored on both sides).
  All later requests carry the token; LAN only, no cloud.
- **TV side:** a `TvWidget` (`omarchytv.link`) that discovers the PC (NsdManager), holds the WebSocket while
  the home screen is resumed, and writes changes into `omarchytv.json`, which the launcher already reloads.
- **Features:**
  - PC status pane: CPU, RAM, now playing (MPRIS) and media controls (play/pause/next) sent back to the PC.
  - Theme sync: when `omarchy theme set` changes the theme, push `theme.accent/accent2`, colours and the
    current background (as a JPEG ≤ 3840x2160 into the app's files dir → `wallpaper` = that path).
  - Pushed widgets/info panes and pinned shortcuts (`dock`, extra `widgets` entries).
  - Commands: whitelisted actions from the TV (e.g. "lock PC", "start screensaver"), confirmed on the PC.
- **Limits:** no shell access from the TV, rate-limited pushes, everything off until paired.

## AI assistant ("> ask") over Omarchy Link — UI shipped in v2.3, transport proposed

The TV side exists: logo menu → `> ask` (or a colour key chosen in Settings → Assistant). It never replaces
Google Assistant: since v2.14 the mic/Search key opens the Grok Bot companion by voice on the home
(`"voiceKey": "companion"`, the default); `"launcher"` gives it to `> search`, `"assistant"` leaves it to Google.

Flow: mic → system speech recognizer on the TV (it-IT / en-US / de-DE from the UI language) → text →
paired Omarchy Linux PC → the PC calls the chosen AI provider with *its* keys → answer → TV shows it in a
terminal pane with a typewriter effect and, if enabled, reads it with Android TTS. **No API keys on the TV.**

Omarchy Link v1 (`omarchy-link/docs/PROTOCOL.md`) makes the **TV the server** (the PC opens no port),
so a TV→PC `POST /ai/ask` cannot exist. Proposed v1.1 additions, for the protocol owner to accept or change:

| Direction | Message | Data |
|---|---|---|
| PC → TV | `PUT /v1/ai/providers` (auth, 1 per 5 s) | `{"providers":[{"id":"grok","name":"Grok"},{"id":"grok-bot","name":"Grok Bot"},{"id":"claude","name":"Claude"},{"id":"chatgpt","name":"ChatGPT"}]}` → TV stores `link.aiProviders` |
| TV → PC | event `ask` | `{"id":"<uuid>","text":"…","lang":"it-IT","provider":"grok"}` (≤ 2000 chars, 1 per 3 s) |
| PC → TV | event `answer` | `{"id":"<uuid>","text":"…","done":true}` (chunks allowed with `done:false`; ≤ 16 KiB total; text only, never HTML) |
| PC → TV | event `answer_error` | `{"id":"<uuid>","error":"no_provider\|quota\|timeout","message":"…"}` |

Capability `ai-ask` appears in Status only when the PC plugin has at least one provider configured.
Settings → Assistant: provider (from `link.aiProviders`), "Read answers aloud" (TTS), mic key mode, `> ask` key.
Until a PC is paired the screen says "connect Omarchy Linux first".
