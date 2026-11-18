"""omarchy-link command line. Run `omarchy-link help`."""

from __future__ import annotations

import argparse
import json
import os
import re
import shutil
import subprocess
import sys
from pathlib import Path

from . import DEFAULT_PORT, VERSION, ai, assistant, cast, client as link, config, mdns, netcheck, panes, sync, tailnet, theme
from .session import KEYS, Session

ROOT = Path(__file__).resolve().parents[2]
EXIT_NOT_PAIRED = 3


def _client(cfg) -> link.Client:
    p = config.Pairing.load()
    if not p.paired:
        print("omarchy-link: not paired; run: omarchy-link pair", file=sys.stderr)
        raise SystemExit(EXIT_NOT_PAIRED)
    return link.Client(p.tv, p.pc_id, not cfg.get("allowOtherSubnets"))


def _tty() -> bool:
    return sys.stdin.isatty() and sys.stdout.isatty()


def _is_ip(host: str) -> bool:
    import ipaddress
    try:
        ipaddress.ip_address(host.strip("[]").split("%", 1)[0])
        return True
    except ValueError:
        return False


def _tailnet_tvs(cfg) -> list:
    """v2.21: OmarchyTVs on this PC's tailnet (only with "tailscale": true)."""
    if cfg.get("tailscale") is not True:
        return []
    return tailnet.discover(tailnet.status(), lambda h, p: link.probe(h, p, 1.5))


def cmd_discover(a, cfg):
    devs = mdns.browse(a.timeout)
    seen = {d["address"] for d in devs}
    devs += [d for d in _tailnet_tvs(cfg) if d["address"] not in seen]
    if a.json:
        print(json.dumps(devs, indent=2))
    elif not devs:
        print("No OmarchyTV found. Open “Connect Omarchy Linux” on the TV, or pair by IP.")
    for d in devs if not a.json else []:
        print(f"{d['name']}\t{d['address']}:{d['port']}\t{'pairing open' if d['txt'].get('pair') == '1' else ''}"
              + ("\ttailscale" if d.get("via") == "tailscale" else ""))
    return 0 if devs else 1


def _host_port(text: str) -> tuple[str, int]:
    """host[:port], [v6]:port, or a bare IPv6 address (v2.21: the tailnet's fd7a:115c:a1e0::/48 too)."""
    text = text.strip()
    if text.startswith("["):
        host, _, rest = text[1:].partition("]")
        return host, int(rest[1:]) if rest.startswith(":") and rest[1:].isdigit() else DEFAULT_PORT
    if text.count(":") > 1:   # a bare IPv6 address has no port
        return text, DEFAULT_PORT
    host, sep, port = text.rpartition(":")
    if sep and port.isdigit():
        return host, int(port)
    return text, DEFAULT_PORT


def cmd_pair(a, cfg):
    pairing = config.Pairing.load()
    if a.target:
        host, port = _host_port(a.target)
        if not _is_ip(host):   # v2.21: a MagicDNS / tailnet host name ("cafe" and "beef" are names too)
            ip = tailnet.resolve(host, tailnet.status()) if cfg.get("tailscale") is True else None
            if not ip:
                print(f"omarchy-link: {host} is not an IP address" + ("" if cfg.get("tailscale") is True else
                      "; for a Tailscale name run: omarchy-link tailscale on") + ".", file=sys.stderr)
                return 1
            host = ip
    else:
        devs = mdns.browse(2.5)
        if not devs:
            devs = _tailnet_tvs(cfg)
        if not devs:
            print("No TV answered on mDNS. Pass its IP: omarchy-link pair 192.168.1.50", file=sys.stderr)
            return 1
        if len(devs) > 1 and _tty() and shutil.which("gum"):
            labels = [f"{d['name']} ({d['address']})" for d in devs]
            pick = subprocess.run(["gum", "choose", *labels], stdout=subprocess.PIPE, text=True).stdout.strip()
            devs = [d for d, l in zip(devs, labels) if l == pick] or devs
        host, port = devs[0]["address"], devs[0]["port"]
    netcheck.check_host(host, not cfg.get("allowOtherSubnets"))
    info = link.probe(host, port)
    code = a.code
    if not code:
        if not _tty():
            print("omarchy-link: --code is required without a terminal", file=sys.stderr)
            return 2
        prompt = f"Code shown on {info.get('name', 'the TV')}: "
        if shutil.which("gum"):
            code = subprocess.run(["gum", "input", "--placeholder", "123 456", "--prompt", prompt],
                                  stdout=subprocess.PIPE, text=True).stdout
        else:
            code = input(prompt)
    tv = link.pair(host, port, code.replace(" ", "").strip(), pairing.pc_id, pairing.pc_name)
    tv["name"] = tv.get("name") or info.get("name")
    pairing.set_tv(tv)
    print(f"Paired with {tv['name']} ({host}:{port}).")
    if cfg.get("themeSync", True):
        try:
            sync.sync_theme(link.Client(tv, pairing.pc_id, not cfg.get("allowOtherSubnets")), cfg, True, print)
        except Exception as e:  # pairing succeeded; a sync problem is only a warning
            print(f"theme sync: {e}", file=sys.stderr)
    return 0


def cmd_unpair(a, cfg):
    p = config.Pairing.load()
    if p.paired:
        try:
            link.Client(p.tv, p.pc_id, False).unpair()
        except (link.LinkError, ValueError) as e:
            print(f"TV not told ({e}); forgetting locally", file=sys.stderr)
    p.forget()
    print("Forgotten.")
    return 0


def waybar(state: dict) -> dict:
    if not state.get("paired"):
        return {"text": "󰔂", "tooltip": "OmarchyTV: not paired (omarchy-link pair)", "class": "unpaired", "alt": "unpaired"}
    name = (state.get("tv") or {}).get("name", "TV")
    if not state.get("connected"):
        return {"text": "󰔂", "tooltip": f"{name}: offline", "class": "offline", "alt": "offline"}
    if state.get("screensaver"):
        return {"text": "󰔂 󰒲", "tooltip": f"{name}: screensaver", "class": "screensaver", "alt": "screensaver"}
    fg = state.get("foreground") or {}
    label = fg.get("label") or ("Home" if state.get("homeVisible") else "")
    return {"text": f"󰔂 {label}".strip(), "tooltip": f"{name}: {label or 'on'}", "class": "connected", "alt": "connected"}


def cmd_status(a, cfg):
    p = config.Pairing.load()
    state = {"paired": p.paired, "connected": False, "tv": p.tv and {k: p.tv[k] for k in ("id", "name", "address", "port")}}
    if p.paired:
        try:
            st = link.Client(p.tv, p.pc_id, not cfg.get("allowOtherSubnets")).status()
            state.update(connected=True, foreground=st.get("foreground"), screensaver=st.get("screensaver"),
                         homeVisible=st.get("homeVisible"), capabilities=st.get("capabilities"))
        except (link.LinkError, ValueError) as e:
            state["error"] = str(e)
    if a.waybar:
        print(json.dumps(waybar(state), ensure_ascii=False))
    elif a.json:
        print(json.dumps(state, indent=2))
    else:
        w = waybar(state)
        print(w["tooltip"])
    return 0


def cmd_key(a, cfg):
    if a.key not in KEYS:
        print(f"unknown key; one of: {' '.join(sorted(KEYS))}", file=sys.stderr)
        return 2
    _client(cfg).key(a.key)
    return 0


def codex_text(text: str) -> str:
    """Codex's notify hook passes a JSON event as the last argument: keep its last assistant message."""
    t = text.strip()
    if not t.startswith("{"):
        return text
    try:
        ev = json.loads(t)
    except ValueError:
        return text
    if not isinstance(ev, dict):
        return text
    msg = ev.get("last-assistant-message") or ev.get("message") or ""
    kind = str(ev.get("type", "")).replace("-", " ")
    return str(msg or kind or text)


def cmd_watch(a, cfg):
    """v2.21 run a command, then tell the TV how it went: omarchy-link watch -- make build."""
    import subprocess as sp
    import time as _t
    argv = list(a.argv)
    if argv[:1] == ["--"]:
        argv = argv[1:]
    if not argv:
        print("omarchy-link watch [--speak] -- COMMAND …", file=sys.stderr)
        return 2
    t0 = _t.monotonic()
    last = ""
    try:
        p = sp.Popen(argv, stdout=sp.PIPE, stderr=sp.STDOUT, text=True, errors="replace")
    except OSError as e:
        print(f"omarchy-link watch: {e}", file=sys.stderr)
        return 127
    for line in p.stdout:
        sys.stdout.write(line)
        if line.strip():
            last = line.strip()
    rc = p.wait()
    secs = int(_t.monotonic() - t0)
    took = f"{secs // 60}m{secs % 60:02d}s" if secs >= 60 else f"{secs}s"
    name = " ".join(argv)[:50]
    title = ("✓ " if rc == 0 else f"✗ exit {rc} · ") + name
    ns = argparse.Namespace(text=[f"{took} · {last}"[:200] if last else took], source=a.source or "watch",
                            title=title, level="info" if rc == 0 else "alert", speak=a.speak, quiet=True)
    cmd_notify(ns, cfg)
    return rc


def cmd_notify(a, cfg):
    """v2.21: a short message on the TV over whatever is playing, for agent hooks and scripts.

    Exit 0 shown, 4 when agent alerts are off on the TV, 3 not paired. Text from the argument or stdin; one line,
    200 characters. Never fails a hook loudly: --quiet prints nothing and always exits 0.
    """
    text = " ".join(a.text) if a.text else ("" if sys.stdin.isatty() else sys.stdin.read(4096))
    text = " ".join(codex_text(text).split())[:200]
    if not text:
        print("omarchy-link notify: nothing to say", file=sys.stderr)
        return 0 if a.quiet else 2
    p = config.Pairing.load()
    if not p.paired:
        return 0 if a.quiet else EXIT_NOT_PAIRED
    try:
        r = link.Client(p.tv, p.pc_id, not cfg.get("allowOtherSubnets")).request(
            "POST", "/v1/notify", {"text": text, "title": (a.title or "")[:60], "level": a.level, "speak": bool(a.speak),
                                   "from": (a.source or "PC")[:40]}, timeout=4)
        if r.get("deferred") and not a.quiet:
            print("held: shown when the TV is back on Home (use --level warn to show it now)", file=sys.stderr)
        return 0
    except (link.LinkError, OSError, ValueError) as e:
        if a.quiet:
            return 0
        if getattr(e, "code", "") == "notify_off" or getattr(e, "status", 0) == 403:
            print("The TV does not show notifications: turn on agent alerts (Settings > Omarchy Link).", file=sys.stderr)
            return 4
        print(f"omarchy-link notify: {e}", file=sys.stderr)
        return 1


def _call(cfg, method, path, payload=None, timeout=8.0):
    return _client(cfg).request(method, path, payload, timeout=timeout)


def cmd_token(a, cfg):
    """v2.21 app tokens for dashboards and agents: add NAME --scope read|notify|control, list, revoke ID."""
    if a.action == "add":
        if not a.name:
            print("omarchy-link token add NAME [--scope read|notify|control]", file=sys.stderr)
            return 2
        r = _call(cfg, "POST", "/v1/tokens", {"name": " ".join(a.name), "scope": a.scope})
        if a.json:
            print(json.dumps(r, indent=2))
        else:
            print(f"{r['name']} ({r['scope']}), id {r['id']}")
            print(r["token"])
            print("Shown once: keep it in your app's settings. Revoke: omarchy-link token revoke " + r["id"], file=sys.stderr)
        return 0
    if a.action == "revoke":
        if not a.name:
            print("omarchy-link token revoke ID", file=sys.stderr)
            return 2
        _call(cfg, "DELETE", "/v1/tokens/" + a.name[0])
        print("revoked")
        return 0
    r = _call(cfg, "GET", "/v1/tokens")
    if a.json:
        print(json.dumps(r, indent=2))
        return 0
    if not r.get("apps"):
        print("No app tokens. Add one: omarchy-link token add \"My dashboard\" --scope read")
    for x in r.get("apps", []):
        print(f"{x['id']}\t{x['scope']:<8}\t{x['name']}")
    return 0


def cmd_ask(a, cfg):
    """v2.21 ask the person at the TV; prints the answer. Exit 0 answered, 5 no answer in time, 4 alerts off."""
    q = " ".join(a.question).strip()
    if not q:
        print("omarchy-link ask \"Deploy now?\" [--choice Yes --choice No] [--timeout 120]", file=sys.stderr)
        return 2
    try:
        r = _call(cfg, "POST", "/v1/ask", {"question": q, "choices": a.choice or [], "timeout": a.timeout,
                                           "from": (a.source or "PC")[:40]})
    except link.LinkError as e:
        if getattr(e, "status", 0) == 403:
            print("The TV does not take questions: turn on agent alerts (Settings > Omarchy Link).", file=sys.stderr)
            return 4
        raise
    import time as _t
    end = _t.time() + a.timeout + 5
    while _t.time() < end:
        s = _call(cfg, "GET", f"/v1/ask/{r['id']}?wait=25", timeout=35)
        if s.get("state") == "answered":
            print(s["answer"])
            return 0
        if s.get("state") == "expired":
            break
    print("no answer", file=sys.stderr)
    return 5


def cmd_page(a, cfg):
    """v2.21 open a page of the LAN / tailnet full screen on the TV, or --close it."""
    if a.close:
        r = _call(cfg, "DELETE", "/v1/page")
        print("closed" if r.get("closed") else "no page was open")
        return 0
    if not a.url:
        print("omarchy-link page http://192.168.1.20:8080/ [up to 3 more, tiled]  (or --close)", file=sys.stderr)
        return 2
    _call(cfg, "POST", "/v1/page", {"urls": a.url[:4]} if len(a.url) > 1 else {"url": a.url[0]})
    return 0


def cmd_radio(a, cfg):
    """v2.21 Radiomarchy on the TV: now | play | pause | next | previous | stop."""
    if a.action == "now":
        r = _call(cfg, "GET", "/v1/radio")
    else:
        r = _call(cfg, "POST", "/v1/radio", {"action": a.action})
    if r.get("title"):
        print(("▶ " if r.get("playing") else "❚❚ ") + f"{r['title']} · {r.get('artist') or ''}".rstrip(" ·"))
    else:
        print("nothing playing")
    return 0


def cmd_open(a, cfg):
    """v2.21 open a part of OmarchyTV on the TV."""
    _call(cfg, "POST", "/v1/open", {"target": a.target})
    return 0


def cmd_mcp(a, cfg):
    """v2.21 the TV's MCP on stdio for desktop agents (claude mcp add omarchytv -- omarchy-link mcp)."""
    from . import mcp_bridge
    return mcp_bridge.serve(lambda: _client(cfg), a.scope)


def cmd_pages(a, cfg):
    """v2.21 agent pages as Home tiles: list | add ID NAME URL [--icon X] [--badge N] | badge ID N | show ID | remove ID."""
    from urllib.parse import quote
    args = a.args
    if a.action == "list":
        r = _call(cfg, "GET", "/v1/pages")
        for pg in r.get("pages", []):
            badge = f" ({pg['badge']})" if pg.get("badge") else ""
            print(f"{pg['id']}\t{pg['name']}{badge}\t{pg['url']}\t{pg.get('by', '')}")
        if not r.get("pages"):
            print("No pages on the TV's Home. Add one: omarchy-link pages add dash \"My dashboard\" http://192.168.1.20:8080/")
        return 0
    if a.action == "add" and len(args) >= 3:
        _call(cfg, "PUT", "/v1/pages/" + quote(args[0]), {"name": args[1], "url": args[2], "icon": a.icon or "", "badge": a.badge})
        return 0
    if a.action == "badge" and len(args) >= 2 and args[1].isdigit():
        _call(cfg, "POST", "/v1/pages/" + quote(args[0]) + "/badge", {"badge": int(args[1])})
        return 0
    if a.action in ("show", "remove") and args:
        _call(cfg, "POST" if a.action == "show" else "DELETE", "/v1/pages/" + quote(args[0]) + ("/show" if a.action == "show" else ""))
        return 0
    print("omarchy-link pages [list] | add ID NAME URL [--icon X] [--badge N] | badge ID N | show ID | remove ID", file=sys.stderr)
    return 2


def cmd_tv(a, cfg):
    """v2.21 tv logs | tv diagnostics: what a bug report needs (no tokens, no keys)."""
    if a.what == "backup":   # v2.21: the TV's omarchytv.json (settings, layout, themes chosen; no secrets)
        c = _call(cfg, "GET", "/v1/config").get("config", {})
        text = json.dumps(c, indent=2, ensure_ascii=False)
        if a.file:
            Path(a.file).write_text(text + "\n", encoding="utf-8")
            print(f"saved {a.file}")
        else:
            print(text)
        return 0
    if a.what == "restore":
        if not a.file:
            print("omarchy-link tv restore FILE", file=sys.stderr)
            return 2
        data = json.loads(Path(a.file).read_text(encoding="utf-8"))
        if not isinstance(data, dict):
            print("not an omarchytv.json backup", file=sys.stderr)
            return 2
        _call(cfg, "PUT", "/v1/config", {"config": data})
        print("restored (this TV's pairings and panes were kept)")
        return 0
    d = _call(cfg, "GET", "/v1/diagnostics")
    if a.json or a.what == "diagnostics":
        print(json.dumps(d, indent=2))
        return 0
    for line in d.get("log", []):
        print(line)
    return 0


def cmd_launch(a, cfg):
    _client(cfg).launch(a.package)
    return 0


def cmd_apps(a, cfg):
    apps = _client(cfg).apps()
    if a.json:
        print(json.dumps(apps, indent=2))
    for app in apps if not a.json else []:
        print(f"{app.get('label', '')}\t{app.get('package', '')}")
    return 0


def cmd_sync(a, cfg):
    p = config.Pairing.load()
    if not p.paired:
        return 0 if a.quiet else EXIT_NOT_PAIRED
    failed = 0
    for tv in p.tvs:  # 2.13: every paired TV gets the theme
        try:
            res = sync.sync_theme(link.Client(tv, p.pc_id, not cfg.get("allowOtherSubnets")), cfg, a.force,
                                  (lambda m: None) if a.quiet else print)
            if res is None and not a.quiet:
                why = ("This PC has no Omarchy theme; the TV keeps its own." if not theme.has_theme() else
                       "Nothing to send (unchanged, or themeSync is off; use --force).")
                print(why if len(p.tvs) == 1 else f"{tv.get('name') or tv.get('address')}: {why}")
        except (link.LinkError, OSError, RuntimeError, ValueError, subprocess.SubprocessError) as e:
            failed += 1
            if not a.quiet:
                print(f"omarchy-link: {tv.get('name') or tv.get('address')}: {e}", file=sys.stderr)
    return 0 if a.quiet or not failed else 1  # a hook must never fail a theme switch


def cmd_push_widgets(a, cfg):
    widgets = panes.load_widgets(Path(a.file) if a.file else None)
    if cfg.get("pcPane", True):
        widgets["panes"] = [panes.pc_pane()] + list(widgets.get("panes", []))
    clean = panes.validate(widgets, cfg.get("actions", {}))
    if a.dry_run:
        print(json.dumps(clean, indent=2))
        return 0
    _client(cfg).put_widgets(clean)
    print(f"Sent {len(clean['panes'])} panes and {len(clean['shortcuts'])} shortcuts.")
    return 0


def cmd_push_ai(a, cfg):
    data = ai.payload()
    if a.dry_run:
        print(json.dumps(data, indent=2))
        return 0
    _client(cfg).put_ai(data)
    print(f"Sent {len(data['ai'])} AI credit entries.")
    return 0


def _preview(preset: str) -> str:
    try:
        data = ai.collect({"providers": [{"type": "mirror", "preset": preset}], "autoDetect": False},
                          config.AI_FILE)["ai"]
    except Exception as e:  # noqa: BLE001 - a preview must never break setup
        return f"(preview failed: {e})"
    parts = []
    for e in data[:4]:
        if e.get("status") == "error":
            parts.append(f"{e['name']}: {e.get('error', 'error')}")
        elif e.get("unit") == "%":
            parts.append(f"{e['name']} {e['used']:g}%" if e.get("used") is not None else e["name"])
        else:
            val = e.get("remaining") if e.get("remaining") is not None else e.get("used")
            parts.append(f"{e['name']} {e['unit']}{val:g}" if val is not None else e["name"])
    return "; ".join(parts) + (f" (+{len(data) - 4} more)" if len(data) > 4 else "") if parts else "(no rows yet)"


def cmd_ai_setup(a, cfg):
    """Pick which bar widget the TV mirrors; writes ai.json. Nothing is recomputed."""
    from . import widgets
    cands = widgets.candidates()
    if a.json:
        print(json.dumps({"candidates": cands, "default": widgets.default_preset()}, indent=2))
        return 0
    if not cands:
        print("No AI-usage bar widget found in ~/.config/omarchy/plugins or the Agents panel records.\n"
              "Without one, omarchy-link reads signed-in Claude Code / Codex itself (see docs/AI-CREDITS.md),\n"
              "or set a generic mirror in ai.json.")
        return 0
    default = widgets.default_preset()
    print("AI-usage widgets on this PC:")
    for i, c in enumerate(cands, 1):
        tag = "  [default]" if c["preset"] and c["preset"] == default else ""
        bar = "in bar" if c["inBar"] else "not in bar"
        print(f" {i}. {c['name']} ({c['id']}) - {bar}; {c['why']}{tag}")
        if c["preset"] and not a.list:
            print(f"    now shows: {_preview(c['preset'])}")
    if a.list:
        return 0
    if a.use:
        chosen = [x.strip() for x in a.use.split(",") if x.strip()]
    elif _tty() and not a.yes:
        ans = input(f"Mirror which? numbers, comma separated [{default or 1}]: ").strip()
        if not ans:
            chosen = [default] if default else [cands[0]["preset"]]
        else:
            chosen = []
            for tok in ans.split(","):
                tok = tok.strip()
                if tok.isdigit() and 1 <= int(tok) <= len(cands):
                    chosen.append(cands[int(tok) - 1]["preset"])
    else:
        chosen = [default] if default else []
    chosen = [c for c in chosen if c]
    from .ai.providers import mirror
    bad = [c for c in chosen if c not in mirror.PRESETS]
    if bad or not chosen:
        print(f"omarchy-link: nothing to mirror{' (unknown: ' + ', '.join(bad) + ')' if bad else ''}; "
              "generic widgets need a source in ai.json (docs/AI-CREDITS.md)", file=sys.stderr)
        return 1
    include = [x.strip() for x in a.include.split(",")] if a.include else None
    providers = [{"type": "mirror", "preset": c, **({"include": include} if include else {})} for c in chosen]
    old = config.read_json(config.AI_FILE, {})
    keep = [p for p in old.get("providers", []) if isinstance(p, dict) and p.get("type") == "manual"] if a.keep_manual else []
    new = {**{k: v for k, v in old.items() if k not in ("providers", "autoDetect")}, "autoDetect": False,
           "providers": providers + keep}
    if a.dry_run:
        print(json.dumps(new, indent=2))
        return 0
    if config.AI_FILE.exists():
        shutil.copy2(config.AI_FILE, config.AI_FILE.with_suffix(".json.bak"))
    config.write_json_private(config.AI_FILE, new)
    print(f"Wrote {config.AI_FILE}: the TV now mirrors {', '.join(chosen)}.")
    return 0


def cmd_ai_sources(a, cfg):
    """List or switch the bar widgets the TV mirrors (the TV can switch them too)."""
    from .ai import sources
    if a.action in ("enable", "disable", "only"):
        if not a.ids:
            print(f"omarchy-link: ai sources {a.action} needs at least one id", file=sys.stderr)
            return 2
        on = sources.enabled()
        if a.action == "enable":
            on += [i for i in a.ids if i not in on]
        elif a.action == "disable":
            on = [i for i in on if i not in a.ids]
        else:
            on = list(a.ids)
        try:
            on = sources.set_enabled(on)
        except sources.SourceError as e:
            print(f"omarchy-link: {e}", file=sys.stderr)
            return 1
        print(f"Mirroring: {', '.join(on) or '(nothing)'} (sent with the next AI refresh; `omarchy-link push-ai` sends now)")
    lst = sources.listing()
    if a.json:
        print(json.dumps(lst, indent=2))
    elif a.action == "list":
        if not lst:
            print("No mirror sources found (see `omarchy-link ai setup`).")
        for s in lst:
            note = "" if s["available"] else "  (not found on this PC)"
            print(f" [{'x' if s['enabled'] else ' '}] {s['id']:<16} {s['name']}{note}")
    return 0


def cmd_ai(a, cfg):
    """Try the assistant locally (no TV needed) and see which AIs it found."""
    if a.ai_cmd == "setup":
        return cmd_ai_setup(a, cfg)
    if a.ai_cmd == "sources":
        return cmd_ai_sources(a, cfg)
    asst = assistant.Assistant()
    if a.ai_cmd == "providers":
        lst = asst.listing()
        if a.json:
            print(json.dumps(lst, indent=2, ensure_ascii=False))
        elif not lst["providers"]:
            print("No signed-in AI found. Sign in to claude / codex / grok / gemini, start ollama,\n"
                  f"or add an API provider to {assistant.ASSISTANT_FILE}.")
        else:
            for p in lst["providers"]:
                star = "*" if p["id"] == lst["default"] else " "
                print(f"{star} {p['id']:<16} {p['name']:<20} {p['kind']:<6} {p.get('model') or ''}")
        return 0
    if a.ai_cmd == "providers-push":
        lst = asst.listing()
        _client(cfg).put_ai_providers(lst)
        print(f"Sent {len(lst['providers'])} assistant providers to the TV.")
        return 0
    printed = False

    def _on_delta(t):
        nonlocal printed
        if t:
            printed = True
            sys.stdout.write(t)
            sys.stdout.flush()

    try:
        res = asst.ask(" ".join(a.text), provider=a.provider, lang=a.lang, on_delta=_on_delta)
    except (ValueError, assistant.NoProvider, assistant.RateLimited, ai.ProviderError) as e:
        print(f"omarchy-link: {e}", file=sys.stderr)
        return 1
    if not printed and res.get("text"):
        sys.stdout.write(res["text"])
        sys.stdout.flush()
    print(f"\n[{res['provider']}{' / ' + res['model'] if res.get('model') else ''}]")
    return 0


def cmd_office(a, cfg):
    """GrokBot Office (OmarchyTV 2.20): Claude / ChatGPT bots on this PC, and their real spend."""
    from . import office
    if a.office_cmd == "hire":
        try:
            bot = office.hire(a.name, a.provider, a.role or "", a.description or "", model=a.model)
        except office.HireError as e:
            print(f"omarchy-link: {e}", file=sys.stderr)
            return 1
        print(f"Hired {bot['name']} ({bot['provider']}) in {assistant.ASSISTANT_FILE}.")
    elif a.office_cmd == "fire":
        if not office.fire(a.name):
            print(f"omarchy-link: no hired bot called {a.name}", file=sys.stderr)
            return 1
        print(f"{a.name} left the office.")
    elif a.office_cmd == "usage":
        u = office.usage()
        if a.json:
            print(json.dumps(u, indent=2, ensure_ascii=False))
        elif not u:
            print("No usage numbers: set an admin key (ANTHROPIC_ADMIN_KEY / OPENAI_ADMIN_KEY, or an anthropic / openai\n"
                  f"entry in {config.AI_FILE}). Without one the TV shows no usage for that provider.")
        for x in ([] if a.json else u):
            extra = f" of {x['budget']} ({x['pct']}%)" if x.get("budget") else ""
            print(f"{x['provider']:<8} {x['unit']}{x['spent']}{extra} this month")
        return 0
    else:
        d = office.tv_payload()
        if a.json or a.office_cmd == "show":
            print(json.dumps(d, indent=2, ensure_ascii=False))
        if a.office_cmd == "push":
            _client(cfg).request("PUT", "/v1/office", d)
            print(f"Sent {len(d['bots'])} bots to the TV.")
        return 0
    if a.office_cmd in ("hire", "fire"):
        try:
            c = _client(cfg)
            c.put_ai_providers(assistant.Assistant().listing())
            c.request("PUT", "/v1/office", office.tv_payload())
            print("The TV has the new office.")
        except (SystemExit, Exception) as e:   # not paired / TV off: the next connection sends it
            print(f"(the TV gets it when it connects: {str(e)[:80]})")
    return 0


HOOK_NAME = "omarchy-link"


def cmd_hook(a, cfg):
    hook_dir = Path.home() / ".config/omarchy/hooks/theme-set.d"
    dst = hook_dir / HOOK_NAME
    if a.action == "add":
        hook_dir.mkdir(parents=True, exist_ok=True)
        src = (ROOT / "hooks/theme-set").read_text().replace("@OMARCHY_LINK_ROOT@", str(ROOT))
        dst.write_text(src)
        os.chmod(dst, 0o755)
        print(f"Theme-set hook written to {dst}")
    else:
        if dst.exists():
            dst.unlink()
        print("Theme-set hook removed.")
    return 0


def cmd_action(a, cfg):
    if not a.confirmed:
        print("omarchy-link: action needs --confirmed", file=sys.stderr)
        return 2
    Session(out=open(os.devnull, "w")).run_action(a.id, confirmed=True)
    return 0


# ------------------------------------------------------------------ cast ("Share to TV")
def _ufw_hint(state, served: bool, tv_ip: str | None = None, run=subprocess.run, which=shutil.which) -> str:
    """#82: stock Omarchy turns ufw on (deny incoming), so the TV can't fetch a stream the PC serves on a random
    port and the cast just ends as "disconnected"/"error". When that happens with ufw active, say so and how to fix it."""
    if not served or state not in ("error", "disconnected", "timeout") or not which("ufw"):
        return ""
    try:
        if run(["systemctl", "is-active", "--quiet", "ufw"], timeout=3).returncode != 0:
            return ""
    except Exception:
        return ""
    who = tv_ip or "<tv-ip>"
    return (f"ufw is on and probably blocked the TV from fetching the stream. Allow the TV once with:\n"
            f"  sudo ufw allow from {who} proto tcp comment 'OmarchyTV'")


def _notify(text: str, title: str = "OmarchyTV") -> None:
    cmds = [["omarchy-notification-send", "-g", "󰔂", title, text], ["notify-send", "-a", "omarchy-link", title, text]]
    if sys.platform == "darwin":  # title and text go in as arguments, so quotes in them can't break the AppleScript
        cmds.append(["osascript", "-e", "on run argv", "-e", "display notification (item 2 of argv) with title (item 1 of argv)",
                     "-e", "end run", title, text])
    for argv in cmds:
        if shutil.which(argv[0]):
            subprocess.run(argv, stdin=subprocess.DEVNULL, capture_output=True, timeout=10)
            return


FALLBACK = {"no_catt", "no_device", "capability_missing", "unsupported_kind", "not_paired", "unreachable",
            "serve_needed", "not_target"}


def _link_client(cfg, to):
    p = config.Pairing.load()
    if not p.paired:
        raise cast.CastError("no OmarchyTV launcher paired (omarchy-link pair)", "not_paired")
    if to and to.lower() not in (str(p.tv.get("name", "")).lower(), str(p.tv.get("address", ""))) \
            and to.lower() not in str(p.tv.get("name", "")).lower():
        raise cast.CastError(f"{to!r} is not the paired OmarchyTV ({p.tv.get('name')})", "not_target")
    try:
        return link.Client(p.tv, p.pc_id, not cfg.get("allowOtherSubnets"))
    except ValueError as e:
        raise cast.CastError(str(e), "not_lan") from e


def _warn_relay(cfg, item, say) -> None:
    p = config.Pairing.load()
    addr = (p.tv or {}).get("address", "") if p.paired else ""
    if cfg.get("tailscale") is not True or not netcheck.is_tailnet(addr):
        return
    if item.get("kind") in ("video", "audio", "image", "file") or item.get("screen") or item.get("path"):
        if tailnet.relayed(tailnet.status(), addr):
            say("Tailscale reaches the TV through a relay (DERP), not directly: a file or the screen may stutter. "
                "`tailscale ping <tv>` shows when the direct path is up.")


def cmd_cast(a, cfg):
    """Send a link, a file, the clipboard, text or (experimental) the screen to the TV."""
    say = (lambda m: None) if a.json else print
    try:
        if a.devices:
            devs = cast.cast_devices(refresh=True) if cast.catt() else []
            p = config.Pairing.load()
            linked = None
            if p.paired:
                try:
                    caps = cast.link_caps(_link_client(cfg, None).status())
                    linked = {"name": p.tv.get("name"), "address": p.tv.get("address"), "cast": caps["cast"],
                              "kinds": caps["kinds"] if caps["cast"] else [], "upload": caps["upload"]}
                except (cast.CastError, link.LinkError) as e:
                    linked = {"name": p.tv.get("name"), "address": p.tv.get("address"), "cast": False, "error": str(e)}
            if a.json:
                print(json.dumps({"catt": bool(cast.catt()), "cast": devs, "link": linked}, indent=2))
                return 0
            print("Google Cast (catt): " + ("" if cast.catt() else "catt not installed (pipx install catt)"))
            for d in devs:
                print(f"  {d['name']:<28} {d['address']:<16} {d['model']}")
            if cast.catt() and not devs:
                print("  none found")
            print("OmarchyTV Link: " + ("not paired" if not linked else
                  f"{linked['name']} ({linked['address']}) - " + (", ".join(linked["kinds"]) if linked.get("cast")
                                                                   else "no cast support in this launcher yet")))
            return 0
        if a.stop or a.control:
            done = []
            action = "stop" if a.stop else a.control
            if a.via in ("auto", "link"):
                try:
                    c = _link_client(cfg, a.to)
                    if cast.link_caps(c.status())["cast"]:
                        cast.link_control(c, action, a.at)
                        done.append("OmarchyTV")
                except (cast.CastError, link.LinkError):
                    pass
            if a.via in ("auto", "cast") and action == "stop" and cast.catt():
                if cast.cast_stop(cast.pick_device(a.to, cfg)):
                    done.append("Google Cast")
            say(f"{action}: " + (", ".join(done) if done else "nothing to control"))
            return 0 if done else 1
        if a.screen:
            item = cast.screen_item(cfg)
        elif a.text is not None:
            item = cast.text_item(a.text, a.title)
        elif a.clipboard:
            item = cast.from_clipboard(a.title)
        elif a.target:
            item = cast.classify(a.target, a.kind, a.title, a.at)
        else:
            print("omarchy-link: what to send? a link, a file, --clipboard, --text or --screen", file=sys.stderr)
            return 2
        if a.at and "position" not in item and item["kind"] in ("youtube", "video", "audio"):
            item["position"] = cast.seconds(a.at)
        _warn_relay(cfg, item, say)   # v2.21: a tailnet TV reached only through DERP: big files and screens crawl
        serve_ok = bool(a.serve or cfg.get("castServe") or item.get("screen"))
        errors, result = [], None
        for route in cast.choose(item, a.via, cfg, serve_ok):
            try:
                if route == "cast":
                    tv_name = config.Pairing.load().tv.get("name") if config.Pairing.load().paired else None
                    result = cast.via_cast(item, cast.pick_device(a.to, cfg, tv_name), serve_ok, say)
                else:
                    try:
                        result = cast.via_link(_link_client(cfg, a.to), item, serve_ok, a.wait, say)
                    except link.LinkError as e:
                        code = "unreachable" if not e.status else ("capability_missing" if e.code == "not_found" else e.code or "link_error")
                        raise cast.CastError(str(e), code) from e
                break
            except cast.CastError as e:
                errors.append((route, e))
                if e.code not in FALLBACK:
                    break
        if not result:
            # the most useful error: not "not installed / not paired" when the other route got further
            e = next((x for _, x in errors if x.code not in ("not_paired", "no_catt")), errors[0][1])
            msg = "; ".join(f"{'Google Cast' if r == 'cast' else 'OmarchyTV'}: {x}" for r, x in errors)
            if a.json:
                print(json.dumps({"ok": False, "error": e.code, "message": msg}))
            else:
                print(f"omarchy-link: {msg}", file=sys.stderr)
            if a.notify:
                _notify(msg[:200], "Send to TV failed")
            return 1
        where = "Google Cast" + (f" ({result['device']})" if result.get("device") else "") if result["via"] == "cast" \
            else "OmarchyTV" + (f" ({result['app']})" if result.get("app") else "")
        if a.json:
            print(json.dumps({"ok": True, "kind": item["kind"], "title": item.get("title"), **result}))
        else:
            print(f"Sent {item['kind']} \u201c{item.get('title', '')}\u201d to the TV via {where}."
                  + (f" Last state: {result['state']}." if result.get("state") not in (None, "opening") else ""))
            hint = _ufw_hint(result.get("state"), result["via"] == "link" and bool(result.get("served")), result.get("tvIp"))
            if hint:
                print(hint, file=sys.stderr)
        if a.notify:
            _notify(f"{item.get('title', item['kind'])} → TV via {where}", "Sent to TV")
        return 0 if result.get("state") != "error" else 1
    except cast.CastError as e:
        print(f"omarchy-link: {e}", file=sys.stderr)
        if a.notify:
            _notify(str(e)[:200], "Send to TV failed")
        return 1


DESKTOP_MIME = ("video/mp4;video/x-matroska;video/webm;video/quicktime;video/x-msvideo;video/mp2t;video/mpeg;"
                "audio/mpeg;audio/flac;audio/ogg;audio/mp4;audio/x-wav;audio/aac;image/jpeg;image/png;image/gif;"
                "image/webp;image/avif;image/heic;text/plain;text/markdown;x-scheme-handler/http;x-scheme-handler/https;")


def desktop_entries(exe: str) -> dict:
    head = "[Desktop Entry]\nType=Application\nCategories=AudioVideo;Network;\nIcon=video-display\nTerminal=false\n"
    return {
        "omarchy-link-share.desktop": head + f"Name=Send to TV\nComment=Play this on the TV (OmarchyTV / Google Cast)\n"
            f"Exec={exe} cast --notify %u\nMimeType={DESKTOP_MIME}\nNoDisplay=true\n",
        "omarchy-link-share-clipboard.desktop": head + "Name=Send clipboard to TV\n"
            "Comment=Send the copied link, file path or text to the TV\nKeywords=cast;tv;share;youtube;airplay;\n"
            f"Exec={exe} cast --clipboard --notify\n",
        "omarchy-link-stop-cast.desktop": head + "Name=Stop casting to TV\nKeywords=cast;tv;stop;\n"
            f"Exec={exe} cast --stop --notify\n",
        "omarchy-link-mirror.desktop": head + "Name=Mirror screen to TV (experimental)\nKeywords=cast;tv;mirror;screen;\n"
            f"Exec={exe} cast --screen --notify\n",
    }


def cmd_desktop(a, cfg):
    """Launcher entries (Super+Space) and file-manager "Open with → Send to TV"."""
    apps = Path(os.environ.get("XDG_DATA_HOME") or Path.home() / ".local/share") / "applications"
    exe = os.environ.get("OMARCHY_LINK_BIN") or str(ROOT / "bin" / "omarchy-link")
    entries = desktop_entries(exe)
    if a.remove:
        for n in entries:
            (apps / n).unlink(missing_ok=True)
        print("Removed the Send to TV entries.")
        return 0
    apps.mkdir(parents=True, exist_ok=True)
    for n, text in entries.items():
        (apps / n).write_text(text)
    if shutil.which("update-desktop-database"):
        subprocess.run(["update-desktop-database", str(apps)], capture_output=True)
    print(f"Installed {len(entries)} entries in {apps}: \"Send clipboard to TV\", \"Stop casting to TV\", "
          "\"Mirror screen to TV\" in the launcher, and \"Send to TV\" under Open with in file managers.")
    return 0


# ------------------------------------------------------------------ agents (live monitor on the TV)
def _exe() -> str:
    return os.environ.get("OMARCHY_LINK_BIN") or str(ROOT / "bin" / "omarchy-link")


def cmd_agents(a, cfg):
    from . import agents
    from .agents import ask as agent_ask
    act = a.action
    if act == "hook":  # called by Claude Code hooks: never print, never fail the agent
        try:
            payload = json.loads(sys.stdin.read(1 << 20) or "{}")
            rec = agents.hook_record(payload) if isinstance(payload, dict) else None
            if rec:
                f = config.STATE_DIR / agents.HOOK_EVENTS
                config.STATE_DIR.mkdir(parents=True, exist_ok=True, mode=0o700)
                if f.exists() and f.stat().st_size > 256 * 1024:
                    keep = f.read_bytes()[-64 * 1024:]
                    f.write_bytes(keep[keep.find(b"\n") + 1:])
                fd = os.open(f, os.O_WRONLY | os.O_CREAT | os.O_APPEND, 0o600)
                with os.fdopen(fd, "a") as fh:
                    fh.write(json.dumps(rec, separators=(",", ":")) + "\n")
        except (OSError, ValueError):
            pass
        return 0
    if act == "approve":
        ok = bool(a.args) and agent_ask.approve(a.args[0])
        print("Approved: the agent runs now." if ok else "Nothing to approve (expired?).")
        return 0 if ok else 1
    if act in ("enable", "disable"):
        m = dict(agents.settings(cfg), enabled=act == "enable")
        if a.args:
            m["agents"] = [x for x in a.args if x in agents.READERS] or None
        changes = {"agentMonitor": {k: v for k, v in m.items() if k in ("enabled", "agents", "projects", "hideProjects",
                                                                         "steps", "activeMinutes", "maxAgents")}}
        config.save_config(changes)
        print(f"Agent monitor {'on' if m['enabled'] else 'off'}"
              + (f" for {', '.join(m['agents'])}" if m.get("agents") else "") + ". Instructions from the TV: "
              + ("on" if cfg.get("agentControlFromTv") else "off (\"agentControlFromTv\": true in config.json)"))
        return 0
    if act == "hooks":
        sub_ = (a.args or ["install"])[0]
        try:
            if sub_ == "remove":
                print("Removed omarchy-link hooks from Claude Code." if agent_ask.hooks_remove() else "No hooks to remove.")
            else:
                f = agent_ask.hooks_install(_exe())
                print(f"Claude Code hooks added to {f} (backup: {f.name}.omarchy-link.bak). They only record the "
                      "event, tool name and a sanitized target, for a sharper 'needs your input' on the TV.")
        except agent_ask.AskError as e:
            print(f"omarchy-link: {e}", file=sys.stderr)
            return 1
        return 0
    snap = agents.snapshot({**cfg, "agentMonitor": {**agents.settings(cfg), "enabled": True}} if act == "preview" else cfg)
    if a.json:
        print(json.dumps(agents.public(snap), indent=2))
        return 0
    if not snap.get("enabled"):
        print("Agent monitor is off. Preview with `omarchy-link agents preview`, turn on with `omarchy-link agents enable`.")
        return 0
    if not snap["agents"]:
        print("No agent active in the last "
              f"{agents.settings(cfg)['activeMinutes']} minutes.")
    for x in snap["agents"]:
        flag = "  ⚠ " + (x.get("question") or "needs input") if x["needsInput"] else ""
        print(f"{x['agentName']:<9} {x.get('project') or '-':<22} {x['status']:<9} {x.get('step') or ''}{flag}")
        for s in x["steps"][-3:]:
            print(f"{'':<9}   {s['status']:<7} {s['text']}")
    return 0


PROMPT_HELP = "lowercase a-z 0-9 . _ -, at most 16 characters"


def cmd_prompt(a, cfg):
    """The TV's home prompt "<user>@<host>:~$" (sent with the theme)."""
    if a.action == "show":
        print(json.dumps(sync.prompt_of(cfg) or {}))
        return 0
    if a.action == "clear":
        config.save_config({"prompt": None})
        print("The TV prompt is no longer sent (the TV keeps its last value).")
        return 0
    if not a.user:
        print("omarchy-link: prompt set <user> [host]", file=sys.stderr)
        return 2
    vals = {"user": a.user, **({"host": a.host} if a.host else {})}
    bad = [v for v in vals.values() if not sync.PROMPT_RE.match(v)]
    if bad:
        print(f"omarchy-link: {', '.join(bad)}: {PROMPT_HELP}", file=sys.stderr)
        return 1
    config.save_config({"prompt": vals})
    print(f"TV prompt: {vals['user']}@{vals.get('host', '<tv default>')}:~$ (sent with the next theme sync)")
    if a.now:
        cfg = config.load_config()
        sync.sync_theme(_client(cfg), cfg, force=True)
        print("Sent.")
    return 0


def cmd_liftoff(a, cfg):
    from . import liftoff   # noqa: PLC0415
    argv = list(a.command)
    if argv[:1] == ["--"]:
        argv = argv[1:]
    if not argv:
        print("usage: omarchy-link liftoff [--name NAME] -- <command> [args…]", file=sys.stderr)
        return 2
    return liftoff.run(argv, a.name)


def cmd_x(a, cfg):
    """X API for the Living screensaver: setup (bearer token + username, stored 0600), status, forget."""
    from . import living   # noqa: PLC0415
    if a.action == "setup":
        import getpass   # noqa: PLC0415
        user = (a.username or input("X username (without @): ")).strip().lstrip("@")
        if not re.match(r"^[A-Za-z0-9_]{1,15}$", user):
            print("not an X username", file=sys.stderr)
            return 2
        token = getpass.getpass("X API bearer token (developer.x.com, not shown): ").strip()
        if not token:
            return 2
        living.x_save({"bearer": token, "username": user})
        print(f"Saved in {living.X_FILE} (mode 0600). The TV uses it only with Living screensaver > X: API, "
              f"while the screensaver runs, up to about {cfg.get('xMonthlyUsd', 1.0)} USD a month (xMonthlyUsd).")
        return 0
    if a.action == "forget":
        try:
            living.X_FILE.unlink()
        except OSError:
            pass
        print("X API token removed.")
        return 0
    d = living.x_load()
    print(json.dumps({"configured": bool(d.get("bearer")), "username": d.get("username"),
                      "spentThisMonthUsd": living.x_spent(d), "limitUsd": cfg.get("xMonthlyUsd", 1.0),
                      "enabled": cfg.get("xApi", True) is not False}, indent=2))
    return 0


def cmd_session(a, cfg):
    return Session().run(daemon=getattr(a, "daemon", False))


def cmd_tailscale(a, cfg):
    """v2.21: on | off | status. On lets this PC talk to TVs on its tailnet; the TV has its own switch."""
    if a.action == "acl":   # a tailnet policy that lets only tagged PCs reach the TVs' Omarchy Link port
        print(json.dumps(tailnet.acl_example(), indent=2))
        return 0
    if a.action in ("on", "off"):
        config.save_config({"tailscale": a.action == "on"})
        if a.action == "on":
            print("Tailscale on for this PC. On the TV: Settings > Omarchy Link > Allow Tailscale (tailnet).")
            print("Then: omarchy-link discover   (or: omarchy-link pair <tv-name>)   See docs/TAILSCALE.md.")
        else:
            print("Tailscale off: Omarchy Link talks to the local network only.")
        return 0
    st = tailnet.status()
    on = cfg.get("tailscale") is True
    out = {"enabled": on, "tailscale": st is not None, "self": tailnet.self_address(st),
           "tvs": tailnet.discover(st, lambda h, p: link.probe(h, p, 1.5)) if on and st else []}
    if a.json:
        print(json.dumps(out, indent=2))
        return 0
    print(f"Omarchy Link over Tailscale: {'on' if on else 'off'}")
    if st is None:
        print("tailscale: not installed or not running (sudo tailscale up)")
        return 1
    print(f"this PC: {out['self'] or '-'}")
    if not on:
        print("turn it on: omarchy-link tailscale on")
    for d in out["tvs"]:
        path = "relay (DERP)" if tailnet.relayed(st, d["address"]) else "direct"   # probing just woke the path
        print(f"TV: {d['name']}\t{d['address']}:{d['port']}\t{d['dns']}\t{path}\t{'pairing open' if d['txt']['pair'] == '1' else ''}")
    if on and not out["tvs"]:
        print("no OmarchyTV answered on the tailnet (is Tailscale allowed on the TV, and the TV on the tailnet?)")
    return 0


def main(argv=None) -> int:
    ap = argparse.ArgumentParser(prog="omarchy-link", description="Pair this Omarchy PC with an OmarchyTV launcher.")
    ap.add_argument("--version", action="version", version=f"omarchy-link {VERSION}")
    sub = ap.add_subparsers(dest="cmd")
    s = sub.add_parser("discover", help="find OmarchyTV launchers on the LAN (mDNS)")
    s.add_argument("--json", action="store_true")
    s.add_argument("--timeout", type=float, default=2.5)
    s.set_defaults(fn=cmd_discover)
    s = sub.add_parser("pair", help="pair with the 6-digit code shown on the TV")
    s.add_argument("target", nargs="?", help="TV IP[:port] or, with Tailscale on, its tailnet name; discovered when omitted")
    s.add_argument("--code")
    s.set_defaults(fn=cmd_pair)
    s = sub.add_parser("notify", help="show a short message on the TV (agent hooks, scripts); text or stdin")
    s.add_argument("text", nargs="*")
    s.add_argument("--from", dest="source", help="who is talking, e.g. \"Claude Code\"")
    s.add_argument("--title")
    s.add_argument("--level", choices=["info", "warn", "alert"], default="info",
                   help="info waits while a film is on; warn and alert show at once")
    s.add_argument("--speak", action="store_true", help="also read it aloud on the TV")
    s.add_argument("--quiet", "-q", action="store_true", help="never fail (for hooks)")
    s.set_defaults(fn=cmd_notify)
    s = sub.add_parser("token", help="app tokens for dashboards and agents: list | add NAME --scope | revoke ID")
    s.add_argument("action", nargs="?", choices=["list", "add", "revoke"], default="list")
    s.add_argument("name", nargs="*")
    s.add_argument("--scope", choices=["read", "notify", "control"], default="read")
    s.add_argument("--json", action="store_true")
    s.set_defaults(fn=cmd_token)
    s = sub.add_parser("watch", help="run a command, then tell the TV how it went (✓ / ✗, time, last line)")
    s.add_argument("--from", dest="source")
    s.add_argument("--speak", action="store_true")
    s.add_argument("argv", nargs=argparse.REMAINDER)
    s.set_defaults(fn=cmd_watch)
    s = sub.add_parser("ask", help="ask the person at the TV, print the answer (agent hooks, scripts)")
    s.add_argument("question", nargs="*")
    s.add_argument("--choice", action="append", help="an answer (up to 4; default Yes / No)")
    s.add_argument("--timeout", type=int, default=120)
    s.add_argument("--from", dest="source")
    s.set_defaults(fn=cmd_ask)
    s = sub.add_parser("page", help="open a LAN / tailnet page full screen on the TV, or up to 4 tiled (or --close)")
    s.add_argument("url", nargs="*")
    s.add_argument("--close", action="store_true")
    s.set_defaults(fn=cmd_page)
    s = sub.add_parser("pages", help="agent pages as tiles on the TV's Home: list | add | badge | show | remove")
    s.add_argument("action", nargs="?", choices=["list", "add", "badge", "show", "remove"], default="list")
    s.add_argument("args", nargs="*")
    s.add_argument("--icon")
    s.add_argument("--badge", type=int, default=0)
    s.set_defaults(fn=cmd_pages)
    s = sub.add_parser("radio", help="Radiomarchy on the TV: now | play | pause | next | previous | stop")
    s.add_argument("action", nargs="?", choices=["now", "play", "pause", "next", "previous", "stop"], default="now")
    s.set_defaults(fn=cmd_radio)
    s = sub.add_parser("open", help="open home | radiomarchy | cinemarchy | arcade | office on the TV")
    s.add_argument("target", choices=["home", "radiomarchy", "cinemarchy", "arcade", "office"])
    s.set_defaults(fn=cmd_open)
    s = sub.add_parser("mcp", help="the TV's MCP tools on stdio for AI agents (claude mcp add omarchytv -- omarchy-link mcp)")
    s.add_argument("--scope", choices=["read", "notify", "control"], default="control")
    s.set_defaults(fn=cmd_mcp)
    s = sub.add_parser("tv", help="tv logs | diagnostics (bug reports) | backup [FILE] | restore FILE")
    s.add_argument("what", nargs="?", choices=["logs", "diagnostics", "backup", "restore"], default="logs")
    s.add_argument("file", nargs="?")
    s.add_argument("--json", action="store_true")
    s.set_defaults(fn=cmd_tv)
    s = sub.add_parser("tailscale", help="reach the TV over Tailscale: on | off | status (off by default)")
    s.add_argument("action", nargs="?", choices=["on", "off", "status", "acl"], default="status")
    s.add_argument("--json", action="store_true")
    s.set_defaults(fn=cmd_tailscale)
    sub.add_parser("unpair", help="forget the TV (and tell it)").set_defaults(fn=cmd_unpair)
    s = sub.add_parser("status", help="TV status")
    g = s.add_mutually_exclusive_group()
    g.add_argument("--json", action="store_true")
    g.add_argument("--waybar", action="store_true", help="Waybar / Omarchy custom-module JSON")
    s.set_defaults(fn=cmd_status)
    s = sub.add_parser("key", help="send a remote key: " + " ".join(sorted(KEYS)))
    s.add_argument("key")
    s.set_defaults(fn=cmd_key)
    s = sub.add_parser("launch", help="open an app on the TV by package name")
    s.add_argument("package")
    s.set_defaults(fn=cmd_launch)
    s = sub.add_parser("apps", help="list the TV's apps")
    s.add_argument("--json", action="store_true")
    s.set_defaults(fn=cmd_apps)
    s = sub.add_parser("sync-theme", help="send colours + wallpaper now")
    s.add_argument("--force", action="store_true")
    s.add_argument("--quiet", action="store_true")
    s.set_defaults(fn=cmd_sync)
    s = sub.add_parser("push-widgets", help="send panes/shortcuts from widgets.json")
    s.add_argument("file", nargs="?")
    s.add_argument("--dry-run", action="store_true")
    s.set_defaults(fn=cmd_push_widgets)
    s = sub.add_parser("push-ai", help="collect AI credits (ai.json) and send them")
    s.add_argument("--dry-run", action="store_true")
    s.set_defaults(fn=cmd_push_ai)
    s = sub.add_parser("ai", help="voice assistant: list signed-in AIs, ask a test question")
    aisub = s.add_subparsers(dest="ai_cmd", required=True)
    t = aisub.add_parser("providers", help="AIs the TV can use (signed-in CLIs first, then APIs)")
    t.add_argument("--json", action="store_true")
    aisub.add_parser("providers-push", help="send that list to the TV now")
    t = aisub.add_parser("setup", help="choose the AI-usage bar widget whose numbers the TV mirrors")
    t.add_argument("--list", action="store_true", help="only list the widgets found")
    t.add_argument("--json", action="store_true")
    t.add_argument("--use", help="preset(s) to mirror, e.g. omacash or omacash,omarchy-agents")
    t.add_argument("--include", help="only these rows, in this order (ids or names, comma separated)")
    t.add_argument("--keep-manual", action="store_true", help="keep manual entries already in ai.json")
    t.add_argument("--dry-run", action="store_true")
    t.add_argument("--yes", action="store_true", help="no prompt: take the default")
    t = aisub.add_parser("sources", help="list / enable / disable the bar widgets the TV mirrors")
    t.add_argument("action", nargs="?", default="list", choices=["list", "enable", "disable", "only"])
    t.add_argument("ids", nargs="*", help="source ids, e.g. omacash")
    t.add_argument("--json", action="store_true")
    t = aisub.add_parser("ask", help="ask like the TV would, answer streamed here")
    t.add_argument("text", nargs="+")
    t.add_argument("--provider")
    t.add_argument("--lang")
    s.set_defaults(fn=cmd_ai)
    s = sub.add_parser("office", help="GrokBot Office: hire Claude / ChatGPT bots, their spend (Admin API)",
                       description="OmarchyTV 2.20 GrokBot Office. Hired bots are assistant.json entries (mode 600): "
                                   "the key stays on this PC (env:ANTHROPIC_API_KEY / env:OPENAI_API_KEY by default) and is "
                                   "checked with the provider before the bot is saved. Usage needs an admin key.")
    osub = s.add_subparsers(dest="office_cmd", required=True)
    t = osub.add_parser("hire", help="hire a bot: name, --provider claude|chatgpt, --role, --description, --model")
    t.add_argument("name")
    t.add_argument("--provider", required=True, choices=["claude", "chatgpt"])
    t.add_argument("--role")
    t.add_argument("--description")
    t.add_argument("--model")
    t = osub.add_parser("fire", help="remove a hired bot")
    t.add_argument("name")
    t = osub.add_parser("usage", help="this month's spend per provider (Admin API keys only)")
    t.add_argument("--json", action="store_true")
    t = osub.add_parser("show", help="the office JSON the TV gets from this PC")
    t.add_argument("--json", action="store_true")
    t = osub.add_parser("push", help="send it to the paired Android TV now")
    t.add_argument("--json", action="store_true")
    s.set_defaults(fn=cmd_office)
    s = sub.add_parser("hook", help="add/remove the Omarchy theme-set hook")
    s.add_argument("action", choices=["add", "remove"])
    s.set_defaults(fn=cmd_hook)
    s = sub.add_parser("action", help=argparse.SUPPRESS)
    s.add_argument("id")
    s.add_argument("--confirmed", action="store_true")
    s.set_defaults(fn=cmd_action)
    s = sub.add_parser("session", help="JSON-lines session for the bar widget (also runs as a service)",
                       description="Read JSON lines on stdin, write state on stdout. The bar widget's process "
                                   "owns stdin: when it closes the session ends. For a service (systemd, "
                                   "/dev/null) use --daemon, which ignores EOF and keeps the session up.")
    s.add_argument("--daemon", action="store_true",
                   help="ignore stdin EOF: run until SIGTERM (for systemd, or stdin from /dev/null)")
    s.set_defaults(fn=cmd_session)
    s = sub.add_parser("cast", help="send a link, file, clipboard, text or the screen to the TV",
                       description="Send to the TV. Routes: Google Cast via catt (any Cast TV) first, the OmarchyTV "
                                   "Link channel for text, screen and as fallback. See docs/CAST.md.")
    s.add_argument("target", nargs="?", help="http(s) link (YouTube too) or a local file")
    s.add_argument("--to", help="Cast device name (catt) or the paired TV's name")
    s.add_argument("--via", choices=["auto", "cast", "link"], default="auto")
    s.add_argument("--kind", choices=list(cast.KINDS), help="override the detected kind")
    s.add_argument("--title")
    s.add_argument("--at", help="start position: 90, 1m30s, 1:30 (also the --seek target)")
    s.add_argument("--clipboard", action="store_true", help="send what is on the clipboard")
    s.add_argument("--text", help="show this text on the TV (OmarchyTV only)")
    s.add_argument("--screen", action="store_true", help="EXPERIMENTAL: mirror the screen (OmarchyTV only, 1-3 s latency)")
    s.add_argument("--serve", action="store_true",
                   help="allow a temporary port for local files too big to upload (TV-only, one-time URL)")
    s.add_argument("--wait", action="store_true", help="follow playback state until it ends")
    s.add_argument("--stop", action="store_true", help="stop what is playing")
    s.add_argument("--control", choices=["play", "pause", "seek"], help="control OmarchyTV playback")
    s.add_argument("--devices", action="store_true", help="list Cast devices and the OmarchyTV cast support")
    s.add_argument("--notify", action="store_true", help="desktop notification with the result")
    s.add_argument("--json", action="store_true")
    s.set_defaults(fn=cmd_cast)
    s = sub.add_parser("agents", help="live AI agent activity for the TV (off by default)",
                       description="list | preview | enable [agent…] | disable | hooks [install|remove]")
    s.add_argument("action", nargs="?", default="list",
                   choices=["list", "preview", "enable", "disable", "hooks", "hook", "approve"])
    s.add_argument("args", nargs="*")
    s.add_argument("--json", action="store_true")
    s.set_defaults(fn=cmd_agents)
    s = sub.add_parser("prompt", help="the TV home prompt user@host (sent with the theme)")
    s.add_argument("action", choices=["set", "show", "clear"])
    s.add_argument("user", nargs="?")
    s.add_argument("host", nargs="?")
    s.add_argument("--now", action="store_true", help="send the theme now")
    s.set_defaults(fn=cmd_prompt)
    s = sub.add_parser("x", help="X API for the TV's Living screensaver (mentions): setup | status | forget")
    s.add_argument("action", choices=["setup", "status", "forget"])
    s.add_argument("--username")
    s.set_defaults(fn=cmd_x)
    s = sub.add_parser("liftoff", help="run a build with a rocket countdown on the TV (alarm if it fails)",
                       description="omarchy-link liftoff -- make release   (no shell; exit code passed through)")
    s.add_argument("--name", help="what the TV shows (default: the command)")
    s.add_argument("command", nargs=argparse.REMAINDER)
    s.set_defaults(fn=cmd_liftoff)
    s = sub.add_parser("desktop", help="install \"Send to TV\" launcher / Open-with entries")
    s.add_argument("--remove", action="store_true")
    s.set_defaults(fn=cmd_desktop)
    a = ap.parse_args(argv)
    if not getattr(a, "fn", None):
        ap.print_help()
        return 2
    try:
        cfg = config.load_config()
        return a.fn(a, cfg)
    except link.LinkError as e:
        print(f"omarchy-link: {e}", file=sys.stderr)
        return 1
    except ValueError as e:
        print(f"omarchy-link: {e}", file=sys.stderr)
        return 1
    except KeyboardInterrupt:
        return 130
