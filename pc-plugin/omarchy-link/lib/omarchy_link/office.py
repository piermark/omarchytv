"""GrokBot Office (OmarchyTV 2.20): Claude and ChatGPT bots that live on this PC.

The TV's office shows Grok Bot's bots (omarchy-tv office push), and with this module also assistants you hire
for Claude (Anthropic API) or ChatGPT (OpenAI API). A hired bot is an entry in assistant.json (mode 600):
name, provider type, model and its role as a system prompt. It then answers like every other PC assistant
(the TV's "ask" event, POST /v1/ai/ask on LG/Samsung). Keys stay here: env:ANTHROPIC_API_KEY / env:OPENAI_API_KEY
by default, or the key reference of an existing entry of the same type (env:, file: chmod 600, cmd:). Nothing
about a key ever goes to the TV or into a log.

Hiring checks the key with the provider first (GET /v1/models: free, no tokens). No key, a wrong key, no
network: the hire fails and says why. Nothing is saved, nothing is shown as hired.

Usage per provider comes from the Admin APIs (the same collectors as the AI pane: ai/providers/anthropic.py and
openai.py) when an admin key is set (ai.json entries, or env:ANTHROPIC_ADMIN_KEY / env:OPENAI_ADMIN_KEY). Without
one there is no number, and the TV shows no usage for that provider.
"""
from __future__ import annotations

import json
import os
import re
import time
from pathlib import Path

from . import ai, assistant, config

# office provider -> (assistant type, label, admin usage collector type, admin key env)
PROVIDERS = {
    "claude": ("anthropic_api", "Claude", "anthropic", "ANTHROPIC_ADMIN_KEY"),
    "chatgpt": ("openai_api", "ChatGPT", "openai", "OPENAI_ADMIN_KEY"),
}
ALIASES = {"anthropic": "claude", "claude-api": "claude", "openai": "chatgpt", "gpt": "chatgpt", "chatgpt-api": "chatgpt"}
MODELS_URL = {"anthropic_api": "{base}/v1/models", "openai_api": "{base}/models"}
NAME_RE = re.compile(r"[\x00-\x1f\x7f<>]")
COLORS = {"claude": "#ff9e64", "chatgpt": "#9ece6a"}
MAX_HIRED = 12


class HireError(Exception):
    def __init__(self, code: str, message: str):
        super().__init__(message)
        self.code = code


def provider_of(kind: str) -> str:
    """Assistant/collector type -> the office's provider tag (claude, chatgpt, grok, or the type itself)."""
    k = str(kind or "").lower()
    if k in ("anthropic_api", "claude_code", "anthropic"):
        return "claude"
    if k in ("openai_api", "codex", "openai"):
        return "chatgpt"
    if k.startswith("grok") or k == "xai_api" or k == "xai":
        return "grok"
    return re.sub(r"[^a-z0-9._-]+", "", k)[:16] or "grok"


def normalize(p) -> str:
    p = str(p or "").strip().lower()
    return ALIASES.get(p, p)


def _clean(s, n: int) -> str:
    return re.sub(r"\s+", " ", NAME_RE.sub("", "" if s is None else str(s))).strip()[:n]


def _slug(s: str) -> str:
    return re.sub(r"[^a-z0-9]+", "-", str(s or "").lower()).strip("-")[:24]


def _settings(path: Path | None) -> dict:
    return assistant.load_settings(path)


def hired(path: Path | None = None) -> list[dict]:
    """The assistant.json entries hired for the office."""
    return [p for p in _settings(path).get("providers", []) if isinstance(p, dict) and isinstance(p.get("office"), dict)]


def bots(path: Path | None = None) -> list[dict]:
    """Hired bots as office bots: {id, name, role, color, status, provider, assistant}. No secrets, no model keys."""
    out = []
    for p in hired(path):
        o = p["office"]
        prov = provider_of(p.get("type"))
        b = {"id": str(p.get("id", ""))[:40], "name": _clean(p.get("name"), 24), "status": "idle", "provider": prov,
             "assistant": str(p.get("id", ""))[:32], "color": o.get("color") or COLORS.get(prov, "")}
        if o.get("role"):
            b["role"] = _clean(o["role"], 40)
        if p.get("enabled") is False:
            b["status"] = "error"
        if b["name"]:
            out.append({k: v for k, v in b.items() if v})
    return out


def _key_ref(settings: dict, kind: str) -> str:
    for p in settings.get("providers", []):
        if isinstance(p, dict) and p.get("type") == kind and p.get("key") and not isinstance(p.get("office"), dict):
            return str(p["key"])
    return assistant.load_type(kind).DEFAULTS["key"]


def check_key(kind: str, cfg: dict, ctx: ai.Context) -> None:
    """GET the provider's model list: proves the key works without spending tokens. Raises HireError."""
    mod = assistant.load_type(kind)
    c = {**mod.DEFAULTS, **cfg}
    try:
        key = ctx.secret(c["key"], PROVIDERS_BY_TYPE[kind])
    except ai.ProviderError as e:
        raise HireError("no_key", f"no {PROVIDERS_BY_TYPE[kind]} API key on the PC ({str(e)[:80]})") from e
    url = MODELS_URL[kind].format(base=str(c["baseUrl"]).rstrip("/"))
    headers = {"x-api-key": key, "anthropic-version": "2023-06-01"} if kind == "anthropic_api" else {"Authorization": f"Bearer {key}"}
    try:
        ctx.http_json("GET", url, headers)
    except ai.ProviderError as e:
        raise HireError("key_rejected", f"{PROVIDERS_BY_TYPE[kind]} did not accept the key: {str(e)[:100]}") from e


PROVIDERS_BY_TYPE = {v[0]: v[1] for v in PROVIDERS.values()}


def hire(name, provider, role="", description="", model=None, path: Path | None = None, ctx: ai.Context | None = None) -> dict:
    """Saves a new Claude/ChatGPT office bot in assistant.json after checking the key. Returns its office bot."""
    path = path or assistant.ASSISTANT_FILE
    prov = normalize(provider)
    if prov not in PROVIDERS:
        raise HireError("bad_provider", "hire on the PC is for claude or chatgpt (Grok Bot hires through its webhook)")
    name = _clean(name, 24)
    if not name:
        raise HireError("bad_request", "the new bot needs a name")
    role, description = _clean(role, 40), _clean(description, 600)
    kind = PROVIDERS[prov][0]
    settings = _settings(path)
    entries = [p for p in settings.get("providers", []) if isinstance(p, dict)]
    if len([p for p in entries if isinstance(p.get("office"), dict)]) >= MAX_HIRED:
        raise HireError("full", f"at most {MAX_HIRED} hired bots on the PC: fire one first (omarchy-link office fire)")
    pid = "office-" + (_slug(name) or "bot")
    if any(str(p.get("id")) == pid for p in entries):
        raise HireError("exists", f"there is already a bot called {name}")
    cfg = {"type": kind, "id": pid, "name": name, "key": _key_ref(settings, kind)}
    if model:
        cfg["model"] = _clean(model, 64)
    private = path.exists() and config.is_private_file(path)
    check_key(kind, cfg, ctx or ai.Context(ai_file_private=private, timeout=15.0))
    system = f"You are {name}, a bot in the user's GrokBot Office" + (f". Your role: {role}" if role else "") + "."
    if description and description != role:
        system += " " + description
    cfg["system"] = system[:1500]
    cfg["office"] = {"role": role, "color": COLORS[prov], "hiredAt": int(time.time())}
    settings["providers"] = entries + [cfg]
    config.write_json_private(path, settings)
    return next(b for b in bots(path) if b["id"] == pid)


def fire(name, path: Path | None = None) -> bool:
    path = path or assistant.ASSISTANT_FILE
    settings = _settings(path)
    entries = [p for p in settings.get("providers", []) if isinstance(p, dict)]
    want = str(name or "").strip().lower()
    keep = [p for p in entries if not (isinstance(p.get("office"), dict)
                                       and want in (str(p.get("name", "")).lower(), str(p.get("id", "")).lower()))]
    if len(keep) == len(entries):
        return False
    settings["providers"] = keep
    config.write_json_private(path, settings)
    return True


def usage(ai_file: Path | None = None) -> list[dict]:
    """Real spend per provider from the Admin APIs, only where an admin key is set. Never a guess.
    [{provider, label, spent, unit, budget?, pct?, period: "month", resets?}]"""
    ai_file = ai_file or config.AI_FILE
    try:
        doc = config.read_json(ai_file, {"providers": []})
    except ValueError:
        doc = {"providers": []}
    want = []
    listed = {p.get("type") for p in doc.get("providers", []) if isinstance(p, dict)}
    for p in doc.get("providers", []):
        if isinstance(p, dict) and p.get("type") in ("anthropic", "openai") and p.get("enabled") is not False:
            want.append(p)
    for prov, (_, label, kind, env) in PROVIDERS.items():
        if kind not in listed and os.environ.get(env):
            want.append({"type": kind, "key": "env:" + env, "name": label + " API"})
    if not want:
        return []
    out = []
    for e in ai.collect({"providers": want, "autoDetect": False, "mirror": False}, ai_file).get("ai", []):
        if e.get("status") != "ok" or e.get("used") is None:
            continue   # no admin key, wrong key, offline: no number, no card
        u = {"provider": provider_of(e.get("provider")), "label": _clean(e.get("name"), 24), "spent": round(float(e["used"]), 2),
             "unit": e.get("unit") or "$", "period": "month"}
        if e.get("limit"):
            u["budget"] = round(float(e["limit"]), 2)
            u["pct"] = max(0.0, min(100.0, round(float(e["used"]) / float(e["limit"]) * 100, 1)))
        if e.get("resetsAt"):
            u["resets"] = str(e["resetsAt"])[:32]
        out.append(u)
    return out


def tv_file() -> Path:
    """What omarchy-tv office push saved (read only here)."""
    base = os.environ.get("XDG_CONFIG_HOME") or os.path.expanduser("~/.config")
    return Path(base) / "omarchy-tv" / "office.json"


def merge(base: dict | None, path: Path | None = None, ai_file: Path | None = None, with_usage: bool = True) -> dict:
    """The office JSON for the TV: the pushed Grok Bot bots, then the hired Claude/ChatGPT bots, and the
    per-provider usage. Grok bots without a provider are "grok"."""
    d = dict(base or {})
    out_bots, seen = [], set()
    for b in list(d.get("bots") or []):
        if isinstance(b, dict):
            b = dict(b)
            b.setdefault("provider", "grok")
            seen.add(str(b.get("id") or b.get("name", "")).lower())
            out_bots.append(b)
    for b in bots(path):
        if b["id"].lower() not in seen and len(out_bots) < 24:
            out_bots.append(b)
    d["bots"] = out_bots
    if with_usage:
        u = usage(ai_file)
        if u:
            d["providers_usage"] = u
    return d


def tv_payload(path: Path | None = None, ai_file: Path | None = None) -> dict:
    """Full payload from this side alone (used after a hire from the Android TV)."""
    try:
        base = json.loads(tv_file().read_text(encoding="utf-8"))
    except (OSError, ValueError):
        base = {}
    if not isinstance(base, dict):
        base = {}
    return merge({k: base[k] for k in ("bots", "usage", "title", "updated_at") if k in base}, path, ai_file)
