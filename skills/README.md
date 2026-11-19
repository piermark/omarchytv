# Agent skills for OmarchyTV

Plain `SKILL.md` files (the Agent Skills format) that Claude Code, Hermes Agent and other agents can load.

| Skill | What it teaches the agent |
|---|---|
| [omarchytv-setup](omarchytv-setup/SKILL.md) | Install the PC plugin, pair with the TV, register the `omarchytv` MCP server (Claude Code, Codex, Hermes), make scoped tokens, reach the TV over Tailscale, fix the usual problems. |
| [omarchytv](../docs/examples/hermes-skill/omarchytv/SKILL.md) | Use the TV well once it is connected: when to notify, ask before anything irreversible, publish pages, post to rooms, follow the theme. |

## Install

```sh
# Claude Code (personal skills)
mkdir -p ~/.claude/skills
cp -r skills/omarchytv-setup docs/examples/hermes-skill/omarchytv ~/.claude/skills/

# Hermes Agent
mkdir -p ~/.hermes/skills
cp -r skills/omarchytv-setup docs/examples/hermes-skill/omarchytv ~/.hermes/skills/
```

Any other agent: point it at the `SKILL.md` file, or paste it into its instructions. Then ask it to
"set up OmarchyTV".

The setup skill never invents a pairing code, uses the smallest token scope unless you ask for more, never exposes
the TV to the internet and asks before installing anything or editing your agent's config.
