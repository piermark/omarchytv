# OmarchyTV 2.21: user guide

This guide covers OmarchyTV 2.21: what is new, where each switch is, which keys to press, and what you can do with
your PC, your AI agents and your other apps. 2.21 is built on 2.20.0. For 2.20 (Radiomarchy, fractals, Pulse,
GrokBot Office) see [OMARCHYTV-2.20-GUIDE.md](OMARCHYTV-2.20-GUIDE.md).

The full reference for developers, with every route, scope and example, is [INTEGRATIONS.md](INTEGRATIONS.md). The
short tour is [WHATS-NEW-2.21.md](WHATS-NEW-2.21.md).

Platforms:

- **Android TV / Google TV**: the launcher APK. Everything in this guide works there.
- **LG webOS and Samsung Tizen**: the TV web app plus `omarchy-tv webos-serve` on the PC (the "bridge"). Notifications,
  questions, one page and the theme work there (section 9).
- **The PC**: an Omarchy Linux PC (or a Mac) with the `omarchy-link` and `omarchy-tv` plugins. Commands below run there.

---

## 1. The switches on the TV (Android)

| Where | Setting | Default | What it does |
|---|---|---|---|
| Settings › Grok Bot & AI | **AI agent alerts while you watch** | off | Lets notifications and questions from your PC, agents and apps appear on the TV. Turn it on first. |
| Settings › Connect Omarchy Linux | **Share theme with apps on the network** | on | Other apps on your LAN can read the theme colours and the wallpaper. Nothing else is shared without a token. |
| Settings › Connect Omarchy Linux | **App tokens** | 0 | How many app tokens exist. OK revokes them all. Any app still connected is cut off at once. |
| Settings › Connect Omarchy Linux | **New app token** | | Makes a token and shows it once, as text and as a QR code. |
| Settings › Connect Omarchy Linux | **Allow Tailscale (tailnet)** | off | Accepts your tailnet as well as the LAN (section 6). |
| Settings › Connect Omarchy Linux | **Tailscale app** | | Opens or installs the official Tailscale app. |
| Settings › Connect Omarchy Linux | **Keep Link reachable after boot** | auto | auto = on above 2.5 GB of RAM, off on 1-2 GB TVs. On keeps a small service running so the PC and agents reach the TV right after a reboot. |
| Settings › Wallpaper | **Always-animated wallpaper** | on for the default | Loops the Home wallpaper's animation. |
| `omarchytv.json` | `"link": {"notifySound": false}` | true | No tone and no voice with notifications. |

Settings › System info shows the TV's state: version, memory, network, Link, the last log lines.

## 2. Home: Last Horizon "Eyes Wide" again

New installs, and anyone still on the fractals that 2.20 set by default, get **Last Horizon "Eyes Wide"** by Erik
Johansson (@erikrjohansson): eyes that open and close in a loop. The music stays off on Home.

- A wallpaper or theme you picked yourself is kept.
- The fractals are still there in Settings › Wallpaper and in the screensaver.
- Weak TVs (2.5 GB of RAM or less, or a GPU like the Sony's Mali-T8xx) play a copy at 540p and 15 fps (134 KB).
- If a TV cannot show at least half the frames, it stops on the still image and remembers this. Turning
  **Always-animated wallpaper** off and on gives it another try.
- On weak TVs, the other themes' intros (Cumulus) show their still image instead of a 720p video.
- From the PC: `omarchy-link tv home` tells you what the Home shows and whether the loop stopped.

## 3. Notifications, voice and questions

Turn on **AI agent alerts while you watch** first.

```sh
omarchy-link notify "Tests green" --from "Claude Code"            # info: waits while a film plays
omarchy-link notify --level warn "Disk at 90%"                     # one tone, shows now
omarchy-link notify --level alert --speak "prod is down"           # two tones, read aloud
omarchy-link watch -- make release                                 # ✓ or ✗ on the TV when it ends
omarchy-link ask "Merge the PR?" --choice Merge --choice Wait      # prints the answer
```

- Notifications look like mako's: top right, coloured by level, in the theme's colours.
- *info* waits until you are back on Home if a film is playing; *warn* and *alert* show at once.
- On a question: ◀ ▶ choose, **OK** answers, **BACK** leaves it for later. No answer in time counts as no.
- A question answered elsewhere, or expired, leaves the screen by itself.

Exit codes for scripts: 0 shown, 4 agent alerts are off, 1 the TV refused the address (for example the Tailscale
switch is off), 3 not paired. `--quiet` never fails.

## 4. Approve your agent's actions from the couch

```sh
omarchy-link agents approvals on      # off removes it; with no argument it says on or off
```

When Claude Code would ask "Allow this command?", the TV asks first, showing the project and the command:
**Allow**, **Deny** or **On the PC**.

- Allow and Deny answer Claude Code.
- If nobody answers within 90 s, the TV is off, or you pick **On the PC**, Claude Code shows its own prompt as
  usual. Nothing is ever allowed without an answer.
- It adds a `PermissionRequest` hook to `~/.claude/settings.json` and keeps a backup next to it.

## 5. Pages, tiles, rooms and the couch console

```sh
omarchy-link page http://192.168.1.20:8080/                        # a LAN page full screen
omarchy-link page http://nas.lan:8080/ http://192.168.1.21:3000/   # up to 4, tiled like Hyprland
omarchy-link page --close
omarchy-link pages add home "Home" http://192.168.1.20:8080/ --icon H --badge 2   # a tile on the Home
omarchy-link pages badge home 5
omarchy-link room post hermes "Backup done, 412 GB" --from cron    # a room pane on the Home
omarchy-link console                                               # this tmux pane on the TV, read-only
omarchy-link console --file ~/build.log                            # or the end of a log
```

- **Pages**: only `http://` addresses on your LAN, or on your tailnet with the switch on. 1-4 or the colour keys
  switch windows; BACK closes.
- **Rooms**: a pane with a room's last 5 lines and the time of each. At most 3 rooms; a room quiet for a day
  leaves the Home. `room clear NAME` removes one.
- **Console**: the page refreshes once a second, only the paired TV's address may open it, and nothing can be typed
  from the TV. Ctrl+C on the PC stops it and
  closes the page. Run your agent in the tmux session of the Super+~ drop-down terminal and watch it from the sofa.

## 6. From anywhere: Tailscale

1. On the TV: install the Tailscale app (Settings › Connect Omarchy Linux › Tailscale app), sign in, then turn on
   **Allow Tailscale (tailnet)**.
2. On the PC:

   ```sh
   omarchy-link tailscale on
   omarchy-link discover
   omarchy-link pair living-tv           # MagicDNS name, 100.x address or [fd7a:…] IPv6
   omarchy-link tailscale acl            # an ACL snippet that limits who reaches port 47800
   ```

3. For a machine elsewhere on the tailnet (a server, a laptop, Hermes on a NAS):

   ```sh
   omarchy-link token add "NAS" --scope notify --remote
   ```

   This prints the token, a curl line, the `claude mcp add --transport http …` command and the Hermes
   `mcp_servers` block for that machine.

On a 1-2 GB TV, set **Keep Link reachable after boot** to *on* (or `omarchy-link tv keepalive on`) if it must
answer before you open OmarchyTV. Details: [TAILSCALE.md](TAILSCALE.md). LG and Samsung reach the tailnet through a
Raspberry Pi: [RASPBERRY-PI.md](RASPBERRY-PI.md).

## 7. AI agents: MCP

```sh
claude mcp add omarchytv -- omarchy-link mcp                  # every tool, over the paired link
claude mcp add omarchytv -- omarchy-link mcp --scope notify   # only read + notify + ask
```

Tools by scope:

- **read**: status, state, theme, themes, apps, AI status, Cinemarchy, what is playing, pages.
- **notify**: notify, ask and its result, page badges, `tv_room_post`, `tv_remind`.
- **control**: keys, apps, themes, pages, the screensaver, Radiomarchy, opening parts of OmarchyTV, diagnostics.

Other ways in:

- **The TV itself**: `http://<tv>:47800/v1/mcp` with an app token.
- **Another box**: `omarchy-tv mcp --http --tailscale`.
- **The cloud**: `omarchy-tv mcp --cloudflare NAME --access-team TEAM --access-aud AUD`, behind Cloudflare Access;
  read + notify by default ([CLOUDFLARE.md](CLOUDFLARE.md)).

Hermes Agent: one `mcp_servers` entry and a ready skill ([examples/hermes.md](examples/hermes.md)). Codex, Claude Code
hooks, GitHub Actions and Home Assistant: [examples/](examples/).

## 8. Your PC talks to the TV

```sh
omarchy-link hook add all        # or pick: theme-set post-boot post-update battery-low font-set
omarchy-link hook list
omarchy-link remind 20m "Pizza in the oven"    # 90s, 1h30m, 45 (minutes) or 18:45; list; cancel ID
omarchy-link radio play; omarchy-link radio next; omarchy-link open radiomarchy
```

- **Omarchy hooks**:
  - *post-boot* says "PC is up" and sends the theme.
  - *post-update* says "Omarchy updated".
  - *battery-low* speaks the level.
  - *font-set* shows the new font.
  - *theme-set* sends the theme, as before. `hook add` alone installs only this one.
  - All run quietly in the background and never slow the desktop.
- **Reminders** are systemd user timers: nothing keeps running meanwhile. At the time, the TV says the reminder aloud.
- **Agent meters**: the usage, reset times and accounts from Omarchy's agents panel (23 agents and providers) are on
  the TV's AI credits pane. Turn it on with `omarchy-link ai sources`.

## 9. LG and Samsung

Start the bridge on the PC (`omarchy-tv webos-serve`), then pair the TV app with the ask code it prints.

- `omarchy-tv notify`, `omarchy-tv page`, `omarchy-tv ask` and `omarchy-tv mcp --bridge` use the same calls as on
  Android TV.
- Agents use their own key (`~/.config/omarchy-tv/bridge-agent-token`, read for you). They can post and wait for an
  answer, but only the TV answers a question: an agent cannot approve itself.
- The bridge accepts this PC, your LAN, and the tailnet only with omarchy-link's Tailscale switch on.
- **Arrange panels** (System menu): OK moves a Home widget up, and the order is kept. *Reset order* brings back the
  default.
- Every menu title now follows the TV's language, in all 23 languages.

## 10. Diagnostics, backup and the PC's view of the TV

```sh
omarchy-link tv logs                  # the TV's last log lines, for a bug report (no secrets)
omarchy-link tv diagnostics           # version, device, network, settings, Home, log
omarchy-link tv backup tv.json        # the TV's settings
omarchy-link tv restore tv.json       # puts them back (pairings and panes stay)
omarchy-link tv keepalive [auto|on|off]
omarchy-link tv home
```

## 11. Grok Bot and Tom Ballard's Agent Desktop

OmarchyTV 2.21 is built for **Agent Desktop v0.3.3** by Tom Ballard. Checkouts of v0.2.0 and v0.1.0 still work.
The avatar shapes, palette and faces are the same as before.

## 12. Install notes for some TVs

ADB over the network, Hisense over Ethernet, TCL (Home and the SmartConnect VPN), turning accessibility back on after
an update, and Link after a reboot on 1-2 GB TVs: [INSTALL-TIPS.md](INSTALL-TIPS.md).

## Defaults at a glance

| Thing | Default |
|---|---|
| Home wallpaper | Last Horizon "Eyes Wide", looping (a 540p, 15 fps copy on weak TVs) |
| Music on Home | off |
| AI agent alerts | off |
| Share theme with apps on the network | on |
| App tokens | none |
| Tailscale | off on the TV and on the PC |
| Keep Link reachable after boot | auto (only above 2.5 GB of RAM) |
| Notification tone and voice | on (`notifySound`) |
| `omarchy-link hook add` | theme-set only (`all` for the pack) |
| Claude Code approvals on the TV | off (`omarchy-link agents approvals on`) |
| Cloudflare MCP | read + notify, with Access recommended |
