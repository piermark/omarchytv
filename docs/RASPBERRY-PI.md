# A Raspberry Pi next to the TV (v2.21)

A small always-on box makes OmarchyTV better in three ways: it keeps dashboards and agent pages up when your laptop
sleeps, it puts an LG or Samsung TV on your tailnet, and it can run the LG/Samsung bridge next to the TV. Any Raspberry
Pi from the 3B+ on is enough (a Pi 4 or 5 is comfortable); any small Linux box works the same.

The Pi does **not** run OmarchyTV itself: the launcher is an Android TV app, and the web app is for LG webOS and
Samsung Tizen.

## 1. Base

Raspberry Pi OS Lite (64-bit), SSH on, a fixed address on your LAN (a DHCP reservation in your router), wired if
possible.

```sh
sudo apt update && sudo apt full-upgrade -y
sudo apt install -y python3 git curl
```

## 2. Tailscale and a subnet router (for LG / Samsung, or to reach home from outside)

```sh
curl -fsSL https://tailscale.com/install.sh | sh
echo 'net.ipv4.ip_forward = 1' | sudo tee /etc/sysctl.d/99-tailscale.conf
sudo sysctl -p /etc/sysctl.d/99-tailscale.conf
sudo tailscale up --advertise-routes=192.168.1.0/24      # your LAN
```

Approve the route in the Tailscale admin console. Your devices on the tailnet now reach the TV's LAN address, even
for a TV that cannot run Tailscale. Details and an ACL example: [TAILSCALE.md](TAILSCALE.md).

## 3. The LG / Samsung bridge on the Pi (optional)

The bridge (`omarchy-tv webos-serve`) usually runs on your Omarchy PC. On the Pi it is always there, next to the TV.
It then serves the Pi's own state; the theme comes from a theme folder you copy to the Pi.

```sh
git clone https://github.com/piermark/omarchytv.git && cd omarchytv/pc-plugin
mkdir -p ~/.local/share ~/.local/bin
cp -r omarchy-link ~/.local/share/omarchy-link && cp -r omarchy-tv ~/.local/share/omarchy-tv
ln -sf ~/.local/share/omarchy-tv/bin/omarchy-tv ~/.local/bin/omarchy-tv
# the theme the bridge serves: a theme folder with colors.toml, its name, and a wallpaper
mkdir -p ~/.config/omarchy/current
cp -r /path/to/your-theme ~/.config/omarchy/current/theme
echo your-theme > ~/.config/omarchy/current/theme.name
ln -sf ~/.config/omarchy/current/theme/backgrounds/1.jpg ~/.config/omarchy/current/background
omarchy-tv webos-serve                     # port 8765; GET /v1/theme answers from that folder
```

## 4. Dashboards that never go blank

Serve the dashboard from the Pi (any web server: `python3 -m http.server 8080` for a static page) and let the page
take its colours from the TV:

```html
<script src="http://192.168.1.50:47800/v1/sdk/omarchytv.js"></script>
<script>
  var tv = OmarchyTV.connect({ token: "otva_…" });   // omarchy-link token add "Pi dashboard" --scope read
  tv.followTheme();                                     // CSS: var(--otv-background), var(--otv-accent), …
</script>
```

Open it on the TV from the PC (`omarchy-link page http://192.168.1.20:8080/`) or from an agent (MCP
`tv_show_page`). The full API is in [INTEGRATIONS.md](INTEGRATIONS.md).

## 5. Agents on the Pi

Hermes, a Claude Code job or a cron script on the Pi can use the TV:

- **Over the tailnet**, with the PC's MCP: `omarchy-tv mcp --http --tailscale` on the PC, then the Pi's agent
  connects to `http://<pc>:8990/mcp` with the Bearer token.
- **Directly**, with an app token from the PC: `curl -H "Authorization: Bearer otva_…" -d '{"text":"backup done"}'
  http://192.168.1.50:47800/v1/notify` (a `notify` token; agent alerts on, on the TV).

## 6. homewatch and other services

The Pi can keep running what it already runs (homewatch, Home Assistant, a NAS share). OmarchyTV only needs the Pi
to be reachable on the LAN or the tailnet, and the TV's agent alerts on for notifications.
