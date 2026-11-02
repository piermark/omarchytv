# Omarchy Link

Pair your [Omarchy](https://omarchy.org) PC with the **OmarchyTV** Android TV launcher over your home network.

- **Theme sync**: `omarchy theme set …` turns the TV the same colours, with your wallpaper as a 4K JPEG.
- **Bar widget** with the TV's status (connected, the app on screen, screensaver). Click it for a
  remote (home, back, play/pause, volume) and an app launcher.
- **Panes and shortcuts on the TV**: a live PC pane (CPU, RAM, uptime, now playing) plus your own panes and
  shortcuts (`widgets.json`), including "run on PC" shortcuts you whitelist.
- **AI agents on the TV** (off by default): live status of Claude Code, Codex, Grok CLI and Hermes sessions (Grok Bot presence, Gemini best effort). Optionally, instructions from the TV with PC-side confirmation. `omarchy-link agents preview|enable`. See [docs/AGENTS.md](docs/AGENTS.md).
- **TV prompt**: `omarchy-link prompt set <user> [host]` sends `user@host` with the theme.
- **Send to TV**: `omarchy-link cast <link|file>`, `--clipboard`, `--text`, experimental `--screen`; Google Cast via `catt` works on any Cast TV today, the OmarchyTV launcher adds text, images and uploads over the signed channel. Bar buttons, launcher and "Open with" entries (`omarchy-link desktop`). See [docs/CAST.md](docs/CAST.md).
- **AI credits pane**: mirrors the AI-usage widget in your Omarchy bar (the built-in Agents panel by default; also Omacash, claudebar, ai-usagebar…; `omarchy-link ai setup`; Omacash and the others are opt-in with `omarchy-link ai sources enable omacash` or from the TV's AI-pane switches), so the TV shows the same numbers. Without one: Claude Code and Codex plan usage read from the tools already signed in on the PC,
  with **no API keys**. Manual entries and API fallbacks cover the rest.
- **Voice assistant relay**: the TV's `> ask` sends what you said to the PC. The PC answers with an AI that
  is already signed in here (Claude Code, Codex, Grok CLI, Gemini CLI, ollama), or an API you configure,
  and streams the reply back for the TV to show and read aloud.

The TV never contacts AI services and never holds credentials. Everything stays on your LAN.

## Install

```bash
omarchy plugin add https://github.com/omarchytv/omarchy-link.git --enable
omarchy-link hook add      # sync the theme to the TV on every `omarchy theme set`
```

(Repository URL is a placeholder until the project is published.) Then add the **OmarchyTV**
widget to your bar from the bar settings. It sits on the right by default.

Requirements: `python3` (standard library only), `ffmpeg` or ImageMagick for the wallpaper,
`playerctl` (optional, for "now playing"). All of these ship with Omarchy. The plugin opens no
port and needs no firewall change.

## Pair

1. On the TV: logo menu → **Connect Omarchy Linux**. It shows its address and a 6-digit code.
2. On the PC: click the widget → Pair, or run:

```bash
omarchy-link discover                # finds TVs via mDNS (_omarchytv._tcp)
omarchy-link pair --code 123456      # or: omarchy-link pair 192.168.1.40 --code 123456
```

The code is never sent over the network. Both sides prove they know it (SPAKE2) and derive
a shared token. Every later request is signed with that token.

## Use

```bash
omarchy-link status                  # connected, app on screen, screensaver
omarchy-link key home | back | play_pause | volume_up | volume_down | mute …
omarchy-link apps; omarchy-link launch com.netflix.ninja
omarchy-link sync-theme --force
omarchy-link push-widgets [widgets.json] [--dry-run]
omarchy-link push-ai --dry-run       # AI credits that would be sent
omarchy-link ai providers            # AIs the TV assistant can use
omarchy-link ai ask "a film for tonight?"
omarchy-link unpair
```

`omarchy-link status --waybar` prints Waybar-style JSON (`text`, `tooltip`, `class`:
connected / screensaver / offline / unpaired) for custom bar modules. Exit code 3 means "not paired".

Widget keyboard shortcuts while its panel is open: `H` home, `B` back, `P` play/pause, `+`/`-` volume,
`M` mute, `A` apps, `T` sync theme, `R` rescan.

## Configure (all optional)

Files live in `~/.config/omarchy-link/`. See `examples/`.

| File | What |
|---|---|
| `config.json` | `themeSync`, `themeFromTv` (TV may switch the PC theme, default on; the TV side is off until enabled there), `wallpaperSync`, `pcPane`, `aiCredits`, `aiRefreshMinutes`, `aiSourcesFromTv`, `agentMonitor`, `agentControlFromTv`, `agentConfirm`, `agentToolApprovalFromTv`, `prompt`, `castPrefer` (`cast`\|`link`), `castDevice`, `castServe`, `screenCommand`, `assistant`, `allowOtherSubnets`, `actions` (PC commands a TV shortcut may run: `{"lock": {"label": "Lock PC", "exec": ["omarchy-system-lock"], "confirm": true}}`) |
| `widgets.json` | extra panes and shortcuts for the TV |
| `ai.json` | AI credits: which bar widget to mirror (`omarchy-link ai setup`), manual entries, API fallbacks, auto-detection. See [docs/AI-CREDITS.md](docs/AI-CREDITS.md). |
| `assistant.json` | voice assistant providers, default, memory, API fallbacks. See [docs/ASSISTANT.md](docs/ASSISTANT.md). |

Keys, when you choose to use API providers, are referenced (`env:NAME`, `file:PATH` with chmod 600,
`cmd:pass show …`) rather than pasted into the files.

## Security

- LAN only. The PC refuses non-private addresses and, by default, anything outside its own subnets.
  The TV accepts only its subnet.
- The TV is the server and the PC the client. Omarchy's default-deny firewall stays as it is.
- Pairing uses a single-use 6-digit code with SPAKE2: no offline guessing, and 5 attempts per code.
- Every request, response and event is authenticated (HMAC with the pairing token), timestamped
  and replay-protected. Since 2.13, REST request and response bodies are also encrypted (AES-256-GCM)
  when `python-cryptography` is installed and the TV advertises `enc-a256gcm`. Paths, headers, file
  uploads, WebSocket events (including AI questions and answers) and cast streams are not encrypted
  (see PROTOCOL.md §9).
- The TV can't run commands on the PC. "PC" shortcuts are ids mapped to commands you wrote, and
  they ask for confirmation by default. The assistant runs AI CLIs with tools and MCP off, in an empty temp dir.
- State: `~/.local/state/omarchy-link/pairing.json` (chmod 600).

## Remove

```bash
omarchy-link unpair
omarchy-link hook remove
omarchy plugin remove com.omarchytv.link
rm -rf ~/.config/omarchy-link ~/.local/state/omarchy-link ~/.cache/omarchy-link
```

## For the TV side and contributors

- [docs/PROTOCOL.md](docs/PROTOCOL.md) is the exact wire spec the launcher implements, with test vectors in `docs/vectors.json`.
- `mock/mock_tv.py` is a complete mock TV implementing PROTOCOL.md:
  `python3 mock/mock_tv.py --data /tmp/tv --port 47800 --mdns --simulate` prints its pairing code.
- Tests: `tests/run.sh` covers crypto vectors, pairing, auth, theme, widgets, credits, the assistant over the link, and mDNS.

## License

MIT. See [LICENSE](LICENSE). Omarchy is © its authors. This is an independent community plugin.
