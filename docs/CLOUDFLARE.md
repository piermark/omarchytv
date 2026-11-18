# OmarchyTV and Cloudflare Tunnel (v2.21)

Tailscale ([TAILSCALE.md](TAILSCALE.md)) connects **your own devices**: the PC, the TV, a Raspberry Pi, the box that
runs your agents. Some agents run **in the cloud** instead (a Grok Bot routine, an agent platform, a CI job) and cannot
join your tailnet. For those, the PC's MCP server can be reached through a **Cloudflare Tunnel**.

| | Tailscale | Cloudflare Tunnel |
|---|---|---|
| Who reaches the TV | your devices on the tailnet | a cloud agent, through Cloudflare |
| What is exposed | Omarchy Link (TV) and MCP, to tailnet peers only | **only** the PC's MCP server (`omarchy-tv mcp`) |
| The TV's port 47800 | reachable on the tailnet, if you allow it | **never** exposed: the tunnel ends on the PC, which talks to the TV over the signed, encrypted link |
| Default tools | all of them (control) | **read + notify**: state, theme, notify, ask; no keys, apps or pages unless you say `--scope control` |
| Auth | tailnet + Bearer token | Cloudflare Access (recommended) + Bearer token |

## Quick try: a temporary URL

```sh
omarchy-tv mcp --tunnel
# MCP tunnel URL: https://<random>.trycloudflare.com/mcp
# Authorization: Bearer <token>
```

Good for a test. The URL changes every run and anyone who has it and the token can use the tools of the scope (notify
by default). Stop it with CTRL+C.

## For good: a named tunnel on your domain, behind Cloudflare Access

You need a domain on Cloudflare and `cloudflared` on the PC.

1. Create the tunnel and its hostname (once). `omarchy-tv cloudflare setup` shows the two commands and runs them
   after you say yes:

   ```sh
   cloudflared tunnel login
   omarchy-tv cloudflare setup omarchytv-mcp tv-mcp.example.com
   # = cloudflared tunnel create omarchytv-mcp
   #   cloudflared tunnel route dns omarchytv-mcp tv-mcp.example.com
   ```

2. Put **Cloudflare Access** in front: Zero Trust › Access › Applications › *Add* › *Self-hosted*, domain
   `tv-mcp.example.com`. Add a policy with the action **Service Auth** and a **service token** (Access › Service
   auth › Create). Copy the application's **AUD tag** (Overview tab) and note your team name (the `TEAM` in
   `TEAM.cloudflareaccess.com`). The agent sends the token's two headers on every request:

   ```
   CF-Access-Client-Id: <id>.access
   CF-Access-Client-Secret: <secret>
   Authorization: Bearer <the token omarchy-tv mcp prints>
   ```

3. Run the server with the tunnel and let it **verify** Access:

   ```sh
   omarchy-tv mcp --cloudflare omarchytv-mcp --access-team TEAM --access-aud <AUD tag>          # read + notify
   omarchy-tv mcp --cloudflare omarchytv-mcp --access-team TEAM --access-aud <AUD> --scope read  # even less
   ```

   Every request must carry the JWT that Access adds (`Cf-Access-Jwt-Assertion`): the server checks its RS256
   signature against your team's public keys, the audience, the issuer and the expiry, and refuses anything else with
   `403 access_denied`, before it looks at the Bearer token. It needs `python-cryptography`. Without the team and
   AUD, `--access` only requires that header to be present (weaker; the origin is still `127.0.0.1`, reachable
   only by `cloudflared`).

4. Keep it running with a systemd user service:

   ```sh
   omarchy-tv cloudflare service --cloudflare omarchytv-mcp --access-team TEAM --access-aud <AUD>
   systemctl --user daemon-reload && systemctl --user enable --now omarchytv-mcp
   ```

5. Point the agent at `https://tv-mcp.example.com/mcp` with the three headers above.

## What a cloud agent can do (default scope: notify)

| Tool | What it does |
|---|---|
| `tv_status`, `tv_get_state` | what is on the TV now |
| `tv_get_theme`, `tv_list_themes`, `tv_list_apps` | the theme and the lists |
| `tv_notify` | one line on the TV over whatever is playing |
| `tv_ask_user`, `tv_ask_result` | a question you answer with the remote ("Deploy to production?") |
| `tv_radio_now` | what Radiomarchy is playing |

`--scope control` adds the remote, apps, themes, the screensaver, pages, Radiomarchy control (`tv_radio`),
opening parts of OmarchyTV (`tv_open`) and diagnostics. Use it only when Access is in
front, and preferably with a service token for that one agent.

## Notes

- `tv_notify` and `tv_ask_user` show up only with **agent alerts** on in the TV's settings.
- The token in `~/.config/omarchy-tv/mcp-token` (mode 600) is the same for every way of running the server. To change
  it, delete the file and restart.
- Revoking access: delete the service token in Cloudflare, or stop `omarchy-tv mcp`. The TV is never reachable
  without the PC.
