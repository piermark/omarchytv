# Integrations: dashboards, AI agents and scripts (v2.21)

OmarchyTV can be the screen of your setup: a dashboard on a stick that matches your Omarchy theme, an AI agent that
tells you on the TV when it is done, a script that switches the theme at sunset. This page lists every way in, from
the simplest (read-only, no pairing) to full control (MCP).

| What | Where | Auth | Since |
|---|---|---|---|
| Read the theme and the wallpaper | `GET /v1/theme`, `GET /v1/theme/wallpaper` on the TV (47800) or the LG/Samsung bridge (8765) | none, LAN / tailnet only | 2.21 |
| The JavaScript SDK | `GET /v1/sdk/omarchytv.js` on the TV | none | 2.21 |
| What is on screen, live | `GET /v1/state`, `GET /v1/stream` (Server-Sent Events) | app token, `read` | 2.21 |
| Show a message on the TV | `omarchy-link notify`, `POST /v1/notify`, MCP `tv_notify` | app token `notify`, or the paired PC | 2.21 |
| Ask the person at the TV | `omarchy-link ask`, `POST /v1/ask`, MCP `tv_ask_user` | app token `notify`, or the paired PC | 2.21 |
| Open a page full screen, or up to 4 tiled | `omarchy-link page`, `POST /v1/page`, MCP `tv_show_page` | app token `control`, or the paired PC | 2.21 |
| Agent pages as Home tiles, with a badge | `omarchy-link pages`, `PUT /v1/pages/<id>`, MCP `tv_publish_page`, `tv_page_badge` | `control` (badge: `notify`) | 2.21 |
| Radiomarchy and parts of OmarchyTV | `omarchy-link radio`, `omarchy-link open`, MCP `tv_radio`, `tv_open` | `read` / `control` | 2.21 |
| The same on LG / Samsung | `omarchy-tv notify`, `page`, `ask`, `omarchy-tv mcp --bridge` | the bridge's ask code | 2.21 |
| Diagnostics and logs | `omarchy-link tv logs`, `GET /v1/diagnostics`, MCP `tv_diagnostics` | app token `control`, or the paired PC | 2.21 |
| Control the TV from an AI agent | MCP: on the TV (`/v1/mcp`) or on the PC (`omarchy-tv mcp`) | app token (tools of its scope), PC token, MCP Bearer token | 2.12, more in 2.21 |
| The same, from another box | `omarchy-tv mcp --http --tailscale` | Bearer token, tailnet peers only | 2.21 |
| The same, from the cloud | `omarchy-tv mcp --cloudflare NAME --access` | Cloudflare Access + Bearer token; read + notify by default | 2.21 |
| Live AI agents and PC stats on the TV | `omarchy-link agents`, widgets, Remote panes | paired PC | 2.10–2.15 |

Everything that changes something on the TV needs either the paired PC or an **app token**:

```sh
omarchy-link token add "Kitchen dashboard" --scope read      # read: state, stream, MCP read tools
omarchy-link token add "Claude Code" --scope notify          # + notify and ask
omarchy-link token add "Hermes" --scope control              # + keys, apps, themes, pages, diagnostics
omarchy-link token                                           # list
omarchy-link token revoke 1a2b3c4d
```

The token (`otva_…`) is printed once; the TV keeps only its SHA-256. Settings › Omarchy Link › **App tokens** shows
how many there are and revokes them all. At most 16. Android TV only (LG and Samsung have the theme on the bridge).

## 1. One theme for every app

`GET /v1/theme` returns the TV's current theme. No pairing and no token: it is read-only, answered on the LAN (and on
the tailnet when Tailscale is allowed), with `ETag`/`304` and CORS, so a page served from any box can read it.

```js
// in a dashboard page
const tv = "http://192.168.1.50:47800";          // or the bridge: http://<pc>:8765 for LG / Samsung
let etag = "";
async function syncTheme() {
  const r = await fetch(tv + "/v1/theme", { headers: etag ? { "If-None-Match": etag } : {} });
  if (r.status === 304) return;                   // nothing changed
  etag = r.headers.get("ETag") || "";
  const t = await r.json();
  const s = document.documentElement.style;
  s.setProperty("--bg", t.background);
  s.setProperty("--fg", t.foreground);
  s.setProperty("--accent", t.accent);
  for (const [k, v] of Object.entries(t.colors)) s.setProperty("--" + k.replace(/_/g, "-"), v);
  document.body.style.backgroundImage = t.wallpaper ? `url(${tv}${t.wallpaper.url}?${t.wallpaper.sha256.slice(0, 8)})` : "none";
}
syncTheme();
setInterval(syncTheme, 15000);
```

The full schema (`omarchytv-theme/1`) is in [PROTOCOL.md](../pc-plugin/omarchy-link/docs/PROTOCOL.md), "Theme for
apps". Turn it off on the TV in Settings › Omarchy Link › **Share theme with apps on the network**.

The TV is the canonical source: when the PC pushes its Omarchy theme, the TV serves it (`"source": "omarchy-pc"`);
when you pick a theme on the TV, the TV serves that (`"source": "tv"`). Pages keep their colours even when the PC
sleeps.

## 2. Tell me on the TV

An agent or a script on the paired PC can put one line on the TV, over whatever is playing:

```sh
omarchy-link notify "Build finished: 0 errors" --from "Claude Code"
make test 2>&1 | tail -1 | omarchy-link notify --from tests      # text from stdin
omarchy-link notify --title Deploy --level alert "prod is down"   # shows at once, even over a film
```

Levels: `info` (default) waits while a film or an app is on screen and shows when you are back on Home, so a busy
agent never interrupts a film; `warn` and `alert` show at once. The TV prints `held` on stderr when it waited.

`--speak` reads it aloud with the TV's voice (Android TextToSpeech; LG/Samsung where the browser has
speechSynthesis). *warn* plays one short tone, *alert* two; `"link": {"notifySound": false}` in `omarchytv.json`
silences them.

```sh
omarchy-link watch -- make release      # runs it, then ✓ / ✗, the time and the last line on the TV (alert on failure)
```

Codex: `notify = ["omarchy-link", "notify", "-q", "--from", "Codex"]` in `~/.codex/config.toml` (its JSON event is
understood). More in [examples/agent-hooks.md](examples/agent-hooks.md).

It needs **agent alerts** on the TV (Settings › Omarchy Link, off by default). Exit codes: 0 shown, 4 alerts off on
the TV, 3 not paired. `--quiet` never fails, for hooks. At most 3 messages per 10 s, one line, 200 characters.

### Claude Code hook

`~/.claude/settings.json`:

```json
{
  "hooks": {
    "Stop": [{ "hooks": [{ "type": "command", "command": "omarchy-link notify -q --from 'Claude Code' 'done, your turn'" }] }],
    "Notification": [{ "hooks": [{ "type": "command", "command": "omarchy-link notify -q --from 'Claude Code' 'needs your answer'" }] }]
  }
}
```

Any other agent with hooks (Codex, Hermes, a CI runner on the PC) works the same way: call `omarchy-link notify`.

## 3. MCP: AI agents that use the TV

Two MCP servers, the same tools.

**On the PC** (`omarchy-tv mcp`), through the paired, signed and encrypted link:

```sh
omarchy-tv mcp --stdio                       # for Claude Code, Claude Desktop, Cursor on this PC
omarchy-tv mcp --http --port 8990            # http://127.0.0.1:8990/mcp, Bearer token in ~/.config/omarchy-tv/mcp-token
omarchy-tv mcp --http --tailscale            # on this PC's tailnet address, tailnet peers only (section 4)
omarchy-tv mcp --cloudflare NAME --access    # a named Cloudflare tunnel behind Access (CLOUDFLARE.md)
omarchy-tv mcp --stdio --scope notify        # fewer tools: read | notify | control
```

Claude Code: `claude mcp add omarchytv -- omarchy-link mcp` (the TV's own MCP over the paired link, no token to
copy; `--scope notify` for fewer tools) or `claude mcp add omarchytv -- omarchy-tv mcp --stdio`. For an LG or Samsung
TV: `omarchy-tv mcp --stdio --bridge` (notify, ask, pages and the theme, through `omarchy-tv webos-serve`).

**On the TV** (`POST /v1/mcp`, Android TV), with an app token (`Authorization: Bearer otva_…`, the tools of its
scope), the paired PC's token, or an OTV1-signed request.

| Tool | Scope | Does |
|---|---|---|
| `tv_status`, `tv_get_state` | read | what is on screen, the app, the theme, the music |
| `tv_get_theme`, `tv_list_themes`, `tv_list_apps` | read | the theme payload, the lists |
| `tv_ai_status`, `tv_cinemarchy` | read | live agents and stats, the Cinemarchy library (TV server only) |
| `tv_notify` | notify | a line on the TV, like `omarchy-link notify` |
| `tv_ask_user`, `tv_ask_result` | notify | a question answered on the remote; waits for the answer |
| `tv_set_theme`, `tv_push_theme` | control | themes (`tv_push_theme`: PC server only) |
| `tv_send_key`, `tv_launch_app` | control | the remote and the apps |
| `tv_screensaver` | control | the text screensaver |
| `tv_show_page`, `tv_close_page` | control | a LAN / tailnet page full screen, or `urls` (up to 4) tiled |
| `tv_list_pages` | read | the pages published as Home tiles |
| `tv_page_badge` | notify | the count on a page's tile |
| `tv_publish_page`, `tv_remove_page` | control | publish a page as a Home tile, remove it |
| `tv_radio_now` | read | what Radiomarchy plays |
| `tv_radio`, `tv_open` | control | play / pause / next / previous / stop; open home, radiomarchy, cinemarchy, arcade, office |
| `tv_diagnostics` | control | version, device, network, recent log lines |

A local server (`--stdio`, `--http`, `--tailscale`) offers every tool unless you pass `--scope`; one reachable from
the internet (`--tunnel`, `--cloudflare`) offers read + notify unless you pass `--scope control`.

Never: a shell, files on the PC, arbitrary intents. Tools are a fixed list.

## 4. MCP and Tailscale together

Your agent does not have to run on the PC next to the TV. With Tailscale on the PC:

```sh
omarchy-tv mcp --http --tailscale
# OmarchyTV MCP HTTP server listening on http://100.100.1.1:8990/mcp (tailnet peers only)
# Authorization: Bearer <token>
```

The server listens on the PC's tailnet address only and refuses anything that is not a tailnet peer, before it even
checks the token. Point the agent on your always-on box (Hermes, a home server running Claude Code) at
`http://<pc-magicdns-name>:8990/mcp` with that Bearer token. Nothing is exposed to the internet; prefer this to
`--tunnel` (cloudflared), which gives a public URL.

For the TV itself on the tailnet (Android TV with the Tailscale app, or LG/Samsung behind a subnet router), see
[TAILSCALE.md](TAILSCALE.md).

## 5. The JavaScript SDK

The TV serves `omarchytv.js` (ES5, no dependencies; any Android TV WebView). A page anywhere on the LAN or tailnet:

```html
<script src="http://192.168.1.50:47800/v1/sdk/omarchytv.js"></script>
<script>
  var tv = OmarchyTV.connect({ token: "otva_…" });         // the TV is where the script came from
  tv.followTheme();                                           // --otv-background, --otv-accent, --otv-color4, --otv-wallpaper …
  tv.on("state", function (s) { show(s.screen, s.app && s.app.label, s.nowPlaying); });
  tv.on("notify", function (n) { toast(n.from + ": " + n.text); });
  document.querySelector("#deploy").onclick = function () {
    tv.ask("Deploy to production?", ["Yes", "Not now"]).then(function (a) { if (a === "Yes") deploy(); });
  };
</script>
```

| Call | Needs | Returns |
|---|---|---|
| `theme()`, `followTheme(el?, cb?)`, `applyTheme(t, el?)`, `wallpaperUrl()` | nothing | the theme payload; CSS variables |
| `state()` | `read` | `{screen: home\|app\|screensaver\|off, app, theme, nowPlaying, time}` |
| `on(type, cb)` | `read` | events `state`, `theme`, `notify`, `ask_answer`; one stream, reconnects by itself |
| `notify(text)` | `notify` | shown on the TV (agent alerts on) |
| `ask(question, choices?, {timeout})` | `notify` | the answer, or `null` if nobody answered |
| `badge(pageId, n)` | `notify` | the count on the page's Home tile |
| `close()` | | stops the stream |
| `OmarchyTV.remote(handlers?, selector?)` | | the remote: arrows move the focus, OK clicks, keys by name (`red`, `play_pause`…) |

```js
OmarchyTV.remote({
  red: function () { refresh(); return false; },          // a colour key
  any: function (key) { console.log("key", key); }         // every key by name
});                                                        // focusable: a, button, [data-otv-focus]
```

## 6. Live state (`GET /v1/state`, `GET /v1/stream`)

```sh
curl -H "Authorization: Bearer otva_…" http://192.168.1.50:47800/v1/state
# {"screen":"app","app":{"package":"com.netflix.ninja","label":"Netflix"},"theme":"tokyo-night","nowPlaying":null,…}
curl -N "http://192.168.1.50:47800/v1/stream?token=otva_…"
# event: state / event: theme / event: notify / event: ask_answer, a ": ping" every 20 s
```

`?token=` exists because a browser `EventSource` cannot send headers; it works only on these two read routes. At
most 4 streams at once; each lasts an hour and the browser reconnects.

## 7. Ask the person at the TV

```sh
omarchy-link ask "Merge the PR?" --choice Merge --choice Wait --from "Claude Code"   # prints the answer
```

The question opens over whatever is playing (or, if Android keeps it back, waits and opens when you go Home), in the
theme's colours, with up to 4 answers on the remote. BACK answers later; it closes itself when it expires
(10–300 s, default 120). Exit codes: 0 answered, 5 no answer in time, 4 agent alerts off on the TV. In an agent:
MCP `tv_ask_user` (waits for the answer) and `tv_ask_result`.

## 8. Pages full screen, tiled, and as Home tiles

```sh
omarchy-link page http://192.168.1.20:8080/        # a dashboard on the Pi
omarchy-link page http://192.168.1.20:8080/ http://192.168.1.21:3000/ http://192.168.1.22/   # tiled, Hyprland style
omarchy-link page --close
omarchy-link pages add dubai "Dubai flats" http://192.168.1.20:8080/ --icon D --badge 3      # a Home tile
omarchy-link pages badge dubai 5
omarchy-link pages                                  # list; show ID; remove ID
```

Tiled: up to four windows (two on a low-RAM TV), with gaps, the accent border on the active window and Hyprland's
dwindle layout for three; 1-4, the colour keys or CH+/CH- switch windows, the arrows and OK go to the active one.
Home tiles: at most 16; the badge shows in the tile's name ("Dubai flats · 5"); Walker opens all of them tiled.

Only `http://` to a literal LAN address (or a `100.x` tailnet address with Tailscale on): no internet pages, no
DNS names. The page stays on its own host (links elsewhere are not followed), has no access to the TV's files and
no JavaScript bridge; BACK closes it. With the SDK, the page itself can follow the theme and ask questions.

## 9. Diagnostics

```sh
omarchy-link tv logs            # the TV's last 100 Omarchy Link log lines
omarchy-link tv diagnostics     # + launcher version, device, GPU, RAM, LAN / tailnet address, settings
```

Nothing secret: no tokens, no keys, no file paths. Useful in a bug report, and for an agent that helps you set up
the TV (MCP `tv_diagnostics`, scope control).

## 10. LG and Samsung

webOS and Tizen have no Omarchy Link server: the PC bridge (`omarchy-tv webos-serve`) keeps an inbox the TV app
reads every 3 s with its ask code. Notifications (mako style), a page full screen (one at a time) and questions work
the same; tiles, state, Radiomarchy control and the remote are Android TV only.

```sh
omarchy-tv notify "backup done" --title NAS --level warn
omarchy-tv page http://192.168.1.20:8080/      # --close
omarchy-tv ask "Merge?" --choice Merge --choice Wait
omarchy-tv mcp --stdio --bridge                 # the same MCP tools for agents (the ones LG/Samsung can do)
```

## 11. Ready-made examples

| File | What |
|---|---|
| [examples/agent-hooks.md](examples/agent-hooks.md) | Claude Code, Codex, `omarchy-link watch`, a cron job with curl |
| [examples/github-actions-notify-tv.yml](examples/github-actions-notify-tv.yml) | CI failed → an alert on the TV, read aloud, over Tailscale |
| [examples/home-assistant.yaml](examples/home-assistant.yaml) | doorbell → Radiomarchy paused, alert spoken, camera full screen; laundry done |
| [examples/dashboard.html](examples/dashboard.html) | a dashboard page: theme, live state, now playing, notifications, ask, the remote |
| [examples/hermes.md](examples/hermes.md) | Hermes Agent: `mcp_servers` on the PC, on a box (straight to the TV with an app token), over Tailscale; the "studio dubai" dashboard |
| [examples/hermes-skill/omarchytv/SKILL.md](examples/hermes-skill/omarchytv/SKILL.md) | a skill that teaches Hermes (and any agent with skills) when and how to use the TV |

## 12. A dashboard on a stick, the way a Hermes user built it

1. An always-on box (a Raspberry Pi, a NAS) serves the dashboard page and its data, so the TV never goes blank when
   the laptop sleeps.
2. The page reads its colours and wallpaper from the TV with the SDK (`followTheme()`): no second theme server.
3. On an Android TV with OmarchyTV, `omarchy-link page http://<box>/` shows it full screen (or a tiny WebView app on
   the stick, as before); Remote panes can show tiles from the same box.
4. Agents on the box report with `tv_notify`, ask with `tv_ask_user`, over the tailnet (section 4) or with an app
   token. A Raspberry Pi recipe: [RASPBERRY-PI.md](RASPBERRY-PI.md). Cloud agents: [CLOUDFLARE.md](CLOUDFLARE.md).
