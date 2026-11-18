"""Theme + wallpaper sync, shared by the session and the theme-set hook."""

from __future__ import annotations

import fcntl
import re
import time

from . import config, theme


def _lock():
    config.ensure_private_dir(config.RUNTIME_DIR)
    f = open(config.RUNTIME_DIR / "sync.lock", "w")
    fcntl.flock(f, fcntl.LOCK_EX)
    return f


PROMPT_RE = re.compile(r"^[a-z0-9._-]{1,16}$")


def prompt_of(cfg: dict) -> dict | None:
    """config "prompt": {"user", "host"} -> the valid parts, or None (then nothing is sent)."""
    p = cfg.get("prompt")
    if not isinstance(p, dict):
        return None
    out = {k: p[k] for k in ("user", "host") if isinstance(p.get(k), str) and PROMPT_RE.match(p[k])}
    return out or None


def sync_theme(client, cfg: dict, force: bool = False, log=lambda m: None) -> dict | None:
    """Push colours (and wallpaper) if they changed since the last push to this TV."""
    if not cfg.get("themeSync", True) and not force:
        return None
    with _lock():
        fp = theme.fingerprint()
        prompt = prompt_of(cfg)
        if prompt:
            fp = f"{fp}|prompt={prompt.get('user', '')}@{prompt.get('host', '')}"
        last = config.read_json(config.SYNC_FILE, {})
        tv_id = client.tv["id"]
        by_tv = dict(last.get("byTv") or {})
        if not force and (by_tv.get(tv_id) == fp or (last.get("fingerprint") == fp and last.get("tv") == tv_id)):
            return None
        if not theme.has_theme():
            # No Omarchy theme here (macOS, a Linux without Omarchy): nothing to send, the TV keeps its own theme.
            # The fingerprint is still recorded so the session does not come back here every few seconds.
            log("no Omarchy theme on this PC; the TV keeps its own")
            by_tv[tv_id] = fp
            config.write_json_private(config.SYNC_FILE, {**last, "byTv": by_tv})
            return None
        sha = None
        if cfg.get("wallpaperSync", True):
            bg = theme.current_background()
            if bg:
                jpeg, sha = theme.render_wallpaper(bg)
                have = client.wallpaper_info().get("sha256")
                if have != sha:
                    log(f"sending wallpaper {bg.name} ({len(jpeg) // 1024} KiB)")
                    client.put_wallpaper(jpeg)
        payload = theme.theme_payload(sha)
        if prompt:
            payload["prompt"] = prompt
        client.put_theme(payload)
        by_tv[tv_id] = fp
        config.write_json_private(config.SYNC_FILE, {
            "fingerprint": fp, "tv": tv_id, "theme": payload["name"],
            "wallpaper": sha, "at": int(time.time()), "byTv": by_tv})
        log(f"theme {payload['name']} sent")
        return payload
