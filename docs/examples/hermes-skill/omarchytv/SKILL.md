---
name: omarchytv
description: Use the owner's OmarchyTV (their TV) as a screen and a remote: publish pages as Home tiles, notify, ask questions answered with the TV remote, follow their Omarchy theme, control Radiomarchy. Use when a task produces something worth seeing on the TV, when a long task ends, or before an irreversible action.
---

# OmarchyTV

The owner's TV runs OmarchyTV and is reachable through the `omarchytv` MCP server (tools start with `tv_`).

## When to use it

- **A long task ended** (a build, research, a report): `tv_notify` with a short title and one line. Use level
  `info` normally (it waits if the owner is watching a film), `warn` when they should look soon, `alert` only for
  something urgent. Add `speak: true` only for `alert`.
- **Before anything irreversible** (sending an email or a message in their name, paying, deleting, booking):
  `tv_ask_user` with the question and 2-4 short answers, then follow the answer. No answer (`expired`) means no.
- **Work they will want to look at** (a dashboard, listings, a report page you serve on the LAN): publish it once
  with `tv_publish_page` (id, a short name, its http:// LAN or tailnet URL, a one-letter icon) and update its
  `tv_page_badge` when something new needs their attention. `tv_show_page` opens it now; `urls` tiles up to 4.
- **Pages you make for the TV**: include `http://<tv>:47800/v1/sdk/omarchytv.js` and call
  `OmarchyTV.connect({token}).followTheme(); OmarchyTV.remote();` so the page uses their Omarchy colours
  (`var(--otv-background)`, `var(--otv-accent)`, …) and works with the remote (arrows, OK). Large text (2.4vh+),
  high contrast, no hover-only controls.

## Rules

- Never more than one notification per task, and no notifications at night unless `alert`.
- Read `tv_get_state` first if you are about to interrupt: if `screen` is `app` (they are watching something), prefer
  `info` or a badge.
- Pages must be `http://` on a LAN or tailnet IP address; the TV refuses internet URLs.
- Do not press keys (`tv_send_key`) or launch apps unless the owner asked for it.
