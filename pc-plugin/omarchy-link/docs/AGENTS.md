# Live AI agents on the TV

A TV widget shows what the AI coding agents on the Omarchy PC are doing right now:
- agent and project;
- status: thinking, running a tool, **waiting for you**, done, idle or error;
- the current step, the last few steps and the elapsed time.

Optionally the TV can also send an instruction to an agent session that is idle.

```sh
omarchy-link agents preview           # what the TV would see (works while the monitor is off)
omarchy-link agents enable            # turn the monitor on (all supported agents)
omarchy-link agents enable claude-code codex   # only these
omarchy-link agents enable claude-code codex t3-code   # include T3 threads
omarchy-link agents disable
omarchy-link agents hooks install     # optional: Claude Code hooks, a sharper "needs your input"
omarchy-link agents --json
```

## Privacy

- **Off by default:** nothing is read or sent until `agents enable`.
- **Read-only:** it uses only each agent's own local files. It calls no APIs and never reads credential files.
- **What a step shows:** the tool name plus a short target:
  - the first line of a command;
  - a file **basename**;
  - a URL **host**;
  - a search pattern.
- **What it never shows:** file contents, tool output, the model's thinking, environment values or full paths. The project is the basename of the cwd only.
- **Redaction:** API keys, tokens, JWTs, `Bearer`/`Basic`, `NAME=value` env assignments, `--password`/`--token` flags, credentials in URLs and long hex or base64 strings all become `•••`. Steps are cut to 72 characters.
- **Kept on the PC:** session IDs and cwd. The TV gets an opaque `agentId` such as `claude-code:7d0c6a2e-111`.
- **Filters** in `config.json`: `"agentMonitor": {"enabled": true, "agents": [...], "projects": [...], "hideProjects": [...], "steps": 8, "activeMinutes": 30, "maxAgents": 8}`.

## Per agent

| Agent | Monitor | How | Ask from the TV | How |
|---|---|---|---|---|
| **T3 Code** | yes | Read-only active-run metadata in `~/.t3/userdata/statev2.sqlite` (`T3CODE_HOME` overrides the base). Sends a redacted thread title, project basename, and status. Conversation, event, payload, and credential data are excluded. | **no** | Read-only: the TV shows the thread title, project and status in the agents widget. |
| **Claude Code** | yes | `~/.claude/projects/*/<session>.jsonl` (`tool_use`/`tool_result`/`stop_reason`). Optional hooks (`PreToolUse`, `PostToolUse`, `Notification`, `Stop`, …) write a sanitized event to `~/.local/state/omarchy-link/agent-events.jsonl`, giving the exact permission question. | yes, when the session is idle or done | `claude -p --resume <session> --permission-mode default` in that cwd, with the text on stdin. Tools that need approval are refused, not approved. |
| **Codex CLI** | yes | `~/.codex/sessions/**/rollout-*.jsonl` (`function_call`, `exec_approval_request` gives the question, `task_complete`) | yes, when idle | `codex exec resume <session> -c sandbox_mode="read-only" -c approval_policy="never" -` |
| **Grok CLI** | yes | `~/.grok/sessions/<cwd>/<session>/updates.jsonl` (ACP `tool_call`/`tool_call_update`), `summary.json` title, `active_sessions.json` for live sessions | yes, when idle | `grok -p … --resume <session> --cwd <cwd> --permission-mode default --no-auto-update` |
| **Hermes Agent** (Nous Research) | yes | `~/.hermes/state.db` (SQLite, opened **read-only**): `sessions` and `messages.tool_calls` | yes, for `cli`/`tui`/`oneshot` sessions, when idle | `hermes chat -q … --resume <session>`. The run goes through Hermes's own gateway and its approval settings. |
| **Grok Bot** (desktop app) | presence only | Transcripts live on the remote box and in an encrypted Electron store. The only local, non-secret signal is `~/.grokbot/local-exec-daemon.json` (`pid`, `inflightCount`): "running N local tool calls" or idle. Its credential and connection files are never opened. | **no** (`unsupported`) | There is no sanctioned local input API, and using the app's session tokens is off limits. |
| Gemini CLI | best effort | `~/.gemini/tmp/*/chats/*.json` | no | monitor only |
| opencode | not yet | | | |
| Omarchy Agents panel | no | It exposes usage limits only, not activity (used for the AI credits pane) | | |

The T3 reader samples at most once every five seconds. It prefers a running or waiting run over a queued next run for the same thread. Pending input requests show **waiting**; queued starts show **queued**. Completed and archived threads are excluded. Missing T3 installation produces no rows. An unsupported schema, read timeout, or active snapshot older than two minutes produces **Status unavailable**.

**"Waiting for you":**
- **Codex:** taken from its approval events, with the command as the question.
- **Claude Code:** taken from the `Notification` hook (exact text). Without hooks, a tool call with no result and nothing written for 45 s is shown as "Allow <step>?".
- **Answering:** a pending terminal prompt is **not** answered from the TV. Answer it on the PC.

## Instructions from the TV (opt-in)

Settings in `config.json`:
- `"agentControlFromTv": true` turns asks on (default **false**).
- `"agentConfirm": true` (the default) means every instruction first shows a PC notification ("TV asks Claude (shop): …"). Nothing runs until you click it (`omarchy-link agents approve <token>`, one-shot, 2-minute expiry).
- `"agentToolApprovalFromTv": true` (default **false**) is the only way TV instructions get edit or write permission:
  - Claude: `acceptEdits`;
  - Codex: `workspace-write`;
  - Grok: `--allow Edit/Write`.
  - Bash and other risky tools are still never auto-approved.
- `"agentControlAgents"` limits which agents accept asks (default Claude Code, Codex, Grok, Hermes).

**When an ask is refused:**
- the agent is working right now (`busy`; there is never a second writer next to a live turn);
- it is waiting for a terminal answer (`unsupported`);
- the agent or its folder is gone;
- the text is empty or longer than 2000 characters.

The reply text is redacted and cut to 1200 characters.

**Voice:** the TV turns speech from the remote's microphone into `text`, as it does for the assistant. The PC only ever receives text.
