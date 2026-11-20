# Remote access and AI agents: the OmarchyTV 2.21 guide

This guide answers two questions:

- How do I reach my TV when I am not at home (Tailscale, Cloudflare Tunnel)?
- How do I plug my AI agents into it (MCP, hooks, Claude Code, Codex, Hermes, any MCP client)?

It is the practical path, step by step. The full reference is in [TAILSCALE.md](TAILSCALE.md),
[CLOUDFLARE.md](CLOUDFLARE.md) and [INTEGRATIONS.md](INTEGRATIONS.md).

"Settings" means OmarchyTV's own settings: the logo menu (or the system page, key 5) › **All OmarchyTV settings**.

Before anything else, on the TV turn on **Settings › Grok Bot & AI › AI agent alerts while you watch**. Without it the
TV accepts no notifications or questions (`omarchy-link notify` exits with 4).

---

## 1. Pick your path

| Where is the agent or the app? | Use | Exposed to the internet? |
|---|---|---|
| On the Omarchy PC paired with the TV | `omarchy-link mcp` (stdio), `omarchy-link notify / ask / page` | no |
| Another machine on your home LAN | an app token, straight to the TV (`http://<tv>:47800`) | no |
| Somewhere else, but it can join your tailnet (laptop, NAS, VPS, a Pi) | **Tailscale** (section 2) | no |
| A cloud service that cannot join a tailnet | **Cloudflare Tunnel + Access** (section 3) | yes, behind Cloudflare Access |
| A quick test from anywhere | `omarchy-tv mcp --tunnel` (a temporary trycloudflare URL) | yes, a random URL; for tests only |

Rule of thumb: stay on the LAN or the tailnet whenever you can. Cloudflare is for agents that live in the cloud.

## 2. Tailscale, step by step

### 2.1 The Android TV on your tailnet

1. On the TV: Settings › Connect Omarchy Linux › **Tailscale app** (opens or installs the official app). Sign in with
   the same account as your PC.
2. Same page: turn on **Allow Tailscale (tailnet)**. With it off, the TV refuses anything that is not your LAN, even
   from your own tailnet.
3. On the PC:

   ```sh
   omarchy-link tailscale on            # the PC side of the same switch
   omarchy-link discover                # finds the TV on the LAN and the tailnet
   omarchy-link pair living-tv          # a MagicDNS name, a 100.x address or [fd7a:…] over IPv6
   omarchy-link tailscale status
   ```

4. Optional: `omarchy-link tailscale acl` prints a tailnet ACL that lets only your devices reach port 47800.
5. On a 1-2 GB TV: `omarchy-link tv keepalive on`, so the TV answers right after a reboot even before anyone opens
   OmarchyTV.

From then on every `omarchy-link` command works from anywhere: notify, ask, pages, themes, MCP, casts.

### 2.2 Another machine talks to the TV directly

On the PC, make a token for that machine. `--remote` prints its whole setup:

```sh
omarchy-link token add "NAS" --scope notify --remote
```

On the other machine, the printed lines look like this (with your TV's address and token):

```sh
curl -s -X POST http://100.101.7.3:47800/v1/notify -H 'Authorization: Bearer otva_…' -d '{"text":"backup done"}'
claude mcp add --transport http omarchytv http://100.101.7.3:47800/v1/mcp --header 'Authorization: Bearer otva_…'
```

Scopes:

- **read**: state, theme, lists.
- **notify**: read, plus notifications, questions and badges.
- **control**: notify, plus keys, apps, themes, pages and diagnostics.

Give each machine the smallest scope it needs, and revoke with `omarchy-link token revoke ID`.

From 2.22 you also decide how loud each one may be: `--max-level warn` (or `info`) lowers anything above it on the TV,
without the voice, whatever level the agent picked. Change it later with `omarchy-link token level ID warn`. The rule is
yours, not the model's.

### 2.3 Through the PC instead (an MCP server on the tailnet)

```sh
omarchy-tv mcp --http --tailscale       # prints http://<pc tailnet ip>:8990/mcp and a Bearer token
```

The server listens only on the PC's tailnet address and refuses anything that is not a tailnet peer, before it even
checks the token. Use this when the agent should not hold a TV token, or for an LG / Samsung TV (add `--bridge`).

### 2.4 LG and Samsung

These TVs cannot run Tailscale. Put a small box on the LAN as a subnet router: a Raspberry Pi with Tailscale and
`omarchy-tv webos-serve`. Step by step: [RASPBERRY-PI.md](RASPBERRY-PI.md) and [TAILSCALE.md](TAILSCALE.md).

## 3. Cloudflare Tunnel, step by step

For an agent in the cloud that cannot join your tailnet. The TV itself is never on the internet: the tunnel ends at
`omarchy-tv mcp` on your PC, which talks to the TV over the paired link.

1. A quick test (a random URL, no Access):

   ```sh
   omarchy-tv mcp --tunnel             # prints https://<random>.trycloudflare.com/mcp and the Bearer token
   ```

   It offers read + notify only. Stop it when you are done.

2. For good: a named tunnel on your domain, behind **Cloudflare Access**.

   ```sh
   omarchy-tv cloudflare setup omarchytv-mcp tv-mcp.example.com     # tunnel + DNS route (asks first)
   omarchy-tv mcp --cloudflare omarchytv-mcp --access-team TEAM --access-aud AUD
   omarchy-tv cloudflare service --cloudflare omarchytv-mcp --access-team TEAM --access-aud AUD   # a systemd unit
   ```

   - With `--access-team` and `--access-aud`, every request must carry a valid Cloudflare Access JWT (RS256, checked
     against your team's keys), on top of the Bearer token.
   - Without Access, `omarchy-tv` warns you: then anyone with the URL and the token gets in.
   - The cloud agent gets read + notify. Add `--scope control` only if it really must drive the TV.

Details, the Access policy and service tokens: [CLOUDFLARE.md](CLOUDFLARE.md).

## 4. AI agents, one by one

**Let the agent do it:** the [omarchytv-setup skill](../skills/omarchytv-setup/SKILL.md) walks Claude Code, Codex or
Hermes through every step below (install, pair, MCP, tokens, Tailscale) and asks you for the parts on the TV.
Install it with the lines in [skills/README.md](../skills/README.md), then ask your agent to "set up OmarchyTV".

### Claude Code (on the PC)

```sh
claude mcp add omarchytv -- omarchy-link mcp                   # every tool, no token to copy
claude mcp add omarchytv -- omarchy-link mcp --scope notify    # only read + notify + ask
omarchy-link agents approvals on                               # its permission prompts on the TV
```

- **Approvals**: the TV asks first (Allow / Deny / On the PC). No answer in 90 s, the TV off, or "On the PC" falls
  back to Claude Code's own prompt. Nothing is allowed without an answer.
- **"Done" notifications**: the Claude Code hook in [examples/agent-hooks.md](examples/agent-hooks.md).

### Codex

In `~/.codex/config.toml`:

```toml
notify = ["omarchy-link", "notify", "-q", "--from", "Codex"]
```

The TV shows Codex's last message when a turn ends. For MCP, register `omarchy-link mcp` the same way as any stdio
server.

### Hermes Agent

- **Hermes on the PC**: in `~/.hermes/config.yaml`, under `mcp_servers`, add `command: "omarchy-link"`,
  `args: ["mcp"]`.
- **Hermes on another box**: use the block that `token add … --remote` prints (section 2.2).
- **The skill**: copy `examples/hermes-skill/omarchytv/` to `~/.hermes/skills/omarchytv/`. It teaches Hermes when to
  notify, when to ask before something irreversible, when to post to a room, and how to make pages in your theme.
- **Cron reports**: `tv_room_post` with `room: "hermes"` puts each report on a Home pane instead of interrupting.

All of this is in [examples/hermes.md](examples/hermes.md).

### Any MCP client (Cursor, Claude Desktop, scripts)

| How it connects | Command or URL |
|---|---|
| stdio, on the PC | `omarchy-link mcp` (or `omarchy-tv mcp --stdio`) |
| HTTP, on the LAN or tailnet | `http://<tv>:47800/v1/mcp` with `Authorization: Bearer otva_…` |
| HTTP, through the PC | `omarchy-tv mcp --http [--tailscale]` |

The tools by scope:

- **read**: `tv_status`, `tv_get_state`, `tv_get_theme`, `tv_list_themes`, `tv_list_apps`, `tv_ai_status`,
  `tv_cinemarchy`, `tv_radio_now`, `tv_list_pages`.
- **notify**: `tv_notify`, `tv_ask_user`, `tv_ask_result`, `tv_page_badge`. `tv_room_post` and `tv_remind` are
  answered by `omarchy-link mcp` on the PC.
- **control**: `tv_set_theme`, `tv_send_key`, `tv_launch_app`, `tv_screensaver`, `tv_show_page`, `tv_close_page`,
  `tv_publish_page`, `tv_remove_page`, `tv_radio`, `tv_open`, `tv_diagnostics`.

There is never a shell, never files on the PC, and never arbitrary intents.

### Grok Bot

The Grok Bot on the TV talks to Tom Ballard's Agent Desktop (v0.3.3) on the PC; see the README's Grok Bot section.

### Scripts, CI and Home Assistant

- **GitHub Actions**: an alert on the TV when CI fails, read aloud, over Tailscale
  ([examples/github-actions-notify-tv.yml](examples/github-actions-notify-tv.yml)).
- **Home Assistant**: doorbell → camera full screen, a spoken alert, the laundry is done
  ([examples/home-assistant.yaml](examples/home-assistant.yaml)).
- **A dashboard page in your theme**: [examples/dashboard.html](examples/dashboard.html) and the SDK
  (`/v1/sdk/omarchytv.js`).
- **The PC's own events**: `omarchy-link hook add all` (boot, update, battery, font, theme) and
  `omarchy-link remind 20m "…"`.

## 5. Security in one screen

- **Pairing**: the paired PC signs every request (OTV1) and encrypts it when python-cryptography is installed.
- **App tokens**: kept on the TV only as a SHA-256 hash. Their scope is enforced by the TV. Revoking one closes its
  live stream at once. At most 16.
- **LAN only by default**: the tailnet needs both switches (TV and PC), and the internet is reached only through
  your own Cloudflare tunnel, which ends on the PC, never on the TV.
- **LG/Samsung bridge**: agents have their own key and cannot answer their own questions.
- **Approvals and questions**: no answer always means no.

## 6. When something does not work

| Symptom | Fix |
|---|---|
| `notify` exits 4, "agent alerts are off" | TV: Settings › Grok Bot & AI › AI agent alerts while you watch |
| "The TV refused this address" (403 `not_lan`) | turn on Allow Tailscale (tailnet) on the TV and `omarchy-link tailscale on` |
| Nothing answers right after the TV reboots | `omarchy-link tv keepalive on` (1-2 GB TVs), or open OmarchyTV once |
| The cloud agent gets 403 | the Access JWT is missing or for another application: check `--access-team` and `--access-aud` |
| `omarchy-tv mcp --bridge`: "is for Android TV" | that tool does not exist on LG / Samsung (keys, apps, Radiomarchy) |
| Anything else | `omarchy-link tv diagnostics` and `omarchy-link tv logs` (no secrets inside) for a bug report |
