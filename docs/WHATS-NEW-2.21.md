# What's new in OmarchyTV 2.21

**OmarchyTV becomes the screen of your agents and dashboards.** Here is what you can try in five minutes. Every
command runs on your paired Omarchy PC. On the TV, turn on Settings › Omarchy Link › **Agent alerts** for
notifications and questions.

## Your agents talk to you on the TV

```sh
omarchy-link notify "Tests green" --from "Claude Code"              # waits while a film plays
omarchy-link notify --level alert --title Deploy "prod is down"     # shows at once
omarchy-link ask "Merge the PR?" --choice Merge --choice Wait       # you answer with the remote
claude mcp add omarchytv -- omarchy-link mcp                        # Claude Code gets every TV tool
```

```sh
omarchy-link watch -- make release                                  # ✓ / ✗ on the TV when it ends
omarchy-link notify --level alert --speak "prod is down"            # with a chime, read aloud
```

Hermes Agent: one `mcp_servers` entry and a skill ([examples/hermes.md](examples/hermes.md)). Ready-made hooks for
Claude Code and Codex, a GitHub Actions job that tells the TV when CI fails, Home Assistant
(doorbell → camera full screen, alert spoken aloud) and a dashboard page are in [examples/](examples/).

## Dashboards on the TV, Hyprland style

```sh
omarchy-link pages add home "Home" http://192.168.1.20:8080/ --icon H --badge 2   # a tile on the Home
omarchy-link page http://192.168.1.20:8080/ http://192.168.1.21:3000/             # two pages tiled
```

1-4 or the colour keys switch windows, and BACK closes them. A page takes the TV's colours with one line of
JavaScript:

```html
<script src="http://<tv>:47800/v1/sdk/omarchytv.js"></script>
<script>OmarchyTV.connect({ token: "otva_…" }).followTheme(); OmarchyTV.remote();</script>
```

Make the token with `omarchy-link token add "Home" --scope read`, or on the TV in Settings › Omarchy Link › New app
token (shown as a QR code).

## From anywhere: Tailscale

Install the Tailscale app on the TV, then turn on Settings › Omarchy Link › Allow Tailscale. On the PC:

```sh
omarchy-link tailscale on
omarchy-link discover
omarchy-link pair living-tv
```

Everything then works from the office or the beach house, over IPv4 and IPv6. LG and Samsung need a Raspberry Pi as a bridge:
[TAILSCALE.md](TAILSCALE.md), [RASPBERRY-PI.md](RASPBERRY-PI.md).

## Agents in the cloud: Cloudflare Tunnel

```sh
omarchy-tv cloudflare setup omarchytv-mcp tv-mcp.example.com
omarchy-tv mcp --cloudflare omarchytv-mcp --access-team TEAM --access-aud AUD
```

A cloud agent can then read and talk to you, but not drive the TV, unless you allow it. The TV itself is never on
the internet. See [CLOUDFLARE.md](CLOUDFLARE.md).

## Quieter, lighter, fixed

- Home no longer starts music by itself. If you liked it, Radiomarchy › Settings › Music on Home brings it back.
- Radiomarchy has a sleep timer, and shows the song's page as a QR code for your phone.
- On LG, Radiomarchy no longer goes silent after the first song.
- Fractals are lighter: each scene is its own shader, and auto quality stops at "high".
- Home is Last Horizon "Eyes Wide" again (Erik Johansson's eyes, looping; a lighter copy on weak TVs). Only if you
  never picked a wallpaper: your choice stays, and the fractals are one click away in Settings › Wallpaper.
- Grok Bot diretto copes with ntfy.sh rate limits (#70).
- Settings › System info shows the TV's state. On the PC, `omarchy-link tv logs` gives the log for bug reports,
  `tv backup` saves the TV's settings and `tv restore` puts them back.
- Eight community pull requests are merged. Thank you!

The full list is in the [CHANGELOG](../CHANGELOG.md).
