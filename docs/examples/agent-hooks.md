# Agent hooks for OmarchyTV 2.21

Every hook below calls `omarchy-link notify` on the paired PC. `-q` never fails the agent. On the TV, turn on agent
alerts (Settings › Grok Bot & AI › AI agent alerts while you watch).

## Claude Code (`~/.claude/settings.json`)

```json
{
  "hooks": {
    "Stop": [{ "hooks": [{ "type": "command", "command": "omarchy-link notify -q --from 'Claude Code' 'done, your turn'" }] }],
    "Notification": [{ "hooks": [{ "type": "command", "command": "omarchy-link notify -q --level warn --from 'Claude Code' 'needs your answer'" }] }]
  }
}
```

And every TV tool for Claude Code: `claude mcp add omarchytv -- omarchy-link mcp`.

## Codex (`~/.codex/config.toml`)

```toml
notify = ["omarchy-link", "notify", "-q", "--from", "Codex"]
```

Codex passes its event as JSON: OmarchyTV shows its last message.

## Any long command

```sh
omarchy-link watch -- make release            # ✓ / ✗, how long it took, the last line
omarchy-link watch --speak -- ./deploy.sh     # and the TV says it aloud
```

## A cron job or a script on another box

With an app token (`omarchy-link token add "NAS" --scope notify`):

```sh
curl -fsS -X POST http://192.168.1.50:47800/v1/notify -H "Authorization: Bearer otva_…" \
  -H "Content-Type: application/json" -d '{"title":"Backup","text":"done in 12 min","level":"info"}'
```
