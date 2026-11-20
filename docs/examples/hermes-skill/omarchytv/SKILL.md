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
- **Scheduled reports** (a cron job, a nightly check, a room's summary): `tv_room_post` with a room name
  (`hermes`, `backups`, …) and one line. The TV Home keeps a pane per room with its last 5 lines, without
  interrupting anyone. These tools are there when Hermes uses `omarchy-link mcp`.
- **A dashboard the owner keeps on the Home** (2.22): `tv_widget_create` places a typed widget on a 12 x 6 grid
  (`stat` for one number, `lines` for a few label/value rows, `chart` for a series, `text`, `image`, `page`,
  `shortcuts`, Home Assistant scenes). Let it pick the first free slot unless the owner asked for a place; never
  use `push` to shove the owner's own widgets around. Keep it fresh with `tv_widget_update` (content only, a
  `notify` token is enough); `tv_widget_list` shows what is there and the free cells. One widget per thing, a
  stable id (`nas-disk`, `living-temp`), and delete what you no longer update. Group related widgets on a page of
  their own (`page: "server"` creates it); `tv_page_show` only when something there needs attention now.
- **"Remind me in 20 minutes"**: `tv_remind` with `when` (`20m`, `1h30m`, `18:45`) and the text. The TV says it
  aloud at that time.
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
