# Integrations: dashboards, AI agents and scripts (v2.21)

OmarchyTV can be the screen of your setup: a dashboard on a stick that matches your Omarchy theme, an AI agent that
tells you on the TV when it is done, a script that switches the theme at sunset. This page lists every way in, from
the simplest (read-only, no pairing) to full control (MCP).

| What | Where | Auth | Since |
|---|---|---|---|
| Read the theme and the wallpaper | `GET /v1/theme`, `GET /v1/theme/wallpaper` on the TV (47800) or the LG/Samsung bridge (8765) | none, LAN / tailnet only | 2.21 |
| Show a message on the TV | `omarchy-link notify`, `POST /v1/notify`, MCP `tv_notify` | paired PC | 2.21 |
| Control the TV from an AI agent | MCP: on the TV (`/v1/mcp`) or on the PC (`omarchy-tv mcp`) | paired PC token / Bearer token | 2.12, more tools in 2.21 |
| The same, from another box | `omarchy-tv mcp --http --tailscale` | Bearer token, tailnet peers only | 2.21 |
| Live AI agents and PC stats on the TV | `omarchy-link agents`, widgets, Remote panes | paired PC | 2.10–2.15 |

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
```

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
omarchy-tv mcp --http --tailscale            # on this PC's tailnet address, tailnet peers only (see below)
```

Claude Code: `claude mcp add omarchytv -- omarchy-tv mcp --stdio`.

**On the TV** (`POST /v1/mcp`, Android TV), with `Authorization: Bearer <the paired PC token>` or an OTV1-signed
request.

| Tool | Does |
|---|---|
| `tv_status` | theme, foreground app, capabilities |
| `tv_get_theme` | the `GET /v1/theme` payload (2.21) |
| `tv_notify` | a line on the TV, like `omarchy-link notify` (2.21) |
| `tv_list_themes`, `tv_set_theme`, `tv_push_theme` | themes |
| `tv_send_key`, `tv_list_apps`, `tv_launch_app` | the remote and the apps |
| `tv_screensaver` | the text screensaver |
| `tv_ai_status`, `tv_cinemarchy` | live agents and stats, the Cinemarchy library (TV server) |

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

## 5. A dashboard on a stick, the way a Hermes user built it

1. An always-on box (a Raspberry Pi, a NAS) serves the dashboard page and its data, so the TV never goes blank when
   the laptop sleeps.
2. The page reads its colours and wallpaper from `GET /v1/theme` on the TV (section 1): no second theme server.
3. A tiny WebView app on the stick shows the page; OmarchyTV's Remote panes can show tiles from the same box.
4. Agents on the box report through MCP `tv_notify` over the tailnet (section 4).
