---
name: omarchytv-setup
description: Set up and connect the owner's OmarchyTV (Android TV, LG or Samsung running the OmarchyTV launcher) to this machine and this agent - install the omarchy-link PC plugin, pair with the TV, register the omarchytv MCP server for Claude Code, Codex or Hermes, make a scoped token for another machine, and reach the TV over Tailscale. Use when the owner asks to connect, pair, set up or troubleshoot OmarchyTV, or when tv_ tools are missing or failing.
---

# Set up and connect OmarchyTV

OmarchyTV is a TV launcher. On the owner's PC, the `omarchy-link` command talks to it on port 47800, and
`omarchy-link mcp` gives an agent the `tv_` tools. Once this skill has done its job, the companion skill
`omarchytv` (docs/examples/hermes-skill/omarchytv/SKILL.md in the same repository) says how to use the TV well.

Work through the steps in order and skip any step that is already done and working.

## Ground rules

- **The owner does the parts on the TV.** You cannot see the TV: ask them to open a menu or read you a code, and wait.
- **Never guess or invent a pairing code.** It is 6 digits shown on the TV; only the owner can read it.
- **Smallest scope.** Use `--scope notify` unless the owner asks for the agent to drive the TV (keys, apps, themes,
  pages). Say what `control` allows before you use it.
- **Do not expose the TV to the internet.** LAN or tailnet only. For a cloud agent, point the owner to section 3 of
  docs/REMOTE-AND-AI-GUIDE.md (Cloudflare Tunnel + Access, ending on the PC) and let them decide.
- **Do not print tokens back in chat** beyond what a command already printed for the owner to copy, and never write
  them into a repository.
- Ask before installing anything or editing the owner's agent configs.

## 1. Is it already working?

```sh
command -v omarchy-link && omarchy-link status
```

- Prints the TV as connected: go to step 4.
- Exit code 3 ("not paired"): go to step 3.
- `omarchy-link: command not found`: go to step 2.
- Offline: the TV is off, asleep or OmarchyTV is not open. Ask the owner to open OmarchyTV once. On a 1-2 GB TV,
  after it answers, `omarchy-link tv keepalive on` keeps it reachable after a reboot.

## 2. Install the PC plugin (Omarchy or any Linux / macOS with python3)

```sh
git clone https://github.com/piermark/omarchytv.git ~/.local/src/omarchytv   # mirror: gitlab.com/piermark/omarchytv
mkdir -p ~/.local/share ~/.local/bin
cp -r ~/.local/src/omarchytv/pc-plugin/omarchy-link ~/.local/share/omarchy-link
ln -sf ~/.local/share/omarchy-link/bin/omarchy-link ~/.local/bin/omarchy-link
```

Only on Omarchy, and only if the owner wants the bar widget and theme sync:

```sh
mkdir -p ~/.config/omarchy/plugins
ln -sfn ~/.local/share/omarchy-link ~/.config/omarchy/plugins/com.omarchytv.link
omarchy-shell shell rescanPlugins && omarchy plugin enable com.omarchytv.link
omarchy-link hook add
```

It needs only python3 and opens no port. Check `~/.local/bin` is on PATH.

## 3. Pair with the TV

1. Ask the owner: on the TV, open the logo menu › **Connect Omarchy Linux**. It shows the TV's address and a 6-digit code.
2. Find the TV, then pair with the code the owner reads to you:

```sh
omarchy-link discover
omarchy-link pair --code 123456              # or: omarchy-link pair 192.168.1.40 --code 123456
omarchy-link status
```

The code never crosses the network (SPAKE2). If `discover` finds nothing, ask for the address shown on the TV and
pass it to `pair`. If the owner gave you this skill from the QR on the TV (Settings › Grok Bot & AI › Get the agent
skill · this TV), the link ends with `#tv=192.168.1.40:47800`: that is the TV's address. It is never a code or a
token; still ask the owner for the 6-digit code.

## 4. Turn on agent messages (on the TV)

Ask the owner: Settings › Grok Bot & AI › **AI agent alerts while you watch** › on. Then test:

```sh
omarchy-link notify "Hello from your agent" --from "Setup"
```

Exit code 4 means that switch is still off. Ask the owner whether the line appeared on the TV.

## 5. Give this agent the TV tools (MCP)

Pick the agent you are running in. Ask before editing its config.

- **Claude Code**
  ```sh
  claude mcp add omarchytv -- omarchy-link mcp --scope notify    # read + notify + ask
  claude mcp add omarchytv -- omarchy-link mcp                   # every tool (control), only if asked
  ```
  Optional: `omarchy-link agents approvals on` puts Claude Code's permission prompts on the TV
  (Allow / Deny / On the PC; no answer means it asks on the PC as usual).
- **Codex**, in `~/.codex/config.toml`:
  ```toml
  notify = ["omarchy-link", "notify", "-q", "--from", "Codex"]

  [mcp_servers.omarchytv]
  command = "omarchy-link"
  args = ["mcp", "--scope", "notify"]
  ```
- **Hermes Agent**, in `~/.hermes/config.yaml` under `mcp_servers`: `command: "omarchy-link"`,
  `args: ["mcp", "--scope", "notify"]`. Copy the usage skill: `cp -r docs/examples/hermes-skill/omarchytv ~/.hermes/skills/`.
- **Any other MCP client** on the PC: the stdio command `omarchy-link mcp --scope notify`.

The agent usually needs a restart to see new MCP servers. Then check: `tv_status` answers, and `tv_notify` puts a
line on the TV.

On an Android TV with 2.22 or later, tell the owner what else they can try (only what they ask for):

- **AI LIVE**: `omarchy-link agents on` and the TV's Settings › Grok Bot & AI › AI LIVE show their agents at work
  like a broadcast; an agent can add a line and a preview with `tv_live_update`.
- **Night shift**: "start a night shift with these tasks until 7, wake me at 7:30" → `tv_shift_start`; then
  `tv_shift_log` for each step and `tv_shift_queue` for anything risky (never push, merge, post or pay at night).
- **Skills**: `tv_skill_list`, and `tv_skill_install` to ask the owner on the TV (Night shift, Homelab, Movie night).

## 6. An agent on another machine (LAN or tailnet)

On the paired PC, make a token for that machine. `--remote` prints the exact lines to run there (a curl test and the
`claude mcp add --transport http …` command with the TV's address and the token):

```sh
omarchy-link token add "NAS" --scope notify --remote          # 2.22: add --max-level warn so it can never alert
omarchy-link token list
omarchy-link token revoke ID          # when that machine no longer needs it
```

## 7. From outside the home (Tailscale)

1. Owner, on the TV: Settings › Connect Omarchy Linux › **Tailscale app**, signed in to the same tailnet, then turn
   on **Allow Tailscale (tailnet)** on the same page.
2. On the PC:
   ```sh
   omarchy-link tailscale on
   omarchy-link discover                # now also finds the TV on the tailnet
   omarchy-link tailscale status
   omarchy-link tailscale acl           # optional: an ACL that lets only your devices reach port 47800
   ```

LG and Samsung TVs cannot run Tailscale: they need a small box on the LAN (a Raspberry Pi) as described in
docs/RASPBERRY-PI.md and docs/TAILSCALE.md. Show the owner those guides instead of improvising.

## When something fails

| Symptom | Fix |
|---|---|
| `notify` exits 4, "agent alerts are off" | step 4 |
| 403 `not_lan` | the address is not on the LAN: step 7, both switches |
| Nothing answers right after the TV reboots | `omarchy-link tv keepalive on`, or open OmarchyTV once |
| `tv_` tools missing in the agent | restart the agent; check the MCP config from step 5 |
| Anything else | `omarchy-link tv diagnostics` and `omarchy-link tv logs` (no secrets inside); full guide: docs/REMOTE-AND-AI-GUIDE.md |

When everything works, tell the owner in one or two lines what is connected, which scope the agent has, and how to
undo it (`claude mcp remove omarchytv`, `omarchy-link token revoke ID`, `omarchy-link unpair`; from 2.22
`omarchy-link forget-tv` undoes all of it at once).
