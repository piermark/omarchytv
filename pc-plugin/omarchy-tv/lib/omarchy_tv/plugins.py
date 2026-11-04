"""omarchy-tv plugins: a small, sandboxed plugin engine for TV media feeds & companions (2.13).

A plugin is declarative JSON data, never executable code. The engine:
- fetches feeds over HTTPS only, with a 10 s timeout and a 16 MiB cap, and never follows redirects to http,
- accepts only https:// media URLs, and strips control characters from titles,
- pushes declarative plugin state to the TV via LinkServer (/v1/plugins) with zero access to LocalAdb or shell.

Built-in MIT catalog:
- com.sighmon.spacex-tv ("SpaceX TV" by sighmon, https://github.com/sighmon/omarchy-spacex-tv, MIT; fork tcballard/omarchy-spacex-tv)
- io.github.tcballard.grokbot-desktop ("Agent Desktop", formerly Grok Bot Desktop Companion, by tcballard;
  https://github.com/tcballard/omarchy-plugin-grokbot-desktop, MIT)
"""
import json, os, re, shutil, urllib.request

MAX_BYTES = 16 * 1024 * 1024
TIMEOUT = 10
ID_RE = re.compile(r"^[a-z0-9][a-z0-9._-]{0,63}$")
CTRL_RE = re.compile(r"[\x00-\x1f\x7f]+")

BUILTIN = {
    "com.sighmon.spacex-tv": {
        "id": "com.sighmon.spacex-tv",
        "alias": "spacex-tv",
        "name": "SpaceX TV",
        "author": "sighmon",
        "license": "MIT",
        "kind": "media-feed",
        "source": "https://github.com/sighmon/omarchy-spacex-tv",
        "feed": {"url": "https://www.sighmon.com/spacex-tv/x-cache.json", "format": "spacex-tv"},
    },
    "io.github.tcballard.grokbot-desktop": {
        "id": "io.github.tcballard.grokbot-desktop",
        "alias": "grokbot-desktop",
        "name": "Agent Desktop",
        "author": "tcballard",
        "license": "MIT",
        "kind": "companion",
        "source": "https://github.com/tcballard/omarchy-plugin-grokbot-desktop",
        "companion": {"eyes": "capsule", "themeAdaptive": True, "speechBubble": True},
    },
}

ALIASES = {
    "spacex-tv": "com.sighmon.spacex-tv",
    "com.sighmon.spacex-tv": "com.sighmon.spacex-tv",
    "grokbot-desktop": "io.github.tcballard.grokbot-desktop",
    "grokbot": "io.github.tcballard.grokbot-desktop",
    "io.github.tcballard.grokbot-desktop": "io.github.tcballard.grokbot-desktop",
}


def user_dir() -> str:
    base = os.environ.get("XDG_CONFIG_HOME") or os.path.expanduser("~/.config")
    return os.path.join(base, "omarchy-tv", "plugins")


def state_file() -> str:
    base = os.environ.get("XDG_CONFIG_HOME") or os.path.expanduser("~/.config")
    return os.path.join(base, "omarchy-tv", "plugins-state.json")


def resolve_id(pid: str) -> str:
    return ALIASES.get(pid, pid)


def load_enabled() -> dict[str, bool]:
    defaults = {k: True for k in BUILTIN}
    sf = state_file()
    if os.path.isfile(sf):
        try:
            with open(sf, encoding="utf-8") as fh:
                data = json.load(fh)
            if isinstance(data, dict):
                for k, v in data.items():
                    defaults[resolve_id(k)] = bool(v)
        except (OSError, ValueError):
            pass
    return defaults


def save_enabled(st: dict[str, bool]) -> None:
    sf = state_file()
    os.makedirs(os.path.dirname(sf), exist_ok=True)
    with open(sf, "w", encoding="utf-8") as fh:
        json.dump(st, fh, indent=2)


def clean_title(s, limit=120) -> str:
    s = CTRL_RE.sub(" ", str(s or "")).strip()
    s = re.sub(r"https?://\S+", "", s).strip()
    return (s[: limit - 1] + "…") if len(s) > limit else s


def safe_url(u) -> str | None:
    return u if isinstance(u, str) and u.startswith("https://") and len(u) < 2048 and not CTRL_RE.search(u) else None


def validate_manifest(m: dict) -> dict:
    if not isinstance(m, dict) or not ID_RE.match(str(m.get("id", ""))):
        raise ValueError("plugin id must match [a-z0-9._-]")
    kind = m.get("kind", "media-feed")
    if kind != "companion":
        feed = m.get("feed") or {}
        if not safe_url(feed.get("url")):
            raise ValueError(f"{m['id']}: feed url must be https://")
        if feed.get("format") not in ("media-list", "spacex-tv"):
            raise ValueError(f"{m['id']}: unknown feed format")
    for k in ("name", "author", "license"):
        if not m.get(k):
            raise ValueError(f"{m['id']}: missing '{k}' (credits are required)")
    return m


def load_all() -> dict:
    out = dict(BUILTIN)
    # Also index by short alias for convenience in lookup while keeping canonical keys
    d = user_dir()
    if os.path.isdir(d):
        for f in sorted(os.listdir(d)):
            if f.endswith(".json"):
                try:
                    with open(os.path.join(d, f), encoding="utf-8") as fh:
                        m = validate_manifest(json.load(fh))
                    if m.get("feed", {}).get("format") == "media-list" and m["id"] not in BUILTIN:
                        out[m["id"]] = m
                except (OSError, ValueError) as e:
                    print(f"plugin {f} ignored: {e}")
    return out


class _HttpsOnly(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        if not newurl.startswith("https://"):
            raise ValueError("redirect to non-https refused")
        return super().redirect_request(req, fp, code, msg, headers, newurl)


def fetch_json(url: str):
    if not safe_url(url):
        raise ValueError("https only")
    opener = urllib.request.build_opener(_HttpsOnly)
    req = urllib.request.Request(url, headers={"User-Agent": "omarchy-tv-plugins/2.13"})
    with opener.open(req, timeout=TIMEOUT) as r:
        data = r.read(MAX_BYTES + 1)
    if len(data) > MAX_BYTES:
        raise ValueError("feed larger than 16 MiB")
    return json.loads(data)


def items_media_list(feed) -> list[dict]:
    out = []
    for it in (feed.get("items") if isinstance(feed, dict) else None) or []:
        if isinstance(it, dict) and safe_url(it.get("url")):
            out.append({"title": clean_title(it.get("title")) or "Untitled", "url": it["url"]})
    return out[:500]


def items_spacex(feed) -> list[dict]:
    out = []
    if not isinstance(feed, dict):
        return out
    for pl in ("starship_playlist", "starship_flight_tests_playlist"):
        for m in ((feed.get(pl) or {}).get("media") or []):
            u = safe_url(m.get("fhdLink")) or safe_url(m.get("hdLink"))
            if u:
                out.append({"title": clean_title(m.get("title")) or "Starship", "url": u})
    entries = ((feed.get("processed_cards") or {}).get("entries") or {})
    for e in entries.values():
        if not isinstance(e, dict) or e.get("contentKind") != "video":
            continue
        u = safe_url(e.get("streamURL"))
        if not u:
            continue
        fp = str(e.get("fingerprint") or "")
        t = fp.split("|t:", 1)[1].split("|at:", 1)[0] if "|t:" in fp else "SpaceX video"
        out.append({"title": clean_title(t.split("\n", 1)[0]), "url": u})
    return out[:500]


def items(m: dict, fetch=fetch_json) -> list[dict]:
    feed = fetch(m["feed"]["url"])
    return items_spacex(feed) if m["feed"]["format"] == "spacex-tv" else items_media_list(feed)


def declarative_payload(plugins: dict | None = None, enabled: dict[str, bool] | None = None) -> dict:
    plugins = plugins if plugins is not None else load_all()
    enabled = enabled if enabled is not None else load_enabled()
    arr = []
    for pid, m in plugins.items():
        arr.append({
            "id": m["id"],
            "name": m["name"],
            "author": m["author"],
            "license": m["license"],
            "kind": m.get("kind", "media-feed"),
            "enabled": bool(enabled.get(pid, True)),
            "source": m.get("source", ""),
        })
    return {
        "plugins": arr,
        "spacexTv": bool(enabled.get("com.sighmon.spacex-tv", True)),
        "grokbot": bool(enabled.get("io.github.tcballard.grokbot-desktop", True)),
    }


USAGE = "omarchy-tv plugins list | enable <ID> | disable <ID> | add <FILE> | remove <ID> | sync | items <ID> | play <ID> <N> | grokbot <MSG>"


def run(args, client, say=print, fetch=fetch_json, send_run=None) -> int:
    plugins = load_all()
    enabled = load_enabled()
    op = args[0] if args else "list"
    if op == "catalog":
        from . import catalog
        return catalog.run(args[1:], say=say, fetch=fetch)
    if op == "list":
        for pid, m in plugins.items():
            st = "on" if enabled.get(pid, True) else "off"
            alias = f" ({m['alias']})" if m.get("alias") else ""
            say(f"[{st:<3}] {m['id']}{alias} — {m['name']} by {m['author']} ({m['license']}) {m.get('source', '')}".rstrip())
        return 0
    if op in ("enable", "disable") and len(args) >= 2:
        pid = resolve_id(args[1])
        if pid not in plugins:
            say(f"✗ unknown plugin {args[1]}"); return 2
        enabled[pid] = (op == "enable")
        save_enabled(enabled)
        say(f"✓ {pid} {'enabled' if enabled[pid] else 'disabled'}")
        if client is not None:
            try:
                c = client() if callable(client) else client
                c.request("PUT", "/v1/plugins", declarative_payload(plugins, enabled))
            except Exception:
                pass
        return 0
    if op == "add" and len(args) >= 2:
        src = args[1]
        with open(src, encoding="utf-8") as fh:
            m = validate_manifest(json.load(fh))
        d = user_dir()
        os.makedirs(d, exist_ok=True)
        dst = os.path.join(d, f"{m['id']}.json")
        shutil.copyfile(src, dst)
        say(f"✓ added plugin {m['id']}")
        return 0
    if op == "remove" and len(args) >= 2:
        pid = resolve_id(args[1])
        if pid in BUILTIN:
            say(f"✗ cannot remove built-in plugin {pid}; use 'disable' instead"); return 2
        dst = os.path.join(user_dir(), f"{pid}.json")
        if os.path.isfile(dst):
            os.remove(dst)
            say(f"✓ removed {pid}")
            return 0
        say(f"✗ plugin not found: {pid}"); return 2
    if op == "sync":
        c = client() if callable(client) else client
        payload = declarative_payload(plugins, enabled)
        c.request("PUT", "/v1/plugins", payload)
        say(f"✓ synced {len(payload['plugins'])} declarative plugins to TV")
        return 0
    if op == "grokbot" and len(args) >= 2:
        msg = clean_title(" ".join(args[1:]), limit=240)
        c = client() if callable(client) else client
        c.request("POST", "/v1/plugins/grokbot", {"message": msg, "from": "pc"})
        say(f"✓ sent to Grok Bot TV companion: {msg}")
        return 0
    if op in ("items", "play") and len(args) >= 2:
        pid = resolve_id(args[1])
        m = plugins.get(pid)
        if not m or "feed" not in m:
            say(f"✗ unknown feed plugin {args[1]}"); return 2
        its = items(m, fetch)
        if op == "items":
            for i, it in enumerate(its, 1):
                say(f"{i:>3}  {it['title']}")
            if not its:
                say("(no playable items)")
            return 0
        try:
            it = its[int(args[2]) - 1]
        except (IndexError, ValueError):
            say("✗ pick an item number from 'plugins items'"); return 2
        if send_run is None:
            from omarchy_tv import send
            send_run = send.run
        return send_run([it["url"], "--title", it["title"]], client)
    say(USAGE)
    return 2
