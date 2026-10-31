"""Bridge to @tcballard's Grok Bot Desktop Companion (experimental).

Built against his v0.1.0 release (tag v0.1.0, commit a2efb00) of
https://github.com/tcballard/omarchy-plugin-grokbot-desktop (MIT, (c) 2026 Tom Ballard).
The palette values below come from his qml/palette.js. None of his QML or Python is
copied here. I only use what his README documents:

* the IPC target ``omarchy-shell io.github.tcballard.grokbot-desktop <method> [args]``
  (``ping``, ``bot <name>``, ``ask <text>``, ``say <title> <text>``);
* his settings entry in ``~/.config/omarchy/shell.json`` (``plugins[]`` with his id:
  ``botId``, ``syncAppearance``, ``shape``, ``color``);
* his bot roster ``~/.config/omarchy/grokbot-bots.json``, else the ``data/bots.json`` of the checkout;
* his ask helper ``bin/grokbot-ask`` (one JSON object on stdin, the reply on stdout; exit 10 means
  a webhook routine took the question and answers later through his ``reply`` command);
* his dispatch settings in ``~/.config/omarchy/grokbot-desktop.json`` (``dispatch``: webhook | agent);
* his roster sync ``bin/grokbot-sync`` (refreshes ``grokbot-bots.json`` from the Grok Bot app).

His IPC has no getter for the reply: ``ask`` only answers ok/busy/empty/error and the reply
shows in his desktop bubble. To bring the reply to the TV I run his helper myself (the same
way his shell does: trusted python3 -I, a cleared environment, the JSON on stdin) and then
mirror the reply in his bubble with ``say``. The webhook key is read by his helper, never here.
"""

from __future__ import annotations

import json
import os
import re
import shutil
import socket
import stat
import subprocess
from pathlib import Path

COMPANION_ID = "io.github.tcballard.grokbot-desktop"
UPSTREAM = "https://github.com/tcballard/omarchy-plugin-grokbot-desktop"
LABEL = "@tcballard's Grok Bot Desktop Companion v0.1.0"

# The bridge targets his v0.1.0 release (manifest.json "version" / VERSION file). A later 0.x is still
# tried with the same contract; OMARCHY_GROKBOT_DESKTOP_REF can point the reference elsewhere.
TARGET_VERSION = "0.1.0"
UPSTREAM_TAG = "v" + TARGET_VERSION
UPSTREAM_COMMIT = "a2efb00d31b6ac698f9ba7578f4d298faa6434be"
UPSTREAM_REF = os.environ.get("OMARCHY_GROKBOT_DESKTOP_REF") or UPSTREAM_TAG
EXIT_SENT = 10            # his grokbot-ask: the webhook accepted the question, the answer follows via `reply`
VERSION_RE = re.compile(r"^v?(\d+)\.(\d+)\.(\d+)(?:[-+].*)?$")

# Bob is the companion's documented default bot (README: id, teardrop, orange).
DEFAULT_BOT = {"id": "e0195a7d-f804-4688-9191-e2cf3574a6d1", "name": "Bob", "shortName": "Bob",
               "avatarShape": "teardrop", "avatarColor": "orange", "isPrimary": True}

# Fill colours of his palette names (v0.1.0 qml/palette.js, MIT, Tom Ballard: Grok Bot's own values).
# Black is drawn white on a dark surface there, and the TV is always dark. "omarchy" means "the active
# theme accent" over there; the TV gets None and uses its own accent.
PALETTE = {"black": "#FFFFFF", "brown": "#936439", "red": "#FF263C", "orange": "#FF6700",
           "yellow": "#FF9800", "green": "#00C972", "cyan": "#00BCA6", "blue": "#1084FE",
           "violet": "#9159FE", "magenta": "#FF309B", "gray": "#777777", "omarchy": None}
COLOR_ALIASES = {"purple": "violet", "grey": "gray", "teal": "cyan", "pink": "magenta", "amber": "yellow"}
# His 14 v0.1.0 shape names (Grok Bot's avatar picker, qml/shapes.js), sent to the TV as "avatarShape"
# so a TV that vendors his shapes draws the exact outline; "shape" stays the older contour family.
AVATAR_SHAPES = ("blob", "cloud", "squircle", "sparkle", "clover", "heart", "flower", "teardrop", "tablet",
                 "wedge", "gem", "burst", "hex", "pebble")
AVATAR_ALIASES = {"circle": "blob", "dome": "blob", "crescent": "blob", "pumpkin": "pebble", "drop": "teardrop",
                  "egg": "teardrop", "bean": "heart", "leaf": "heart", "sprout": "clover", "pill": "tablet",
                  "capsule": "tablet", "cylinder": "tablet", "peanut": "tablet", "triangle": "wedge", "bolt": "wedge",
                  "pentagon": "gem", "shield": "gem", "crystal": "gem", "hexagon": "hex", "heptagon": "hex",
                  "octagon": "hex", "square": "squircle", "diamond": "squircle", "arch": "squircle",
                  "ghost": "squircle", "spark": "sparkle", "star": "burst", "group": "cloud"}
URL_RE = re.compile(r"https?://[^\s<>\"')\]]{3,400}")

# Official avatarShape strings -> the contour name his shapes use (aliases from qml/shapes.js).
SHAPE_ALIASES = {"blob": "bean", "teardrop": "drop", "hex": "hexagon", "tablet": "pill", "wedge": "bolt",
                 "squircle": "square", "sparkle": "spark", "leaf": "sprout", "egg": "drop", "capsule": "pill",
                 "cylinder": "pill", "gem": "diamond", "crystal": "diamond", "dome": "circle", "arch": "ghost",
                 "house": "shield"}
SHAPES = {"circle", "square", "triangle", "diamond", "pentagon", "hexagon", "octagon", "heptagon", "pill",
          "cloud", "heart", "crescent", "star", "spark", "burst", "flower", "clover", "sprout", "drop", "bean",
          "peanut", "pebble", "shield", "ghost", "bolt"}

CONTROLS = re.compile(r"[\x00-\x08\x0b\x0c\x0e-\x1f\x7f]")
ASK_LIMIT = 1000          # his IPC accepts 4000; the TV keyboard never needs more than this
REPLY_LIMIT = 4000        # his bubble shows at most 4000 chars
SAY_LIMIT = 300           # his `say` text cap
ASK_TIMEOUT = 180         # same ceiling his shell uses for the helper
TRUSTED = ("/usr/bin", "/bin", "/usr/local/bin")
# Environment names the helper may need (agent mode credentials, proxies). Values are passed
# through to his helper as his shell does; they are never read, logged or sent to the TV.
PASS_ENV = ("HOME", "USER", "LOGNAME", "LANG", "LC_ALL", "LC_CTYPE", "TZ", "XDG_CONFIG_HOME", "XDG_DATA_HOME",
            "XDG_CACHE_HOME", "XDG_STATE_HOME", "XDG_RUNTIME_DIR", "TMPDIR", "SSL_CERT_FILE", "SSL_CERT_DIR",
            "HTTP_PROXY", "HTTPS_PROXY", "NO_PROXY", "http_proxy", "https_proxy", "no_proxy",
            "MISE_DATA_DIR", "MISE_SHIMS_DIR", "ANTHROPIC_API_KEY", "ANTHROPIC_AUTH_TOKEN",
            "CLAUDE_CODE_OAUTH_TOKEN", "OPENAI_API_KEY", "OPENAI_BASE_URL", "GEMINI_API_KEY", "GOOGLE_API_KEY",
            "GITHUB_TOKEN", "GH_TOKEN", "XAI_API_KEY", "GROK_API_KEY", "GROKBOT_WEBHOOK_KEY")


class GrokbotError(Exception):
    def __init__(self, code: str, message: str):
        super().__init__(message)
        self.code = code


def plain(value, limit: int) -> str:
    return " ".join(CONTROLS.sub(" ", str(value or "")).split())[:limit]


def omarchy_dir() -> Path:
    base = os.environ.get("XDG_CONFIG_HOME") or os.path.join(os.path.expanduser("~"), ".config")
    return Path(base) / "omarchy"


def _read_json(path: Path, limit: int = 256 * 1024):
    try:
        st = path.lstat()
        if stat.S_ISLNK(st.st_mode) or not stat.S_ISREG(st.st_mode) or st.st_size > limit:
            return None
        return json.loads(path.read_text(encoding="utf-8"))
    except (OSError, ValueError):
        return None


def plugin_entry() -> dict | None:
    data = _read_json(omarchy_dir() / "shell.json")
    plugins = data.get("plugins") if isinstance(data, dict) else None
    for entry in plugins if isinstance(plugins, list) else []:
        if isinstance(entry, dict) and entry.get("id") == COMPANION_ID:
            return entry
    return None


def checkout_candidates(cfg: dict) -> list[Path]:
    """Where `omarchy plugin add` may have put the checkout. His README does not pin the path,
    so the user can set "grokbotDesktopDir" in config.json (or OMARCHY_GROKBOT_DESKTOP_DIR)."""
    out = []
    for v in (cfg.get("grokbotDesktopDir"), os.environ.get("OMARCHY_GROKBOT_DESKTOP_DIR")):
        if v:
            out.append(Path(os.path.expanduser(str(v))))
    home = Path(os.path.expanduser("~"))
    data = Path(os.environ.get("XDG_DATA_HOME") or home / ".local/share")
    for root in (data / "omarchy/plugins", data / "omarchy-shell/plugins", omarchy_dir() / "plugins",
                 data / "omarchy/shell/plugins"):
        out += [root / COMPANION_ID, root / "grokbot-desktop", root / "omarchy-plugin-grokbot-desktop"]
    return out


def _owned_safe(p: Path) -> bool:
    """A file I may execute: regular, owned by me (or root) and not writable by group/others."""
    try:
        st = p.stat()
    except OSError:
        return False
    return stat.S_ISREG(st.st_mode) and st.st_uid in (os.getuid(), 0) and not st.st_mode & 0o022


def checkout_dir(cfg: dict) -> Path | None:
    for d in checkout_candidates(cfg):
        if (d / "manifest.json").is_file() and (d / "bin/grokbot-ask").is_file():
            m = _read_json(d / "manifest.json", 64 * 1024)
            if isinstance(m, dict) and m.get("id") == COMPANION_ID:
                return d
    return None


def companion_version(d: Path | None) -> str | None:
    """His version: manifest.json "version", else the VERSION file (both say 0.1.0 on the branch)."""
    if not d:
        return None
    m = _read_json(d / "manifest.json", 64 * 1024)
    v = m.get("version") if isinstance(m, dict) else None
    if not v:
        try:
            f = d / "VERSION"
            if f.is_file() and not f.is_symlink() and f.stat().st_size < 64:
                v = f.read_text(encoding="utf-8").strip()
        except OSError:
            v = None
    v = plain(v, 32)
    return v if VERSION_RE.match(v) else None


def compatibility(version: str | None) -> str:
    """"target" = v0.1.0 (the release or the branch reporting it); "branch" = no/older version
    (the in-development branch before VERSION existed); "newer" = a later 0.x, still tried with
    the v0.1.0 contract; "unsupported" = a new major, where the documented IPC may have changed."""
    if not version:
        return "branch"
    want = tuple(int(x) for x in VERSION_RE.match(TARGET_VERSION).groups())
    got = tuple(int(x) for x in VERSION_RE.match(version).groups())
    if got[:2] == want[:2]:
        return "target"
    if got < want:
        return "branch"
    return "newer" if got[0] == want[0] else "unsupported"


def shell_bin() -> str | None:
    return shutil.which("omarchy-shell")


def roster(cfg: dict) -> list[dict]:
    """His roster file, else the bundled data/bots.json of the checkout, else just Bob."""
    sources = [omarchy_dir() / "grokbot-bots.json"]
    d = checkout_dir(cfg)
    if d:
        sources.append(d / "data/bots.json")
    for src in sources:
        data = _read_json(src)
        bots = data.get("bots") if isinstance(data, dict) else None
        good = [b for b in bots if isinstance(b, dict) and b.get("id") and b.get("name")] if isinstance(bots, list) else []
        if good:
            return good
    return [DEFAULT_BOT]


def shape_name(value) -> str:
    v = str(value or "").strip().lower()
    v = SHAPE_ALIASES.get(v, v)
    return v if v in SHAPES else "bean"


def color_name(value) -> str:
    v = str(value or "").strip().lower()
    v = COLOR_ALIASES.get(v, v)
    return v if v in PALETTE else "orange"


def avatar_shape(value) -> str:
    """His canonical v0.1.0 shape name (aliases resolved as qml/shapes.js does; unknown -> blob)."""
    v = str(value or "").strip().lower()
    v = AVATAR_ALIASES.get(v, v)
    return v if v in AVATAR_SHAPES else "blob"


def desktop_settings() -> dict:
    """His ~/.config/omarchy/grokbot-desktop.json (written by bin/grokbot-connect), with the same keys on
    his shell.json entry winning, as his README says. Only non-secret keys are read; the key files never."""
    data = _read_json(omarchy_dir() / "grokbot-desktop.json", 64 * 1024)
    out = dict(data) if isinstance(data, dict) else {}
    entry = plugin_entry() or {}
    for k in ("dispatch", "webhookUrl"):
        if k in entry:
            out[k] = entry[k]
    return out


def dispatch_mode() -> str:
    """"webhook" (Grok Bot routine) or "agent" (local coding agent), his default being the agent."""
    return "webhook" if str(desktop_settings().get("dispatch", "")).lower() == "webhook" else "agent"


def links(text: str, limit: int = 4) -> list[dict]:
    """The http(s) links in a reply, like his sayLink buttons: [{label, url}], at most `limit`."""
    out, seen = [], set()
    for m in URL_RE.finditer(str(text or "")):
        url = m.group(0).rstrip(".,;:!?")
        if url in seen:
            continue
        seen.add(url)
        host = re.sub(r"^https?://(www\.)?", "", url).split("/")[0][:40]
        item = {"label": host or "link", "url": url, "kind": "url"}
        try:   # "answers and plays the video": the TV offers YouTube/video links in Cinemarchy (OK to play)
            from . import cast
            c = cast.classify(url)
            if c.get("kind") in ("youtube", "video", "audio"):
                item["kind"] = c["kind"]
                if c.get("videoId"):
                    item["videoId"] = c["videoId"]
                if c.get("position"):
                    item["position"] = c["position"]
        except Exception:
            pass
        out.append(item)
        if len(out) >= limit:
            break
    return out


def sync_roster(cfg: dict, timeout: float = 30.0) -> dict:
    """Run his bin/grokbot-sync (the same trusted python3 -I and cleared environment as the ask helper), which
    refreshes ~/.config/omarchy/grokbot-bots.json from the Grok Bot desktop app's local cache."""
    d = checkout_dir(cfg)
    helper = d / "bin/grokbot-sync" if d else None
    if not helper or not _owned_safe(helper):
        raise GrokbotError("not_installed", "his bin/grokbot-sync was not found")
    try:
        r = subprocess.run(_helper_argv(helper), capture_output=True, text=True, timeout=timeout,
                           env=_helper_env(), cwd=str(helper.parent), stdin=subprocess.DEVNULL)
    except subprocess.TimeoutExpired as e:
        raise GrokbotError("timeout", "roster sync timed out") from e
    except OSError as e:
        raise GrokbotError("helper_failed", str(e)[:160]) from e
    if r.returncode != 0:
        raise GrokbotError("sync_failed", plain(r.stderr, 200) or "roster sync failed")
    return {"bots": len(roster(cfg)), "message": plain(r.stdout, 200)}


def detect(cfg: dict) -> dict:
    """Is his companion here? Installed = his shell.json entry or a checkout exists."""
    entry = plugin_entry()
    d = checkout_dir(cfg)
    sh = shell_bin()
    ver = companion_version(d)
    compat = compatibility(ver)
    return {"installed": bool(entry is not None or d), "enabled": bool(entry and entry.get("enabled", True) is not False),
            "ipc": bool(sh), "relay": bool(d and _owned_safe(d / "bin/grokbot-ask")) and compat != "unsupported",
            "checkout": str(d) if d else None, "version": ver, "compat": compat, "target": TARGET_VERSION,
            "dispatch": dispatch_mode()}


def current(cfg: dict) -> dict | None:
    """What the companion shows now, as far as its saved settings tell (None when not installed).
    With syncAppearance off his mark uses the custom shape/colour instead of the bot's."""
    info = detect(cfg)
    if not info["installed"]:
        return None
    entry = plugin_entry() or {}
    bots = roster(cfg)
    want = str(entry.get("botId") or DEFAULT_BOT["id"])
    bot = next((b for b in bots if b.get("id") == want), None) or \
        next((b for b in bots if b.get("isPrimary")), None) or bots[0]
    if entry.get("syncAppearance", True) is False:
        shape, color = entry.get("shape", "teardrop"), entry.get("color", "orange")
    else:
        shape, color = bot.get("avatarShape"), bot.get("avatarColor")
    color = color_name(color)
    return {"id": plain(bot.get("id"), 80), "name": plain(bot.get("name"), 40),
            "shortName": plain(bot.get("shortName") or bot.get("name"), 24),
            "shape": shape_name(shape), "avatarShape": avatar_shape(shape), "color": color, "fill": PALETTE[color],
            "mode": "relay" if info["relay"] else ("ipc" if info["ipc"] else "offline"),
            "source": "tcballard/grokbot-desktop", "label": LABEL,
            "version": info["version"], "compat": info["compat"], "dispatch": info["dispatch"]}


def find_bot(cfg: dict, query) -> dict | None:
    q = plain(query, 80).lower()
    if not q:
        return None
    bots = roster(cfg)
    for b in bots:
        if str(b.get("id", "")).lower() == q or str(b.get("name", "")).lower() == q or \
                str(b.get("shortName", "")).lower() == q:
            return b
    return None


def ipc(method: str, *args: str, timeout: float = 5.0) -> str:
    """One documented IPC call; argv list, no shell. Returns his reply line ('' when unreachable)."""
    sh = shell_bin()
    if not sh:
        raise GrokbotError("no_ipc", "omarchy-shell is not on PATH")
    try:
        r = subprocess.run([sh, COMPANION_ID, method, *args], capture_output=True, text=True, timeout=timeout,
                           stdin=subprocess.DEVNULL)
    except (OSError, subprocess.SubprocessError) as e:
        raise GrokbotError("ipc_failed", f"IPC {method}: {e}") from e
    return plain(r.stdout, 200)


def _helper_argv(helper: Path) -> list[str]:
    py = next((f"{d}/python3" for d in TRUSTED if os.access(f"{d}/python3", os.X_OK)), None)
    if not py:
        raise GrokbotError("no_python", "no trusted python3")
    sp = next((f"{d}/setpriv" for d in TRUSTED if os.access(f"{d}/setpriv", os.X_OK)), None)
    return ([sp, "--pdeathsig", "TERM"] if sp else []) + [py, "-I", str(helper)]


def _helper_env() -> dict:
    env = {"PATH": "/usr/bin:/bin", "PYTHONNOUSERSITE": "1", "PYTHONSAFEPATH": "1", "PYTHONDONTWRITEBYTECODE": "1"}
    env.update({k: os.environ[k] for k in PASS_ENV if k in os.environ})
    return env


def ask(cfg: dict, text, bot_query=None) -> dict:
    """TV -> companion. Returns {"bot", "text", "mode"}; raises GrokbotError.

    relay: his bin/grokbot-ask answers on stdout -> the reply goes to the TV and is mirrored in
           his desktop bubble with `say` (best effort).
    ipc:   no checkout found -> `ask` over his IPC; the reply only appears on the desktop.
    """
    prompt = plain(text, ASK_LIMIT)
    if not prompt:
        raise GrokbotError("empty", "empty question")
    info = detect(cfg)
    if not info["installed"]:
        raise GrokbotError("not_installed", "the Grok Bot Desktop Companion is not installed on this PC")
    bot = find_bot(cfg, bot_query) if bot_query else None
    if bot_query and not bot:
        raise GrokbotError("unknown_bot", "unknown bot")
    if bot and info["ipc"]:
        try:
            ipc("bot", str(bot["id"]))      # switch the desktop companion too (documented `bot`)
        except GrokbotError:
            pass
    cur = current(cfg) or {}
    if bot:
        cur = {**cur, "id": plain(bot["id"], 80), "name": plain(bot["name"], 40),
               "shortName": plain(bot.get("shortName") or bot["name"], 24)}
    if info["relay"]:
        helper = Path(info["checkout"]) / "bin/grokbot-ask"
        payload = json.dumps({"botId": cur.get("id") or DEFAULT_BOT["id"], "botName": cur.get("shortName") or "Bob",
                              "prompt": prompt, "host": plain(socket.gethostname(), 63)})
        try:
            r = subprocess.run(_helper_argv(helper), input=payload, capture_output=True, text=True,
                               timeout=int(cfg.get("grokbotTimeoutS", ASK_TIMEOUT)), env=_helper_env(),
                               cwd=str(helper.parent))
        except subprocess.TimeoutExpired as e:
            raise GrokbotError("timeout", "the bot did not answer in time") from e
        except OSError as e:
            raise GrokbotError("helper_failed", str(e)[:160]) from e
        who = cur.get("shortName") or "Bob"
        if r.returncode == EXIT_SENT:
            # Webhook routine took it; the answer arrives later in his desktop bubble through `reply`.
            return {"bot": who, "text": "", "mode": "sent", "links": [],
                    "note": plain(r.stdout, 200) or f"Sent to {who}."}
        reply = plain(r.stdout, REPLY_LIMIT) if r.returncode == 0 else ""
        if not reply:
            raise GrokbotError("ask_failed", plain(r.stderr, 200) or "the ask didn't return a reply")
        found = links(reply)
        if info["ipc"]:
            try:
                if found:   # his v0.1.0 sayLink: the bubble gets a button for the first link
                    ipc("sayLink", who, reply[:SAY_LIMIT], found[0]["label"], found[0]["url"])
                else:
                    ipc("say", who, reply[:SAY_LIMIT])
            except GrokbotError:
                pass
        return {"bot": who, "text": reply, "mode": "relay", "links": found}
    if info["ipc"]:
        res = ipc("ask", prompt)
        if res == "ok":
            return {"bot": cur.get("shortName") or "Bob", "text": "", "mode": "ipc"}
        raise GrokbotError(res or "ipc_failed", {"busy": "the companion is busy with another question",
                                                  "empty": "empty question"}.get(res, "the companion did not accept the question"))
    raise GrokbotError("no_ipc", "omarchy-shell is not on PATH and no companion checkout was found")


def state_signature(cfg: dict):
    """mtimes of the files current() reads; the ticker pushes again when this changes."""
    sig = []
    for p in (omarchy_dir() / "shell.json", omarchy_dir() / "grokbot-bots.json", omarchy_dir() / "grokbot-desktop.json"):
        try:
            sig.append(p.stat().st_mtime_ns)
        except OSError:
            sig.append(None)
    sig.append(str(checkout_dir(cfg)))
    return tuple(sig)
