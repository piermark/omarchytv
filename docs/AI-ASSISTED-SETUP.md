# AI-assisted setup

You can let an AI coding assistant or agent (Claude Code, Codex CLI, Gemini CLI, Cursor, ChatGPT with a terminal,
Grok Bot, …) run the adb steps for you. **You stay in charge:** the agent must ask before every change, and you
should read each command before approving it. Nothing below contains anyone's IP, serial or account; fill in your own.

Guides the agent should follow: [INSTALL](../launcher/docs/INSTALL.md) and [DEBLOAT](DEBLOAT.md).

## Generic prompt (any assistant)

Copy, replace `<TV-IP>` and the APK path, paste:

```text
Goal: install the OmarchyTV launcher (package com.omarchytv.home) on my Android TV over adb, optionally set it
as home screen and screensaver, and optionally do a safe, reversible debloat. TV: <TV-IP>:5555, network debugging
is enabled. APK: ./omarchytv-release-2.12.0-beta.apk

HARD RULES (never break them, stop and ask if a step seems to need it):
1. Back up first: save `pm list packages -f`, `pm list packages -d`, the current HOME activity
   (`cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME`),
   `settings list global|secure|system` and `settings get secure screensaver_components` to local files.
2. Debloat ONLY with `pm disable-user --user 0 <pkg>`. NEVER `pm uninstall` (with or without --user), never
   `pm clear` on system apps.
3. NEVER change DNS or any `private_dns_*` setting.
4. Ask me before EACH package you disable and before every command that changes the TV. One at a time.
5. NEVER try to root, unlock, flash or sideload anything other than the APK above.
6. Do not touch other devices on my network. Do not print or store anything beyond the backup files.

Steps:
a) `adb connect <TV-IP>:5555`; `adb devices` must show it as "device" (I accept the prompt on the TV).
b) Verify the APK: `sha256sum` must be f1426db057fdc958fe562d1afd57a710b896e2f63d4fa6c2ec6c8ceefdfee11e and
   `apksigner verify --print-certs` must show signer SHA-256
   90aa68d9f45e623eadfd349ab1eb66042356f95ff6347a4568c3f3a78f8ea33d. Stop if either differs.
c) Backup (rule 1).
d) `adb install -r <apk>`; check `adb shell dumpsys package com.omarchytv.home | grep versionName`.
e) Only if I say yes: `adb shell cmd package set-home-activity --user 0 com.omarchytv.home/.MainActivity`.
f) Only if I say yes: note the old value, then
   `adb shell settings put secure screensaver_components com.omarchytv.home/.OmarchyDream` and
   `adb shell settings put secure screensaver_enabled 1`.
g) Only if I ask for a debloat: list packages, propose candidates from docs/DEBLOAT.md marked "verify on your
   model", and disable them one by one with my confirmation, logging each to disabled-by-me.txt.

Verify at the end and report: HOME resolves to com.omarchytv.home/.MainActivity (if chosen), screensaver_components
value, `pm list packages -d` diff against the backup, and how to undo every change (`pm enable <pkg>`, the old
screensaver value, the old HOME activity).
```

## Claude Code

Clone this repo and put this file in its root as `CLAUDE.md` (Claude Code reads it automatically), then start
`claude` in that folder and say "set up my TV at <TV-IP>".

```markdown
# CLAUDE.md — OmarchyTV TV setup

You help install OmarchyTV (com.omarchytv.home) on an Android TV over adb. Follow launcher/docs/INSTALL.md and
docs/DEBLOAT.md exactly.

Hard rules:
- Back up first (pm list packages -f, pm list packages -d, HOME activity, settings list global/secure/system,
  screensaver_components) into ./backup/ before any change. tools/backup-state.sh does this.
- Debloat only with `pm disable-user --user 0`. Never `pm uninstall`, never root, never flash.
- Never change DNS or private_dns_*; do not run tools/private-dns.sh.
- Ask before every command that changes the TV and before each package. Never batch-disable.
- Verify the APK SHA-256 and signer certificate (values in launcher/docs/INSTALL.md) before installing.
- Only talk to the TV address the user gives you.
- Finish with a verification summary and the exact undo commands.
```

Tip: keep Claude Code in its default "ask before running commands" mode for this.

## Codex CLI, Gemini CLI, Cursor, others

- **Codex CLI:** the same block works as `AGENTS.md` in the repo root; run in a mode that requires approval for
  shell commands.
- **Gemini CLI:** save it as `GEMINI.md` in the repo root.
- **Cursor:** save it as `.cursor/rules/omarchytv-setup.mdc` (or paste the generic prompt into the agent chat).
- **ChatGPT / chat-only assistants:** paste the generic prompt; they will give you commands to run yourself.
  Run them one by one and paste the output back.

## Grok Bot

Paste this into a Grok Bot chat. Grok Bot works on its own Linux box, so the TV must be reachable from where the
commands run: either run the adb commands on a computer on your home network that you connected to Grok Bot, or
ask Grok Bot for the commands and run them yourself.

```text
Help me install OmarchyTV (Android TV launcher, package com.omarchytv.home) from
<project URL>/-/releases on my Android TV at <TV-IP>:5555 using adb, following docs/INSTALL.md and
docs/DEBLOAT.md of the project. Rules: back up package lists and settings first; verify the APK SHA-256 and signing
certificate from INSTALL.md before installing; debloat only with `pm disable-user --user 0`, never uninstall, never
root; never touch DNS or private_dns_*; ask me before every change and before each package. Run commands only on
my computer on the same network as the TV (ask me which), and end with a verification report and undo commands.
```

A shareable Grok Bot template for OmarchyTV setup will be published later.

### Grok Bot & MCP Server Control (`2.12.0-beta+`)

In addition to ADB setup, OmarchyTV `2.12.0-beta` includes two built-in ways to use **Grok Bot** directly with your TV:
1. **On-TV Grok Bot WebApp & `> ask` Prompt:** Open **Grok Bot** directly from the Home screen shelf, the **Grok Bot & AI** hub (Main Menu, Workspace 4 `PC`, or Workspace 5 `System`), or Walker search (`> grok bot` / `> ask`). You only need to sign in once on the TV (persistent cookies/DOM storage in the sandboxed `:web` process, with D-pad spatial navigation and long-press Back virtual cursor), or pair your PC via `omarchy-link` to answer `> grok bot` / `> ask` prompts from the PC without logging in on the TV.
2. **MCP Server (`/mcp` on TV + `omarchy-tv mcp` on PC):** two separate servers, each with its own tools.
   - **Direct TV MCP endpoint:** `POST http://<TV-IP>:47800/mcp` (or `/v1/mcp`) using `Authorization: Bearer <link-token>` or `OTV1` HMAC signing. Its tools are `tv_status`, `tv_list_themes`, `tv_set_theme`, `tv_send_key`, `tv_list_apps`, `tv_launch_app`, `tv_screensaver`, `tv_ai_status` and `tv_cinemarchy`.
   - **PC MCP Bridge (`omarchy-tv mcp`):** Run `omarchy-tv mcp --stdio` for local MCP clients (Claude Desktop, Cursor, Hermes) or `omarchy-tv mcp --http --port 8990` (authenticated via `~/.config/omarchy-tv/mcp-token`) to expose its tools to your remote Grok Bot agent: `tv_status`, `tv_list_themes`, `tv_set_theme`, `tv_push_theme`, `tv_send_key`, `tv_list_apps`, `tv_launch_app`, `tv_screensaver`.

## How to verify (whatever assistant you used)

```sh
adb shell dumpsys package com.omarchytv.home | grep -E 'versionName|versionCode'
adb shell cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME
adb shell settings get secure screensaver_components
adb shell pm list packages -d          # compare with your backup
```
