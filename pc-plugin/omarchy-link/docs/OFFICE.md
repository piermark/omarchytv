# GrokBot Office: Claude and ChatGPT bots (OmarchyTV 2.20)

GrokBot Office on the TV shows your bots at their desks. Grok Bot's bots come from Grok Bot itself (Grok Bot
diretto, `docs/GROKBOT-DIRECT.md`) or from `omarchy-tv office push`. Bots for **Claude** (Anthropic API) and
**ChatGPT** (OpenAI API) live on this PC, in omarchy-link.

## Keys: only on this PC

| What | Where | Used for |
| --- | --- | --- |
| Anthropic API key | `env:ANTHROPIC_API_KEY`, or the `key` of your own `anthropic_api` entry in `assistant.json` | Claude answers |
| OpenAI API key | `env:OPENAI_API_KEY`, or the `key` of your own `openai_api` entry | ChatGPT answers |
| Anthropic Admin key | `env:ANTHROPIC_ADMIN_KEY`, or an `anthropic` entry in `ai.json` | spend (optional) |
| OpenAI Admin key | `env:OPENAI_ADMIN_KEY`, or an `openai` entry in `ai.json` | spend (optional) |

A `key` is `env:NAME`, `file:PATH` (the file must be chmod 600) or `cmd:ARGV`; a literal key only if the JSON file
is chmod 600 (see [ASSISTANT.md](ASSISTANT.md)). Keys are never sent to the TV and never written to a log.

## Hire, fire, see

```bash
omarchy-link office hire Trimmer --provider claude --role "cuts token use" [--description …] [--model …]
omarchy-link office fire Trimmer
omarchy-link office show [--json]    # the office JSON the TV gets from this PC
omarchy-link office push             # send it to the paired Android TV now
omarchy-link office usage [--json]   # this month's spend per provider
```

Or from the TV: **Hire** in the office, with Claude or ChatGPT on the provider chip (Android sends the
`office_hire` event over the encrypted link; LG/Samsung `POST /v1/office/hire` to `omarchy-tv webos-serve` with the
bridge's ask code).

Hiring first checks the key with the provider (`GET /v1/models`: free, no tokens). No key, a rejected key or no
network: the hire fails with the reason (`no_key`, `key_rejected`, `exists`, `full`, …) and nothing is saved or
shown. When it works, `assistant.json` (mode 600) gets an entry:

```json
{ "type": "anthropic_api", "id": "office-trimmer", "name": "Trimmer", "key": "env:ANTHROPIC_API_KEY", "model": "…",
  "system": "You are Trimmer, a bot in the user's GrokBot Office. Your role: cuts token use. <description>",
  "office": { "role": "cuts token use", "color": "#ff9e64", "hiredAt": 1794652800 } }
```

The key reference, `baseUrl` and model come from your own entry of the same type, if there is one. The `system`
text goes in front of the TV's short-answer prompt. The bot then answers like every other PC assistant: pick it
in the TV's Ask box (the TV's "ask" event, or `POST /v1/ai/ask` on LG/Samsung), and the TV shows and speaks the
answer.

## What the TV gets

`omarchy-link` adds these to the office JSON (with `omarchy-tv office push`'s bots, from
`~/.config/omarchy-tv/office.json`) and sends it with `PUT /v1/office` when it connects and on every AI refresh:

```json
{ "bots": [ { "id": "office-trimmer", "name": "Trimmer", "role": "cuts token use", "provider": "claude",
              "assistant": "office-trimmer", "status": "idle" } ],
  "providers_usage": [ { "provider": "claude", "label": "Claude API", "spent": 12.35, "unit": "$",
                         "budget": 50, "pct": 24.7, "period": "month", "resets": "…" } ] }
```

`providers_usage` holds only real numbers from the Admin APIs (Anthropic cost report, OpenAI organization costs).
No admin key, a wrong one, or no network: that provider has no row and the TV shows no spend for it. `pct` is there
only when you set a budget (`limit` in `ai.json`).
