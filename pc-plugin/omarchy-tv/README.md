# omarchy-tv — Omarchy Front-End & MCP Bridge for OmarchyTV (`2.12.0-beta`)

`omarchy-tv` is the desktop CLI and menu front-end built on top of **omarchy-link** (SPAKE2 pairing, HMAC-signed link, Waybar session). MIT License, © 2026 Marco Porro.
Pure Python 3.10+ standard library; optional Pillow (backgrounds → WebP) or ffmpeg.

| Command | Description |
|---|---|
| `omarchy-tv` / `menu` | Interactive menu via `omarchy-menu-select` (Omarchy 4.5+), `walker --dmenu`, `gum choose`, or numbered terminal list; uses active theme colors and `notify-send` |
| `pair`, `status`, `cast`, `remote KEY` | Delegates to `omarchy-link` |
| `themes list / push NAME / push --all / select NAME [BG] / delete NAME` | Syncs all **26 MIT Omarchy themes** and user themes in `~/.config/omarchy/themes/*` → TV (`PUT /v1/themes/<name>`, ZIP: `theme.json` + backgrounds ≤3840×2160, max **50 MB** total on TV) |
| `icons list / push [PACKAGE\|--all] / delete PACKAGE` | Custom 16:9 PNG app banners from `~/.config/omarchy-tv/banners/<package>.png` |
| `mcp [--stdio\|--http] [--port 8990] [--tunnel]` | **MCP Server (JSON-RPC 2.0)** exposing TV tools (`tv_get_status`, `tv_list_themes`, `tv_set_theme`, `tv_send_key`, `tv_launch_app`, `tv_cast_media`, `tv_show_notification`, `tv_open_ai_webapp`). HTTP mode binds to `127.0.0.1:8990` with a 256-bit Bearer token (`~/.config/omarchy-tv/mcp-token`, `0600`) |
| `screen start [--audio] / stop` | Live PC screen sharing ("PC Screen", opt-in): `gpu-screen-recorder` (hw) or `wf-recorder` → H.264 MPEG-TS over one-time LAN URL for the paired TV IP; ExoPlayer ~0.5 s low-latency playback |
| `update <APK\|IPK> [--tv IP:5555\|DEVICE] [--yes] [--dry-run]` | **Android TV (`.apk`)**: `adb install -r` only (never `-d` or uninstall), verifies signer cert `90aa68d9…a33d` and monotonic `versionCode`. **LG webOS (`.ipk`)**: verifies `appinfo.json` (`id == com.omarchytv.home`) and installs via `ares-install` |
| `webos-serve [--port 8765] [--bind 0.0.0.0]` | Lightweight LAN HTTP + CORS bridge for LG webOS TVs (`/v1/status`, `/v1/themes`, `/v1/wallpaper`) |
| `stats` | Prints the live telemetry payload sent every 5 s while the TV home screen is visible (`statsIntervalS`) |

## MCP Server (`omarchy-tv mcp`) & Direct TV `/mcp`

Starting in **2.12.0-beta**, both the TV launcher (`POST http://<tv-ip>:8999/mcp`) and the PC plugin (`omarchy-tv mcp`) speak the **Model Context Protocol (MCP, `2024-11-05`, JSON-RPC 2.0)** so **Grok Bot**, **Claude Desktop**, **Cursor**, **Hermes**, or any MCP client can inspect and control the TV safely:

- **Available MCP Tools (strict TV-only whitelist):**
  - `tv_get_status`: Active theme, wallpaper, workspace, volume, and capabilities.
  - `tv_list_themes`: All 26 built-in MIT Omarchy themes + any custom themes installed on the TV.
  - `tv_set_theme`: Switch the TV to any theme (e.g. `jade-horse`, `year-of-the-horse`, `hermarchy`, `tokyo-night`) and optional wallpaper.
  - `tv_send_key`: Send D-pad/remote keys (`home`, `back`, `up`, `down`, `left`, `right`, `select`, `play_pause`, `mute`, `volume_up`, `volume_down`, `menu`, `1`..`5`).
  - `tv_launch_app`: Launch an installed Android TV package or built-in WebApp (`web:grok-bot`, `web:chatgpt`, `web:claude`, `web:hermes`).
  - `tv_open_ai_webapp`: Open `grok-bot`, `grok-x`, `chatgpt`, `claude`, or `hermes` directly on the TV screen.
  - `tv_cast_media`: Cast a video/audio/image URL or text note to the TV.
  - `tv_show_notification`: Display a terminal toast notification on the TV screen.
- **Security:** `omarchy-tv mcp --http` binds strictly to `127.0.0.1` by default and requires `Authorization: Bearer <token>` using the 256-bit random token generated at `~/.config/omarchy-tv/mcp-token` (permissions `0600`). No arbitrary shell execution or filesystem tools exist.

## Custom App Icons (Banners)

Since **2.11.0-beta+**, you can replace any app's tile banner on the TV home screen with your own 16:9 PNG (`≤ 1 MB`):

1. Find the package name: `omarchy-link apps` (or `adb shell pm list packages`).
2. Save a 16:9 PNG (`320×180` or `640×360`) as `~/.config/omarchy-tv/banners/<package>.png`.
3. Push to the TV: `omarchy-tv icons push <package>` or `omarchy-tv icons push --all` (or via ADB: `adb push <pkg>.png /sdcard/Android/data/com.omarchytv.home/files/banners/<pkg>.png`).

## GrokBot Office (2.20, optional)

`omarchy-tv office push bots.json` sends your bots to the TV's GrokBot Office (Android: `PUT /v1/office`; LG and
Samsung read them from `omarchy-tv webos-serve`); `office show` and `office clear` too. Bots carry an optional
`provider` (default `grok`). The shape is in `docs/GROKBOT-DIRECT.md`; Claude and ChatGPT bots are hired with
`omarchy-link office hire` ([OFFICE.md](../omarchy-link/docs/OFFICE.md)). Nothing is made up: no bots, an
empty office.

## Language

Menus and messages follow the system language automatically (`LC_ALL` > `LC_MESSAGES` > `LANG`, supporting English and Italian), or `"language": "en" | "it" | "auto"` in `~/.config/omarchy-link/config.json`.

## Unit Tests
```bash
python3 -m unittest discover -s tests -v
```
