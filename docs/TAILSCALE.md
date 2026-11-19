# OmarchyTV over Tailscale (v2.21)

Omarchy Link is local-network only by design. Since 2.21 you can also let it use your **tailnet**: your PC and your
TV talk over Tailscale as if they were on the same LAN, from another room, another network or another house. It is
**off by default** on both sides, and only Tailscale's own addresses are accepted (`100.64.0.0/10` and
`fd7a:115c:a1e0::/48`). Nothing is opened to the internet: no port forwarding, no Funnel, no Serve.

## What works over the tailnet

| | Android TV (Omarchy Link) | LG webOS / Samsung Tizen |
|---|---|---|
| Pair, theme and wallpaper sync, keys, apps, widgets, AI panes, agents | yes | through the PC bridge (see below) |
| `GET /v1/theme` for dashboards and agent pages | yes | yes (bridge) |
| Send to TV (cast) of a link or a file | yes; slower if Tailscale has to relay (DERP) | — |
| Remote panes from another box on the tailnet | yes, by its `100.x` address | — |
| MCP for AI agents (`omarchy-tv mcp --http --tailscale`) | yes | yes |
| mDNS discovery | no (mDNS does not cross a tailnet): `omarchy-link discover` asks Tailscale instead | — |

## Android TV: the TV on the tailnet

1. Install the official **Tailscale** app on the TV (Google Play on Android TV / Google TV) and sign in to the same
   tailnet as your PC.
   OmarchyTV › Settings › **Omarchy Link** › **Tailscale app** opens it, or, when it is missing, opens its Play
   Store page (or shows a QR code of it for your phone).
2. OmarchyTV › Settings › **Omarchy Link** › **Allow Tailscale (tailnet)** → *on*. The row shows the TV's tailnet
   address, for example `on · 100.101.2.3:47800`, or `offline` when Tailscale is not connected.
3. On the PC:

   ```sh
   omarchy-link tailscale on        # this PC may now talk to tailnet TVs
   omarchy-link discover            # LAN TVs (mDNS) and tailnet TVs (asks tailscaled)
   omarchy-link pair living-tv      # a MagicDNS name, a host name, or the 100.x address
   ```

   `omarchy-link tailscale status` shows this PC's tailnet address and every OmarchyTV that answers on the tailnet,
   with the path: `direct` or `relay (DERP)`.

Why OmarchyTV uses the official app instead of building Tailscale in: the TV stays one device of your tailnet with
Tailscale's own login, exit-node and DNS settings and security updates; Android allows one VPN at a time; and the
launcher stays small. OmarchyTV finds the tailnet network by itself.

With the switch off the TV listens on its LAN address only, so a tailnet PC gets no answer at all (a timeout or
"connection refused"), not an error page: turn the switch on. `omarchy-link tv diagnostics` lists each paired PC with
`"via": "tailscale"` or `"lan"`.

**IPv6**: with the switch on, the TV listens on IPv4 and IPv6 (dual stack; a TV without IPv6 falls back to IPv4),
so it answers on its `100.x` address and on its `fd7a:115c:a1e0::/48` address. `discover` and MagicDNS names use
IPv4; to pair over IPv6 give the address: `omarchy-link pair fd7a:115c:a1e0::5` or `[fd7a:115c:a1e0::5]:47800`.
Listening on IPv6 also means devices on your home LAN that have IPv6 can reach port 47800 over it, not only over
IPv4. Nothing changes for them: every route except the public theme still needs a paired PC or an app token, and
with the switch off the TV listens on IPv4 only, as in 2.20.
Pages, casts and Remote panes use the `100.x` address.

The TV keeps working on the LAN at the same time. Before 2.21, having a VPN app (Tailscale included) active on the TV
made Omarchy Link listen on the VPN address and refuse the PC next to it; now the TV always finds the Wi-Fi or
Ethernet network under the VPN.

**After a reboot.** On a TV with more than 2.5 GB of RAM, Link starts at boot and stays reachable. A 1-2 GB TV
(the Sony class) keeps no resident service by default: Link answers once OmarchyTV is open, which is the case when
it is your Home. To reach such a TV from far away right after it reboots, turn on Settings › Connect Omarchy Linux ›
*Keep Link reachable after boot*, or from the PC: `omarchy-link tv keepalive on` (`tv keepalive` shows the
setting and whether it is active on that TV).

What the switch changes on the TV: Omarchy Link listens on all IPv4 interfaces instead of the LAN address only, and
a peer is let in when it is on the LAN subnet **or** has a Tailscale address. Everything else still gets
`403 not_lan`. Pairing, the HMAC signing and the AES-GCM bodies are exactly the same as on the LAN.

## LG and Samsung: a small box on the tailnet

webOS and Tizen cannot run the Tailscale client. Use a **subnet router**: any always-on Linux box on the TV's LAN
(a Raspberry Pi is plenty) that announces the LAN to your tailnet.

```sh
# on the Raspberry Pi (Raspberry Pi OS / Debian)
curl -fsSL https://tailscale.com/install.sh | sh
echo 'net.ipv4.ip_forward = 1' | sudo tee /etc/sysctl.d/99-tailscale.conf && sudo sysctl -p /etc/sysctl.d/99-tailscale.conf
sudo tailscale up --advertise-routes=192.168.1.0/24     # your TV's LAN
```

Approve the route in the Tailscale admin console (Machines › the Pi › Edit route settings), and on a Linux PC
accept routes once: `sudo tailscale set --accept-routes`. Your PC then reaches the TV's LAN address (`192.168.1.x`)
through the Pi.

- **LG / Samsung**: the TV talks to the bridge (`omarchy-tv webos-serve`) on the PC. When the PC is away from home,
  run the bridge on the Pi itself, next to the TV ([RASPBERRY-PI.md](RASPBERRY-PI.md)).
- **An Android TV without the Tailscale app** works the same way, through the Pi. The TV's address is then a LAN
  address of *another* network than the PC's, so tell omarchy-link once: `"allowOtherSubnets": true` in
  `~/.config/omarchy-link/config.json` (private addresses only; the internet is still refused). The TV's
  Tailscale switch is not needed for this: the connections arrive from the Pi's LAN address.

The same Pi can be your always-on box for dashboards: it serves the page, and the page reads the theme from
`GET /v1/theme` on the TV or on the bridge.

## Who may talk to the TV: an ACL example

Tailscale's access rules decide which tailnet devices can reach the TV at all. A tight policy lets only your PCs
reach Omarchy Link's port:

```json
{
  "tagOwners": { "tag:tv": ["autogroup:admin"] },
  "acls": [
    { "action": "accept", "src": ["autogroup:member"], "dst": ["tag:tv:47800"] }
  ]
}
```

Tag the TV (`tag:tv`) in the admin console. `omarchy-link tailscale acl` prints a ready policy with the tags
`omarchy-pc` and `omarchytv`. For an LG/Samsung TV behind a subnet router, allow its LAN address (for example
`"src": ["192.168.1.40/32"]`) to the bridge's port 8765 on the box that runs `omarchy-tv webos-serve`.

## Everything else over the tailnet

- **App tokens and the app API** (dashboards, agent pages, `GET /v1/state`, `/v1/stream`, `/v1/ask`): a page or an
  agent on any tailnet box reaches the TV at its `100.x` address with an app token ([INTEGRATIONS.md](INTEGRATIONS.md)).
- **MCP from another box**: `omarchy-tv mcp --http --tailscale` on the PC (tailnet peers only), or
  `omarchy-link mcp` on the PC for local agents.
- **The LG/Samsung bridge** (`omarchy-tv webos-serve`) answers on every interface of its box, the tailnet included.
- **For agents in the cloud**, which cannot join the tailnet, use Cloudflare instead: [CLOUDFLARE.md](CLOUDFLARE.md).

## Who can read the theme

`GET /v1/theme` and `/v1/theme/wallpaper` need no token, on the tailnet as on the LAN: any tailnet device that can
reach the TV, devices **shared** into your tailnet included, can read your colours and wallpaper (nothing else).
If that matters, turn off Settings › Connect Omarchy Linux › Share theme with apps on the network, or narrow who reaches
port 47800 with an ACL (`omarchy-link tailscale acl`). Everything else needs pairing or an app token.

## Limits

- **Relayed connections** (DERP, when Tailscale cannot connect directly) work for everything but are slow for casting
  big files and live screens. `omarchy-link cast` warns when the TV is being reached through a relay right now (an
  idle connection is not reported); `tailscale ping <tv>` says when the direct path is up.
- **No hostnames on the TV side**: Remote panes and casts accept literal addresses only (`100.x.y.z`), as on the LAN.
- **Exit nodes**: when the TV uses an exit node, Tailscale may route the LAN through it; enable *Allow LAN access*
  in the Tailscale app.
- Turning the switch off on either side is immediate: the TV goes back to listening on the LAN address only.
