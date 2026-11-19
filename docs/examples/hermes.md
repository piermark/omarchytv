# OmarchyTV and Hermes Agent (Nous Research)

OmarchyTV 2.21 gives Hermes a screen: it can publish its pages as tiles on your TV's Home, show them full screen
or tiled, put a notification on the TV (read aloud if you like), ask you a question you answer with the remote,
take its colours from your Omarchy theme, and control Radiomarchy. Hermes lists MCP servers under `mcp_servers` in
`~/.hermes/config.yaml`; `/reload-mcp` in a chat picks up a change.

## 1. Hermes on the Omarchy PC (the simplest)

```yaml
mcp_servers:
  omarchytv:
    command: "omarchy-link"
    args: ["mcp"]            # the TV's MCP over the paired, signed link; add "--scope", "notify" for fewer tools
    timeout: 130             # tv_ask_user can wait up to 110 s for your answer
```

## 2. Hermes on an always-on box (a Pi, a NAS, a server) — straight to the TV

Make an app token on the PC: `omarchy-link token add "Hermes" --scope control` (or on the TV: Settings › Omarchy
Link › New app token, scan the QR code). Then on the box:

```yaml
mcp_servers:
  omarchytv:
    url: "http://192.168.1.50:47800/v1/mcp"     # the TV; its 100.x address over Tailscale
    headers:
      Authorization: "Bearer otva_…"
    timeout: 130
```

The token's scope decides the tools Hermes sees: `read` (state, theme, lists), `notify` (+ notifications,
questions, page badges), `control` (+ pages, tiles, remote, apps, Radiomarchy, diagnostics).

## 3. Hermes on another network — over Tailscale through the PC

On the PC: `omarchy-tv mcp --http --tailscale` (it prints the Bearer token). On Hermes' box:

```yaml
mcp_servers:
  omarchytv:
    url: "http://<pc-magicdns-name>:8990/mcp"
    headers:
      Authorization: "Bearer <token>"
```

An LG or Samsung TV: `omarchy-tv mcp --http --tailscale --bridge` (notifications, questions, one page).

## 4. Teach Hermes the TV: a skill

Copy `hermes-skill/omarchytv/` to `~/.hermes/skills/omarchytv/`. It tells Hermes when and how to use the TV:
publish a page as a tile with a badge for things it keeps working on, notify when a task ends, ask before doing
something irreversible, follow your theme.

## 5. A Hermes room on the TV Home

A Hermes cron job (or a room's routine) can leave its report on the TV Home instead of in a notification:
`tv_room_post` with `room: "hermes"` and one line, through `omarchy-link mcp`. The Home shows a pane titled
"hermes · room" with the last 5 lines and the time of each. Up to 3 rooms; a room quiet for a day leaves the Home.
The same from a shell: `omarchy-link room post hermes "Backup done, 412 GB" --from cron`.

## 6. The "studio dubai" dashboard, OmarchyTV style

What a Hermes user built by hand (a WebView app, a theme server, key handling) is now built in:

1. Hermes serves its page from the box (e.g. `python3 -m http.server 8080` in its output folder).
2. In the page: `<script src="http://<tv>:47800/v1/sdk/omarchytv.js">` then
   `OmarchyTV.connect({token: "otva_…"}).followTheme(); OmarchyTV.remote();`: the Omarchy colours, live, and the
   remote moves between the listings. [dashboard.html](dashboard.html) is a starting point.
3. `tv_publish_page {id: "dubai-flats", name: "Dubai flats", url: "http://<box>:8080/", icon: "D"}`: a tile on the
   TV's Home. When owners reply: `tv_page_badge {id: "dubai-flats", badge: 3}` and
   `tv_notify {title: "Building 71", text: "the owner replied", level: "warn"}`.
4. Before sending an email in your name: `tv_ask_user {question: "Book the visit Saturday 10:00?", choices: ["Yes",
   "Another time", "No"]}`, answered from the sofa.
