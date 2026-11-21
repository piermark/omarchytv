"""OmarchyTV Model Context Protocol (MCP) server for Grok Bot, Claude, Hermes & Cursor.

Supports:
- stdio JSON-RPC 2.0 (`omarchy-tv mcp --stdio`) for local desktop agents
- HTTP JSON-RPC 2.0 (`omarchy-tv mcp --http [--port 8990] [--tunnel | --tailscale]`) with mandatory
  256-bit Bearer token authentication (~/.config/omarchy-tv/mcp-token, mode 0600)
  and optional `cloudflared` tunnel so cloud-hosted Grok Bot can control the TV safely.
- v2.21 `--tailscale`: listen on this PC's tailnet address only and answer only tailnet peers
  (100.64.0.0/10, fd7a:115c:a1e0::/48), still with the Bearer token: an agent on another box of your
  tailnet (Hermes, Claude Code on a server) reaches the TV without opening anything to the internet.

Security:
- Strict tool whitelist (TV status, themes, remote keys, apps, screensaver, AI status).
- Never exposes shell execution or local filesystem access on the PC.
"""

from __future__ import annotations

from . import VERSION

import hmac
import json
import os
import re
import secrets
import shutil
import subprocess
import sys
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

from . import themes

TOKEN_FILE = Path(os.environ.get("XDG_CONFIG_HOME") or (Path.home() / ".config")) / "omarchy-tv" / "mcp-token"

ALLOWED_TOOLS = (
    {
        "name": "tv_status",
        "description": "Read OmarchyTV status, active theme, capabilities, and foreground app.",
        "inputSchema": {"type": "object", "properties": {}},
    },
    {
        "name": "tv_list_themes",
        "description": "List bundled MIT Omarchy themes and custom themes installed on the TV.",
        "inputSchema": {"type": "object", "properties": {}},
    },
    {
        "name": "tv_set_theme",
        "description": "Switch the active Omarchy theme on the TV.",
        "inputSchema": {
            "type": "object",
            "properties": {
                "name": {"type": "string", "description": "Theme slug (e.g. tokyo-night, jade-horse, hermarchy)"},
                "bg": {"type": "integer", "description": "Optional background index (default 0)"},
            },
            "required": ["name"],
        },
    },
    {
        "name": "tv_push_theme",
        "description": "Push an Omarchy theme from ~/.config/omarchy/themes/<name> on the PC to the TV.",
        "inputSchema": {
            "type": "object",
            "properties": {"name": {"type": "string", "description": "Theme slug"}},
            "required": ["name"],
        },
    },
    {
        "name": "tv_send_key",
        "description": "Send a remote key to the TV (home, back, up, down, left, right, select, play_pause, volume_up, volume_down, mute).",
        "inputSchema": {
            "type": "object",
            "properties": {"key": {"type": "string", "description": "Remote key name"}},
            "required": ["key"],
        },
    },
    {
        "name": "tv_list_apps",
        "description": "List installed Android TV apps on the TV.",
        "inputSchema": {"type": "object", "properties": {}},
    },
    {
        "name": "tv_launch_app",
        "description": "Launch an Android TV app by package name.",
        "inputSchema": {
            "type": "object",
            "properties": {"package": {"type": "string", "description": "Android package name"}},
            "required": ["package"],
        },
    },
    {
        "name": "tv_get_theme",
        "description": "Read the TV's current theme: background, foreground, accent, accent2, the full colors.toml "
                       "palette and the wallpaper URL (schema omarchytv-theme/1). Use it to style pages and dashboards.",
        "inputSchema": {"type": "object", "properties": {}},
    },
    {
        "name": "tv_notify",
        "description": "Show a short message on the TV over whatever is playing, e.g. 'build finished' or "
                       "'I need your answer'. Needs agent alerts on in the TV's settings.",
        "inputSchema": {
            "type": "object",
            "properties": {
                "text": {"type": "string", "description": "One line, at most 200 characters"},
                "from": {"type": "string", "description": "Who is talking, e.g. Claude Code (at most 40 characters)"},
                "title": {"type": "string", "description": "Optional title, at most 60 characters"},
                "level": {"type": "string", "enum": ["info", "warn", "alert"], "description": "info waits while a film is on; warn and alert show at once"},
                "speak": {"type": "boolean", "description": "Also read it aloud on the TV"},
            },
            "required": ["text"],
        },
    },
    {
        "name": "tv_screensaver",
        "description": "Set or reset custom ASCII screensaver text on the TV.",
        "inputSchema": {
            "type": "object",
            "properties": {"text": {"type": "string", "description": "ASCII text (empty string resets to default)"}},
        },
    },
    {
        "name": "tv_get_state",
        "description": "What is on the TV now: screen (home, app, screensaver, off), the app, the theme, the music.",
        "inputSchema": {"type": "object", "properties": {}},
    },
    {
        "name": "tv_ask_user",
        "description": "Ask the person in front of the TV a question; they pick an answer with the remote. "
                       "Waits for the answer up to 'wait' seconds, then use tv_ask_result.",
        "inputSchema": {
            "type": "object",
            "properties": {
                "question": {"type": "string", "description": "One line, at most 200 characters"},
                "choices": {"type": "array", "items": {"type": "string"}, "description": "Up to 4 short answers (default Yes / No)"},
                "timeout": {"type": "integer", "description": "Seconds the question stays on the TV (10-300, default 120)"},
                "wait": {"type": "integer", "description": "Seconds to wait here for the answer (0-90, default 60)"},
                "from": {"type": "string", "description": "Who asks, e.g. Claude Code"},
                "speak": {"type": "boolean", "description": "Also read the question aloud (silent when the TV's tones and voice are off)"},
                "detail": {"type": "string", "description": "The full text to read before answering, e.g. the whole command (2000 characters, 40 lines), scrollable on the TV"},
            },
            "required": ["question"],
        },
    },
    {
        "name": "tv_ask_result",
        "description": "The state of a question from tv_ask_user: pending, answered (with the answer) or expired.",
        "inputSchema": {"type": "object", "properties": {"id": {"type": "string"}, "wait": {"type": "integer", "description": "0-25 s"}},
                        "required": ["id"]},
    },
    {
        "name": "tv_show_page",
        "description": "Open a web page of the LAN or tailnet full screen on the TV (http:// and an IP address, e.g. a "
                       "dashboard), or up to 4 tiled like Hyprland windows (Android TV). BACK closes it.",
        "inputSchema": {"type": "object", "properties": {"url": {"type": "string"},
                                                         "urls": {"type": "array", "items": {"type": "string"}}}},
    },
    {
        "name": "tv_close_page",
        "description": "Close the page opened with tv_show_page.",
        "inputSchema": {"type": "object", "properties": {}},
    },
    {
        "name": "tv_radio_now",
        "description": "What Radiomarchy (Omarchy Radio on the TV) is playing.",
        "inputSchema": {"type": "object", "properties": {}},
    },
    {
        "name": "tv_radio",
        "description": "Control the radio: play (resume, or a station by name or id from tv_radio_stations), pause, next, "
                       "previous, stop, volume (0-100, the radio's own).",
        "inputSchema": {"type": "object", "properties": {
            "action": {"type": "string", "enum": ["play", "pause", "next", "previous", "stop", "volume"]},
            "station": {"type": "string"}, "volume": {"type": "integer", "minimum": 0, "maximum": 100}},
            "required": ["action"]},
    },
    {"name": "tv_radio_stations", "description": "The radio stations: Omarchy Radio first, then the owner's (favourites first).",
     "inputSchema": {"type": "object", "properties": {}}},
    {"name": "tv_radio_station_add", "description": "Add an internet radio station (Icecast / Shoutcast, HLS .m3u8, or a .pls / .m3u "
                                                    "playlist). https://, or http:// on the LAN.",
     "inputSchema": {"type": "object", "properties": {"name": {"type": "string"}, "url": {"type": "string"},
                                                      "logo": {"type": "string"}}, "required": ["name", "url"]}},
    {"name": "tv_radio_station_remove", "description": "Remove a radio station (not Omarchy Radio).",
     "inputSchema": {"type": "object", "properties": {"id": {"type": "string"}}, "required": ["id"]}},
    {"name": "tv_radio_sleep", "description": "Stop the radio after some minutes (0 turns the timer off).",
     "inputSchema": {"type": "object", "properties": {"minutes": {"type": "integer", "minimum": 0, "maximum": 240}},
                     "required": ["minutes"]}},
    {
        "name": "tv_open",
        "description": "Open a part of OmarchyTV on the TV: home, radiomarchy, cinemarchy, arcade, office or skill "
                       "(the agent skill as a QR).",
        "inputSchema": {"type": "object", "properties": {"target": {"type": "string",
                                                                    "enum": ["home", "radiomarchy", "cinemarchy", "arcade", "office", "skill"]}},
                        "required": ["target"]},
    },
    {"name": "tv_skill_list", "description": "The skills the TV can install (id, name, author, permissions) and the installed ones "
                                            "with their instructions for you (agent).", "inputSchema": {"type": "object", "properties": {}}},
    {"name": "tv_skill_install", "description": "Ask the owner to install a skill: the TV shows it with its permissions and the owner "
                                               "answers with the remote.",
     "inputSchema": {"type": "object", "properties": {"id": {"type": "string"}}, "required": ["id"]}},
    {"name": "tv_skill_remove", "description": "Remove an installed skill with its widgets and its page.",
     "inputSchema": {"type": "object", "properties": {"id": {"type": "string"}}, "required": ["id"]}},
    {"name": "tv_live_update", "description": "Tell the TV's AI LIVE channel what you are doing now, like a live broadcast: a short line, "
                                             "a progress 0-100 and a preview (image URL, diff, text or numbers). The preview also shows "
                                             "on your next question, so the owner sees the work before approving it.",
     "inputSchema": {"type": "object", "properties": {"text": {"type": "string"}, "progress": {"type": "integer"},
                                                      "preview": {"type": "object"}}}},
    {"name": "tv_live", "description": "The AI LIVE channel: every agent on it and the last events.",
     "inputSchema": {"type": "object", "properties": {}}},
    {"name": "tv_shift_start", "description": "Start a night shift: the owner's tasks, its end (\"07:00\" or epoch ms, within 16 h) and "
                                             "when the TV shows the morning page (wake). The TV stays silent; write the diary with "
                                             "tv_shift_log and leave anything risky for the morning with tv_shift_queue.",
     "inputSchema": {"type": "object", "properties": {"tasks": {"type": "array", "items": {"type": "string"}},
                                                      "until": {"type": "string"}, "wake": {"type": "string"},
                                                      "replace": {"type": "boolean"}}, "required": ["tasks", "until"]}},
    {"name": "tv_shift_end", "description": "End the night shift now.", "inputSchema": {"type": "object", "properties": {}}},
    {"name": "tv_shift_status", "description": "The night shift: running / ended / none, tasks, end, wake, counts.",
     "inputSchema": {"type": "object", "properties": {}}},
    {"name": "tv_shift_log", "description": "A line in the night shift's diary: level ok | fail | info, text, optional detail and task index.",
     "inputSchema": {"type": "object", "properties": {"level": {"type": "string", "enum": ["ok", "fail", "info"]},
                                                      "text": {"type": "string"}, "detail": {"type": "string"},
                                                      "task": {"type": "integer"}}, "required": ["text"]}},
    {"name": "tv_shift_queue", "description": "Leave a risky step (push, merge, post, payment) for the owner's morning instead of doing it. "
                                             "Returns an id for tv_shift_decision. Only the owner answers, on the TV.",
     "inputSchema": {"type": "object", "properties": {"title": {"type": "string"}, "detail": {"type": "string"},
                                                      "risk": {"type": "string"}}, "required": ["title"]}},
    # v2.22 the studio
    {"name": "tv_briefing_set", "description": "Prepare the owner's morning briefing: 1-8 sections of short lines (overnight, today, "
                                             "the weather, what waits) and a text to read aloud. It goes on AI LIVE at the wake time "
                                             "(\"07:30\") or with tv_briefing_show.",
     "inputSchema": {"type": "object", "properties": {
         "title": {"type": "string"}, "say": {"type": "string", "description": "read aloud, ≤1500"},
         "wake": {"type": "string", "description": "\"07:30\" (optional)"},
         "sections": {"type": "array", "description": "[{title, lines: [≤8 short strings]}]", "items": {"type": "object"}}},
         "required": ["sections"]}},
    {"name": "tv_briefing_show", "description": "Put the morning briefing on AI LIVE now.", "inputSchema": {"type": "object", "properties": {}}},
    {"name": "tv_debate_start", "description": "Start a debate on AI LIVE: one question several agents answer side by side; the owner "
                                             "picks the winner with the remote. Returns its id.",
     "inputSchema": {"type": "object", "properties": {"topic": {"type": "string"}}, "required": ["topic"]}},
    {"name": "tv_debate_post", "description": "Post your answer to a debate (≤1200 characters, optional preview); a second post replaces "
                                            "the first.",
     "inputSchema": {"type": "object", "properties": {"id": {"type": "string"}, "text": {"type": "string"},
                                                      "preview": {"type": "object"}}, "required": ["id", "text"]}},
    {"name": "tv_debate_result", "description": "A debate's answers, and the winner once the owner picked one.",
     "inputSchema": {"type": "object", "properties": {"id": {"type": "string"}}, "required": ["id"]}},
    {"name": "tv_scene_run", "description": "Android TV: run a scene. {name} runs a saved one at once; {steps: [{tool, args}]} asks the "
                                          "owner on the TV first. Steps use tv_ha_run, tv_launch_app, tv_set_theme, tv_radio, "
                                          "tv_send_key, tv_open, tv_notify, tv_screensaver, tv_page_show.",
     "inputSchema": {"type": "object", "properties": {"name": {"type": "string"},
                                                      "steps": {"type": "array", "items": {"type": "object"}}}}},
    {"name": "tv_scene_save", "description": "Android TV: save a scene under a name after the owner's OK on the TV.",
     "inputSchema": {"type": "object", "properties": {"name": {"type": "string"},
                                                      "steps": {"type": "array", "items": {"type": "object"}}},
                     "required": ["name", "steps"]}},
    {"name": "tv_scene_list", "description": "Android TV: the saved scenes and their steps.", "inputSchema": {"type": "object", "properties": {}}},
    {"name": "tv_scene_result", "description": "Android TV: the owner's answer to a scene question (pending, ran, saved, denied).",
     "inputSchema": {"type": "object", "properties": {"id": {"type": "string"}}, "required": ["id"]}},
    {"name": "tv_shift_decision", "description": "The owner's answer to a queued step: pending, approved or denied.",
     "inputSchema": {"type": "object", "properties": {"id": {"type": "string"}}, "required": ["id"]}},
    {"name": "tv_settings_get", "description": "The TV's settings (theme, Home, plugins, Home Assistant URL, agents…) as "
                                              "{values, secrets}; secrets only say whether they are set.",
     "inputSchema": {"type": "object", "properties": {"section": {"type": "string", "enum": [
         "appearance", "home", "screensaver", "plugins", "homeassistant", "agents"]}}}},
    {"name": "tv_settings_set", "description": "Change one TV setting, with the same checks as the web settings page "
                                              "(e.g. key \"uiSize\", value \"large\").",
     "inputSchema": {"type": "object", "properties": {"key": {"type": "string"}, "value": {}}, "required": ["key", "value"]}},
    {"name": "tv_skill_qr", "description": "Show the OmarchyTV agent skill as a QR on the TV, for the owner's phone or "
                                          "another agent. Only the public URL.",
     "inputSchema": {"type": "object", "properties": {}}},
    {
        "name": "tv_list_pages",
        "description": "The agent pages published as tiles on the TV's Home.",
        "inputSchema": {"type": "object", "properties": {}},
    },
    {
        "name": "tv_publish_page",
        "description": "Publish (or update) a LAN / tailnet page as a tile on the TV's Home: id, name, url, optional "
                       "one-letter icon and badge count. At most 200 (16 before 2.22).",
        "inputSchema": {"type": "object", "properties": {
            "id": {"type": "string", "description": "a-z, 0-9, dashes"}, "name": {"type": "string"},
            "url": {"type": "string", "description": "http://<LAN or 100.x address>[:port]/path"},
            "icon": {"type": "string"}, "badge": {"type": "integer"}}, "required": ["id", "name", "url"]},
    },
    {
        "name": "tv_page_badge",
        "description": "Set the badge count of a published page (0 hides it), e.g. new replies.",
        "inputSchema": {"type": "object", "properties": {"id": {"type": "string"}, "badge": {"type": "integer"}}, "required": ["id", "badge"]},
    },
    {
        "name": "tv_remove_page",
        "description": "Remove a published page from the TV's Home.",
        "inputSchema": {"type": "object", "properties": {"id": {"type": "string"}}, "required": ["id"]},
    },
    # v2.22 #157: positioned, typed widgets on the Home (12 x 6 cells per page); same names and schemas as the TV's MCP
    {
        "name": "tv_widget_types",
        "description": "The Home widget types, the shape of each one's data, the grid (12 x 6 cells per page) and the limits.",
        "inputSchema": {"type": "object", "properties": {}},
    },
    {
        "name": "tv_widget_list",
        "description": "The widgets on the TV's Home (one page, or every page) with position and size, and the free cells.",
        "inputSchema": {"type": "object", "properties": {"page": {"type": "integer", "description": "Page, 1 = the Home"}}},
    },
    {
        "name": "tv_widget_create",
        "description": "Create a widget on the Home. type: lines, stat, text, image, chart, page, shortcuts, "
                       "homeassistant.scenes, homeassistant.entities, radio, qr (see tv_widget_types). Without x/y it takes the "
                       "first free slot; an occupied area answers cell_taken with free slots, unless push is true.",
        "inputSchema": {"type": "object", "properties": {
            "id": {"type": "string"}, "type": {"type": "string"}, "page": {"type": "integer"},
            "x": {"type": "integer"}, "y": {"type": "integer"}, "w": {"type": "integer"}, "h": {"type": "integer"},
            "title": {"type": "string"}, "icon": {"type": "string"}, "ttl": {"type": "integer"},
            "data": {"type": "object"}, "push": {"type": "boolean"}}, "required": ["id", "type"]},
    },
    {
        "name": "tv_widget_update",
        "description": "Change a widget's content (title, icon, ttl, data). Needs only the notify scope.",
        "inputSchema": {"type": "object", "properties": {
            "id": {"type": "string"}, "title": {"type": "string"}, "icon": {"type": "string"},
            "ttl": {"type": "integer"}, "data": {"type": "object"}}, "required": ["id"]},
    },
    {
        "name": "tv_widget_move",
        "description": "Move or resize a widget (page, x, y, w, h). cell_taken with free slots if the area is not free, "
                       "unless push is true.",
        "inputSchema": {"type": "object", "properties": {
            "id": {"type": "string"}, "page": {"type": "integer"}, "x": {"type": "integer"}, "y": {"type": "integer"},
            "w": {"type": "integer"}, "h": {"type": "integer"}, "push": {"type": "boolean"}}, "required": ["id"]},
    },
    {
        "name": "tv_widget_delete",
        "description": "Remove a widget from the Home.",
        "inputSchema": {"type": "object", "properties": {"id": {"type": "string"}}, "required": ["id"]},
    },
    # v2.22 #158 / #159 Home widget pages (tv_list_pages / tv_show_page are the LAN agent pages)
    {"name": "tv_page_list", "description": "The Home widget pages (index, id, name, widgets, hidden, rotate) and the one on screen.",
     "inputSchema": {"type": "object", "properties": {}}},
    {"name": "tv_page_current", "description": "The Home widget page on screen now (index 0 = the classic Home).",
     "inputSchema": {"type": "object", "properties": {}}},
    {"name": "tv_page_create", "description": "Create an empty Home widget page (name, id, after).",
     "inputSchema": {"type": "object", "properties": {"name": {"type": "string"}, "id": {"type": "string"},
                                                       "after": {"type": ["integer", "string"]}}}},
    {"name": "tv_page_rename", "description": "Rename a Home widget page.",
     "inputSchema": {"type": "object", "properties": {"page": {"type": ["integer", "string"]}, "name": {"type": "string"}},
                     "required": ["page", "name"]}},
    {"name": "tv_page_reorder", "description": "Move a Home widget page to position 'to' (page 1 stays first).",
     "inputSchema": {"type": "object", "properties": {"page": {"type": ["integer", "string"]}, "to": {"type": "integer"}},
                     "required": ["page", "to"]}},
    {"name": "tv_page_delete", "description": "Remove a Home widget page (not page 1); its widgets go to moveWidgetsTo or away.",
     "inputSchema": {"type": "object", "properties": {"page": {"type": ["integer", "string"]},
                                                       "moveWidgetsTo": {"type": ["integer", "string"]}}, "required": ["page"]}},
    {"name": "tv_page_show", "description": "Bring a Home widget page to the screen.",
     "inputSchema": {"type": "object", "properties": {"page": {"type": ["integer", "string"]}}, "required": ["page"]}},
    # v2.22 #160 Home Assistant through the TV's own connection (the token never leaves the TV)
    {"name": "tv_ha_status", "description": "Is Home Assistant set up on the TV and reachable (configured, reachable, version, host).",
     "inputSchema": {"type": "object", "properties": {}}},
    {"name": "tv_ha_list", "description": "Home Assistant entities the TV sees: [{entity_id, name, state, unit}], optionally of one "
                                          "domain (scene, script, light, switch, sensor, …) and matching a query.",
     "inputSchema": {"type": "object", "properties": {"domain": {"type": "string"}, "query": {"type": "string"}}}},
    {"name": "tv_ha_run", "description": "Run a scene, script or automation, or toggle a light / switch / fan / input_boolean.",
     "inputSchema": {"type": "object", "properties": {"entity_id": {"type": "string"}}, "required": ["entity_id"]}},
    {
        "name": "tv_diagnostics",
        "description": "Diagnostics for troubleshooting: launcher version, device, network (LAN, Tailscale), Link, "
                       "recent log lines. No secrets.",
        "inputSchema": {"type": "object", "properties": {}},
    },
)

# v2.21 scopes, as on the TV: read < notify < control. A server reachable from the internet (--tunnel,
# --cloudflare) defaults to notify: an agent in the cloud can read and talk to you, not drive the TV.
SCOPES = ("read", "notify", "control")
WIDGET_CREATE_KEYS = ("id", "type", "page", "x", "y", "w", "h", "title", "icon", "ttl", "data", "push")
TOOL_SCOPE = {
    "tv_status": "read", "tv_list_themes": "read", "tv_list_apps": "read", "tv_get_theme": "read", "tv_get_state": "read",
    "tv_radio_now": "read", "tv_radio": "control", "tv_open": "control",
    "tv_radio_stations": "read", "tv_radio_station_add": "control", "tv_radio_station_remove": "control",
    "tv_radio_sleep": "control", "tv_skill_qr": "control",
    "tv_settings_get": "read", "tv_settings_set": "control",
    "tv_shift_start": "control", "tv_shift_end": "control", "tv_shift_status": "read",
    "tv_shift_log": "notify", "tv_shift_queue": "notify", "tv_shift_decision": "notify",
    "tv_live_update": "notify", "tv_live": "read",
    "tv_skill_list": "read", "tv_skill_install": "control", "tv_skill_remove": "control",
    "tv_briefing_set": "notify", "tv_briefing_show": "control",
    "tv_debate_start": "notify", "tv_debate_post": "notify", "tv_debate_result": "notify",
    "tv_scene_run": "control", "tv_scene_save": "control", "tv_scene_list": "read", "tv_scene_result": "control",
    "tv_list_pages": "read", "tv_page_badge": "notify", "tv_publish_page": "control", "tv_remove_page": "control",
    "tv_notify": "notify", "tv_ask_user": "notify", "tv_ask_result": "notify",
    "tv_set_theme": "control", "tv_push_theme": "control", "tv_send_key": "control", "tv_launch_app": "control",
    "tv_screensaver": "control", "tv_show_page": "control", "tv_close_page": "control", "tv_diagnostics": "control",
    "tv_widget_types": "read", "tv_widget_list": "read", "tv_widget_update": "notify",
    "tv_widget_create": "control", "tv_widget_move": "control", "tv_widget_delete": "control",
    "tv_page_list": "read", "tv_page_current": "read", "tv_page_create": "control", "tv_page_rename": "control",
    "tv_page_reorder": "control", "tv_page_delete": "control", "tv_page_show": "control",
    "tv_ha_status": "read", "tv_ha_list": "read", "tv_ha_run": "control",
}


def allowed(tool: str, scope: str) -> bool:
    need = TOOL_SCOPE.get(tool)
    return need is not None and SCOPES.index(scope) >= SCOPES.index(need)


def ensure_token(path: Path | None = None) -> str:
    p = path or TOKEN_FILE
    if p.is_file():
        tok = p.read_text(encoding="utf-8").strip()
        if len(tok) >= 32:
            os.chmod(p, 0o600)
            return tok
    p.parent.mkdir(parents=True, exist_ok=True, mode=0o700)
    tok = secrets.token_urlsafe(32)
    p.write_text(tok + "\n", encoding="utf-8")
    os.chmod(p, 0o600)
    return tok


LEVELS = ("info", "warn", "alert")


def default_max_level(public: bool, given: str | None) -> str:
    """v2.22 the loudest tv_notify level this server passes on: alert locally, warn through the internet."""
    if given in LEVELS:
        return given
    return "warn" if public else "alert"


def handle_rpc(req: dict, tv_client, scope: str = "control", max_level: str = "alert") -> dict | None:
    """Process one MCP JSON-RPC 2.0 request dict and return response dict (or None for notifications)."""
    method = req.get("method", "")
    req_id = req.get("id")
    params = req.get("params") if isinstance(req.get("params"), dict) else {}

    if method.startswith("notifications/") and "id" not in req:
        return None   # a notification gets no answer (HTTP: 202, no body)
    if method == "notifications/initialized":
        return {"jsonrpc": "2.0", "id": req_id, "result": {}}
    if method == "initialize":
        return {
            "jsonrpc": "2.0",
            "id": req_id,
            "result": {
                "protocolVersion": "2024-11-05",
                "capabilities": {"tools": {}},
                "serverInfo": {"name": "omarchy-tv-mcp", "version": VERSION},
            },
        }
    if method == "ping":
        return {"jsonrpc": "2.0", "id": req_id, "result": {}}
    if method == "tools/list":
        return {"jsonrpc": "2.0", "id": req_id, "result": {"tools": [t for t in ALLOWED_TOOLS if allowed(t["name"], scope)]}}
    if method == "tools/call":
        name = params.get("name", "")
        args = params.get("arguments") if isinstance(params.get("arguments"), dict) else {}
        if name not in {t["name"] for t in ALLOWED_TOOLS}:
            return {"jsonrpc": "2.0", "id": req_id, "error": {"code": -32601, "message": f"Unknown tool: {name}"}}
        if not allowed(name, scope):
            return {"jsonrpc": "2.0", "id": req_id,
                    "error": {"code": -32601, "message": f"Not allowed on this server (scope {scope}): {name}"}}
        try:
            out = _call_tool(name, args, tv_client, max_level)
            return {
                "jsonrpc": "2.0",
                "id": req_id,
                "result": {"content": [{"type": "text", "text": json.dumps(out)}], "isError": False},
            }
        except Exception as e:
            return {
                "jsonrpc": "2.0",
                "id": req_id,
                "result": {"content": [{"type": "text", "text": json.dumps({"error": str(e)})}], "isError": True},
            }
    return {"jsonrpc": "2.0", "id": req_id, "error": {"code": -32601, "message": f"Method not found: {method}"}}


def _call_tool(name: str, args: dict, c, max_level: str = "alert") -> dict:
    if name == "tv_status":
        return c.status()
    if name == "tv_list_themes":
        return themes.list_tv(c)
    if name == "tv_set_theme":
        tname = str(args.get("name", "")).strip()
        bg = int(args.get("bg", 0) or 0)
        return themes.select(c, tname, bg)
    if name == "tv_push_theme":
        tname = str(args.get("name", "")).strip()
        found = [th for th in themes.user_themes() if th.name == tname]
        if not found:
            raise ValueError(f"theme not found in ~/.config/omarchy/themes: {tname}")
        return themes.push(c, found[0])
    if name == "tv_send_key":
        return c.key(str(args.get("key", "")).strip())
    if name == "tv_list_apps":
        return c.apps()
    if name == "tv_launch_app":
        return c.launch(str(args.get("package", "")).strip())
    if name == "tv_get_theme":
        return _tv_theme(c)
    if name == "tv_notify":
        text = str(args.get("text", "")).strip()
        if not text:
            raise ValueError("text is required")
        level = args.get("level") if args.get("level") in LEVELS else "info"
        speak = args.get("speak") is True
        capped = LEVELS.index(level) > LEVELS.index(max_level)   # v2.22: the owner's rule, as the TV does for tokens
        if capped:
            level, speak = max_level, False
        out = c.request("POST", "/v1/notify", {"text": text[:200], "title": str(args.get("title") or "")[:60], "level": level,
                                                "speak": speak, "from": str(args.get("from") or "AI agent")[:40]})
        if capped and isinstance(out, dict):
            out = dict(out, capped=level)
        return out
    if name == "tv_get_state":
        return c.request("GET", "/v1/state")
    if name == "tv_ask_user":
        q = str(args.get("question", "")).strip()
        if not q:
            raise ValueError("question is required")
        r = c.request("POST", "/v1/ask", {"question": q[:200], "choices": [str(x)[:24] for x in (args.get("choices") or [])][:4],
                                         "timeout": max(10, min(300, int(args.get("timeout") or 120))),
                                         "from": str(args.get("from") or "AI agent")[:40],
                                         **({"speak": True} if args.get("speak") is True else {}),
                                         **({"detail": str(args["detail"])[:2000]} if args.get("detail") else {})})
        return _await_ask(c, r, max(0, min(90, int(args.get("wait", 60) or 0))))
    if name == "tv_ask_result":
        aid = str(args.get("id", ""))
        if not re.fullmatch(r"[0-9a-f]{16}", aid):
            raise ValueError("bad id")
        return c.request("GET", f"/v1/ask/{aid}?wait={max(0, min(25, int(args.get('wait', 0) or 0)))}", timeout=35)
    if name == "tv_show_page":
        urls = [str(u) for u in (args.get("urls") or [])][:4]
        return c.request("POST", "/v1/page", {"urls": urls} if urls else {"url": str(args.get("url", ""))})
    if name == "tv_close_page":
        return c.request("DELETE", "/v1/page")
    if name in ("tv_publish_page", "tv_page_badge", "tv_remove_page"):
        pid = str(args.get("id", ""))
        if not re.fullmatch(r"[a-z0-9][a-z0-9-]{0,39}", pid):
            raise ValueError("id: a-z, 0-9, dashes")
        if name == "tv_publish_page":
            return c.request("PUT", f"/v1/pages/{pid}", {"name": str(args.get("name", ""))[:32], "url": str(args.get("url", "")),
                                                         "icon": str(args.get("icon", ""))[:2], "badge": int(args.get("badge") or 0)})
        if name == "tv_page_badge":
            return c.request("POST", f"/v1/pages/{pid}/badge", {"badge": int(args.get("badge") or 0)})
        return c.request("DELETE", f"/v1/pages/{pid}")
    if name == "tv_list_pages":
        return c.request("GET", "/v1/pages")
    if name.startswith("tv_ha_"):   # v2.22 #160
        from urllib.parse import urlencode
        if name == "tv_ha_status":
            return c.request("GET", "/v1/ha/status")
        if name == "tv_ha_list":
            return c.request("GET", "/v1/ha/states?" + urlencode({"domain": str(args.get("domain", ""))[:32],
                                                                  "q": str(args.get("query", ""))[:64]}))
        return c.request("POST", "/v1/ha/run", {"entity_id": str(args.get("entity_id", ""))[:100]})
    if name.startswith("tv_page_"):   # v2.22 #158: the TV checks everything
        from urllib.parse import quote
        ref = quote(str(args.get("page", "")), safe="")
        if name == "tv_page_list":
            return c.request("GET", "/v1/widget-pages")
        if name == "tv_page_current":
            return c.request("GET", "/v1/widget-pages/current")
        if name == "tv_page_create":
            return c.request("POST", "/v1/widget-pages", {k: args[k] for k in ("name", "id", "after") if k in args})
        if not ref:
            raise ValueError("page is required")
        if name == "tv_page_rename":
            return c.request("PATCH", f"/v1/widget-pages/{ref}", {"name": str(args.get("name", ""))[:32]})
        if name == "tv_page_reorder":
            return c.request("PATCH", f"/v1/widget-pages/{ref}", {"to": int(args.get("to") or 0)})
        if name == "tv_page_delete":
            return c.request("DELETE", f"/v1/widget-pages/{ref}",
                             {"moveWidgetsTo": args["moveWidgetsTo"]} if "moveWidgetsTo" in args else None)
        return c.request("POST", f"/v1/widget-pages/{ref}/show", {})
    if name.startswith("tv_widget_"):   # v2.22 #157: the TV checks and places everything
        from urllib.parse import quote
        wid = quote(str(args.get("id", "")), safe="")
        if name == "tv_widget_types":
            return c.request("GET", "/v1/widgets/types")
        if name == "tv_widget_list":
            page = args.get("page")
            return c.request("GET", "/v1/widgets/list" + (f"?page={int(page)}" if isinstance(page, int) else ""))
        if name == "tv_widget_create":
            return c.request("POST", "/v1/widgets/item", {k: v for k, v in args.items() if k in WIDGET_CREATE_KEYS})
        if name == "tv_widget_delete":
            return c.request("DELETE", f"/v1/widgets/item/{wid}")
        keys = ("title", "icon", "ttl", "data") if name == "tv_widget_update" else ("page", "x", "y", "w", "h", "push")
        body = {k: v for k, v in args.items() if k in keys}
        if not body:
            raise ValueError("nothing to change")
        return c.request("PATCH", f"/v1/widgets/item/{wid}", body)
    if name == "tv_radio_now":
        return c.request("GET", "/v1/radio")
    if name == "tv_radio":
        body = {"action": str(args.get("action", ""))}
        if args.get("station"):
            body["station"] = str(args["station"])
        if "volume" in args:
            body["volume"] = int(args["volume"])
        return c.request("POST", "/v1/radio", body)
    if name == "tv_radio_stations":
        return c.request("GET", "/v1/radio/stations")
    if name == "tv_radio_station_add":
        return c.request("POST", "/v1/radio/stations", {k: str(args[k]) for k in ("name", "url", "logo") if args.get(k)})
    if name == "tv_radio_station_remove":
        sid = str(args.get("id", ""))
        if not sid or not all(ch.isalnum() or ch == "-" for ch in sid):
            raise ValueError("bad station id")
        return c.request("DELETE", f"/v1/radio/stations/{sid}")
    if name == "tv_radio_sleep":
        return c.request("POST", "/v1/radio", {"action": "sleep", "minutes": int(args.get("minutes", 0))})
    if name == "tv_skill_list":   # v2.22 skills
        return c.request("GET", "/v1/skills")
    if name in ("tv_skill_install", "tv_skill_remove"):
        sid = str(args.get("id", ""))
        if not re.fullmatch(r"[a-z0-9][a-z0-9-]{1,39}", sid):
            raise ValueError("bad skill id")
        if name == "tv_skill_install":
            return c.request("POST", f"/v1/skills/{sid}/install", {})
        return c.request("DELETE", f"/v1/skills/{sid}")
    if name == "tv_briefing_set":   # v2.22 the studio
        b = {k: args[k] for k in ("title", "say", "wake", "sections") if k in args}
        return c.request("POST", "/v1/briefing", b)
    if name == "tv_briefing_show":
        return c.request("POST", "/v1/briefing/show", {})
    if name == "tv_debate_start":
        return c.request("POST", "/v1/debate", {"topic": str(args.get("topic", ""))})
    if name in ("tv_debate_post", "tv_debate_result", "tv_scene_result"):
        sid = str(args.get("id", ""))
        if not re.fullmatch(r"[a-z0-9]{1,16}", sid):
            raise ValueError("bad id")
        if name == "tv_debate_post":
            return c.request("POST", f"/v1/debate/{sid}", {k: args[k] for k in ("text", "preview") if k in args})
        return c.request("GET", f"/v1/debate/{sid}" if name == "tv_debate_result" else f"/v1/scene/{sid}")
    if name == "tv_scene_list":
        return c.request("GET", "/v1/scenes")
    if name in ("tv_scene_run", "tv_scene_save"):
        return c.request("POST", "/v1/scene/run" if name == "tv_scene_run" else "/v1/scene/save",
                         {k: args[k] for k in ("name", "steps") if k in args})
    if name == "tv_live":   # v2.22 AI LIVE
        return c.request("GET", "/v1/live")
    if name == "tv_live_update":
        return c.request("POST", "/v1/live", {k: args[k] for k in ("text", "progress", "preview") if k in args})
    if name.startswith("tv_shift_"):   # v2.22 the night shift
        if name == "tv_shift_status":
            return c.request("GET", "/v1/shift")
        if name == "tv_shift_decision":
            qid = str(args.get("id", ""))
            if not re.fullmatch(r"q\d{1,6}", qid):
                raise ValueError("bad id")
            return c.request("GET", f"/v1/shift/queue/{qid}")
        op = name[len("tv_shift_"):]
        keys = {"start": ("tasks", "until", "wake", "replace"), "end": (), "log": ("level", "text", "detail", "task"),
                "queue": ("title", "detail", "risk")}[op]
        return c.request("POST", f"/v1/shift/{op}", {k: args[k] for k in keys if k in args})
    if name == "tv_settings_get":
        sec = str(args.get("section", "") or "")
        if sec and not sec.isalpha():
            raise ValueError("bad section")
        return c.request("GET", "/v1/settings" + (f"?section={sec}" if sec else ""))
    if name == "tv_settings_set":
        key = str(args.get("key", ""))
        if not key or len(key) > 40 or "value" not in args:
            raise ValueError("key and value")
        return c.request("PATCH", "/v1/settings", {key: args["value"]})
    if name == "tv_skill_qr":
        return c.request("POST", "/v1/open", {"target": "skill"})
    if name == "tv_open":
        return c.request("POST", "/v1/open", {"target": str(args.get("target", ""))})
    if name == "tv_diagnostics":
        return c.request("GET", "/v1/diagnostics")
    if name == "tv_screensaver":
        return c.request("PUT", "/v1/screensaver", {"text": str(args.get("text", ""))})
    raise ValueError(f"unsupported tool: {name}")


def _await_ask(c, r: dict, wait: int) -> dict:
    """Long-poll the TV (25 s at a time) until the answer, the deadline or `wait` seconds."""
    import time
    end = time.time() + wait
    while r.get("state") == "pending" and time.time() < end:
        step = int(max(0, min(25, end - time.time())))
        r = c.request("GET", f"/v1/ask/{r['id']}?wait={step}", timeout=step + 10)
        if step == 0:
            break
    return r


def _tv_theme(c) -> dict:
    """GET /v1/theme on the TV: read-only and unsigned (the TV answers it to any LAN app)."""
    import http.client
    host, port = getattr(c, "host", None), getattr(c, "port", 47800)
    if not host:
        return c.request("GET", "/v1/theme")
    conn = http.client.HTTPConnection(host, port, timeout=5)
    try:
        conn.request("GET", "/v1/theme")
        r = conn.getresponse()
        data = json.loads(r.read(1 << 20) or b"{}")
        if r.status != 200:
            raise ValueError(f"TV answered {r.status}: {data.get('error', '')}")
        return data
    finally:
        conn.close()


TAILNET = ("100.64.0.0/10", "fd7a:115c:a1e0::/48")


def is_tailnet(addr: str) -> bool:
    import ipaddress
    try:
        ip = ipaddress.ip_address(addr.split("%", 1)[0])
    except ValueError:
        return False
    if getattr(ip, "ipv4_mapped", None):
        ip = ip.ipv4_mapped
    return any(ip in ipaddress.ip_network(n) for n in TAILNET if ipaddress.ip_network(n).version == ip.version)


def tailnet_ip(run=subprocess.run) -> str | None:
    """This PC's Tailscale IPv4 (`tailscale ip -4`), or None."""
    if not shutil.which("tailscale"):
        return None
    try:
        r = run(["tailscale", "ip", "-4"], capture_output=True, text=True, timeout=4)
    except (OSError, subprocess.SubprocessError):
        return None
    for line in (r.stdout or "").split():
        if is_tailnet(line.strip()):
            return line.strip()
    return None


class LazyClient:
    """The TV client, made on the first tool call: initialize and tools/list work before pairing, and a factory that
    prints (\"TV not paired\") or exits never writes on the MCP stdout nor kills the server."""

    def __init__(self, factory):
        self._factory, self._c = factory, None

    def _get(self):
        if self._c is None:
            import contextlib
            try:
                with contextlib.redirect_stdout(sys.stderr):
                    self._c = self._factory()
            except SystemExit:
                raise RuntimeError("the TV is not paired: run omarchy-link pair (or omarchy-tv pair)") from None
        return self._c

    def request(self, *a, **kw):
        return self._get().request(*a, **kw)

    def __getattr__(self, name):
        if name.startswith("_"):
            raise AttributeError(name)
        return getattr(self._get(), name)


def serve_stdio(tv_client_factory, scope: str = "control", max_level: str = "alert") -> int:
    lazy = LazyClient(tv_client_factory)
    for line in sys.stdin:
        line = line.strip()
        if not line:
            continue
        try:
            req = json.loads(line)
        except ValueError:
            continue
        resp = handle_rpc(req, lazy, scope, max_level)
        if resp is not None:
            sys.stdout.write(json.dumps(resp) + "\n")
            sys.stdout.flush()
    return 0


def create_http_server(tv_client_factory, token: str, host: str = "127.0.0.1", port: int = 8990,
                       tailnet_only: bool = False, scope: str = "control", require_access: bool = False,
                       verifier=None, max_level: str = "alert") -> ThreadingHTTPServer:
    expected = f"Bearer {token}"

    class Handler(BaseHTTPRequestHandler):
        def log_message(self, *a):
            pass

        def do_POST(self):
            if tailnet_only and not is_tailnet(self.client_address[0]):
                self._send(403, {"error": "not_tailnet"})
                return
            if self.path not in ("/mcp", "/v1/mcp", "/"):
                self._send(404, {"error": "not_found"})
                return
            jwt = self.headers.get("Cf-Access-Jwt-Assertion", "")
            if verifier is not None:
                why = verifier.check(jwt) if jwt else "missing"
                if why:
                    self._send(403, {"error": "access_denied", "message": f"Cloudflare Access token: {why}"})
                    return
            if require_access and not self.headers.get("Cf-Access-Jwt-Assertion"):
                # v2.21 --cloudflare --access: the origin is 127.0.0.1, only cloudflared reaches it, and Cloudflare adds
                # this header only after Access let the caller in. No header: Access is not in front of this tunnel.
                self._send(403, {"error": "access_required", "message": "put Cloudflare Access in front of this tunnel"})
                return
            auth = self.headers.get("Authorization", "").strip()
            if not hmac.compare_digest(auth, expected):
                self._send(401, {"error": "unauthorized", "message": "missing or invalid Bearer token"})
                return
            length = int(self.headers.get("Content-Length", "0") or "0")
            if length <= 0 or length > 256 * 1024:
                self._send(400, {"error": "bad_request"})
                return
            try:
                req = json.loads(self.rfile.read(length).decode("utf-8"))
            except ValueError:
                self._send(400, {"error": "invalid_json"})
                return
            resp = handle_rpc(req, LazyClient(tv_client_factory), scope, max_level)
            if resp is None:   # a notification: accepted, no body (MCP streamable HTTP)
                self.send_response(202)
                self.send_header("Content-Length", "0")
                self.end_headers()
                return
            self._send(200, resp)

        def do_GET(self):   # no server-initiated stream here: JSON 405, not the stdlib's 501 HTML page
            self._send(405, {"error": "method_not_allowed", "message": "POST JSON-RPC to /mcp"}, allow="POST")

        do_DELETE = do_PUT = do_GET

        def _send(self, code: int, obj: dict, allow: str | None = None):
            data = json.dumps(obj).encode("utf-8")
            self.send_response(code)
            if allow:
                self.send_header("Allow", allow)
            self.send_header("Content-Type", "application/json")
            self.send_header("Cache-Control", "no-store")
            self.send_header("Content-Length", str(len(data)))
            self.end_headers()
            self.wfile.write(data)

    return ThreadingHTTPServer((host, port), Handler)


def tunnel_argv(cf: str, port: int, name: str | None) -> list:
    """Quick tunnel (a random trycloudflare.com URL) or, v2.21, a named tunnel on your own domain."""
    if name:
        return [cf, "tunnel", "--no-autoupdate", "run", "--url", f"http://127.0.0.1:{port}", name]
    return [cf, "tunnel", "--no-autoupdate", "--url", f"http://127.0.0.1:{port}"]


def default_scope(public: bool, scope: str | None) -> str:
    """Local and tailnet: control. Through the internet (a tunnel): notify unless asked."""
    if scope in SCOPES:
        return scope
    return "notify" if public else "control"


def serve_http(tv_client_factory, port: int = 8990, tunnel: bool = False, tailscale: bool = False,
               cloudflare: str | None = None, scope: str | None = None, access: bool = False,
               access_team: str | None = None, access_aud: str | None = None, max_level: str | None = None) -> int:
    token = ensure_token()
    bind = "127.0.0.1"
    if tailscale:
        bind = tailnet_ip()
        if not bind:
            print("tailscale: no tailnet address on this PC (sudo tailscale up)", file=sys.stderr, flush=True)
            return 1
        if tunnel or cloudflare:
            print("--tailscale: ignoring --tunnel/--cloudflare (the tailnet never needs a public tunnel)",
                  file=sys.stderr, flush=True)
        tunnel, cloudflare = False, None   # one or the other
    public = bool(tunnel or cloudflare)
    scope = default_scope(public, scope)
    max_level = default_max_level(public, max_level)
    verifier = None
    if cloudflare and (access_team or access_aud):
        from .cf_access import AccessVerifier
        try:
            import cryptography  # noqa: F401
        except ImportError:
            print("--access-team/--access-aud need python-cryptography (pacman -S python-cryptography)", file=sys.stderr)
            return 1
        verifier = AccessVerifier(access_team or "", access_aud or "")
    if cloudflare and not (access or verifier):
        print("warning: --cloudflare without Access: anyone with the URL and the bearer token reaches this server. "
              "Put a Cloudflare Access policy in front and add --access-team/--access-aud (docs/CLOUDFLARE.md).",
              file=sys.stderr, flush=True)
    srv = create_http_server(tv_client_factory, token, bind, port, tailnet_only=tailscale, scope=scope,
                             require_access=bool(cloudflare and (access or verifier)), verifier=verifier,
                             max_level=max_level)
    actual_port = srv.server_address[1]
    print(f"OmarchyTV MCP HTTP server listening on http://{bind}:{actual_port}/mcp"
          + (" (tailnet peers only)" if tailscale else "") + f", tools: {scope}"
          + (f", notifications at most {max_level}" if max_level != "alert" else ""), flush=True)
    print(f"Authorization: Bearer {token}", flush=True)
    proc = None
    if public:
        cf = shutil.which("cloudflared")
        if not cf:
            print("cloudflared not found on PATH; running local HTTP only.", file=sys.stderr, flush=True)
        else:
            proc = subprocess.Popen(tunnel_argv(cf, actual_port, cloudflare),
                                    stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True)
            if cloudflare:
                print(f"Cloudflare tunnel '{cloudflare}' -> this server. Its hostname is the one you routed to it "
                      "(cloudflared tunnel route dns). See docs/CLOUDFLARE.md." + (
                          " Access JWT verified (team, audience, signature)." if verifier else
                          " Access header required." if access else
                          " Tip: put Cloudflare Access in front and add --access-team/--access-aud."), flush=True)

            def _watch():
                for line in proc.stdout:
                    m = re.search(r"https://[a-zA-Z0-9.-]+\.trycloudflare\.com", line)
                    if m:
                        print(f"MCP tunnel URL: {m.group(0)}/mcp", flush=True)
            threading.Thread(target=_watch, daemon=True).start()
    try:
        srv.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        srv.shutdown()
        if proc:
            proc.terminate()
    return 0


# ---- v2.21 setup helpers: omarchy-tv cloudflare setup | service ----------------------------------------------------

def setup_commands(name: str, hostname: str) -> list:
    """The cloudflared commands that make a named tunnel and route a hostname to it (run once)."""
    if not re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9-]{0,62}", name or ""):
        raise ValueError("tunnel name: letters, digits and dashes")
    if not re.fullmatch(r"(?=.{4,253}$)([a-z0-9-]{1,63}\.)+[a-z]{2,63}", hostname or ""):
        raise ValueError("hostname: e.g. tv-mcp.example.com")
    return [["cloudflared", "tunnel", "create", name], ["cloudflared", "tunnel", "route", "dns", name, hostname]]


def service_unit(argv: list) -> str:
    """A systemd user unit that keeps `omarchy-tv mcp …` running (and restarts it)."""
    import shlex
    return ("[Unit]\nDescription=OmarchyTV MCP server\nAfter=network-online.target\nWants=network-online.target\n\n"
            "[Service]\nExecStart=" + " ".join(shlex.quote(a) for a in argv) + "\nRestart=on-failure\nRestartSec=10\n\n"
            "[Install]\nWantedBy=default.target\n")
