"""v2.21 agents on LG / Samsung: what the PC bridge (omarchy-tv webos-serve) keeps for the web app on the TV.

The TV app polls GET /v1/inbox every few seconds (with the ask code, X-Omarchy-Token) and shows what is new:
notifications, a page to open full screen, questions to answer with the remote. The same routes as on an Android TV
(POST /v1/notify, POST /v1/page, POST /v1/ask, GET /v1/ask/<id>?wait=), so `omarchy-tv mcp --bridge`, the
`omarchy-tv notify|page|ask` commands and scripts work the same for every TV. Memory only: nothing on disk.
"""

from __future__ import annotations

import ipaddress
import secrets
import threading
import time
from urllib.parse import urlparse

LEVELS = ("info", "warn", "alert")


def clean(s, n: int) -> str:
    return "".join(ch for ch in str(s or "").replace("\n", " ") if ch.isprintable()).strip()[:n]


def lan_url(url: str, allow_tailnet: bool = True) -> bool:
    """http:// to a literal private (or tailnet) address: what the TV may open full screen."""
    try:
        u = urlparse(url)
        if u.scheme != "http" or not u.hostname or len(url) > 2000:
            return False
        ip = ipaddress.ip_address(u.hostname)
    except ValueError:
        return False
    if ip.version == 4 and allow_tailnet and ip in ipaddress.ip_network("100.64.0.0/10"):
        return True
    return ip.is_private and not ip.is_loopback


class Inbox:
    MAX_NOTES, MAX_ASKS = 20, 4

    def __init__(self, now=time.time):
        self._now = now
        self._lock = threading.Condition()
        self.seq = 0
        self.notes: list = []
        self.page = None
        self.asks: dict = {}

    def notify(self, text: str, title: str = "", level: str = "info", source: str = "PC", speak: bool = False) -> dict:
        text, title = clean(text, 200), clean(title, 60)
        if not text and not title:
            raise ValueError("text is required")
        with self._lock:
            self.seq += 1
            self.notes.append({"seq": self.seq, "from": clean(source, 40) or "PC", "title": title, "text": text,
                               "level": level if level in LEVELS else "info", "speak": bool(speak), "at": int(self._now())})
            self.notes = self.notes[-self.MAX_NOTES:]
            self._lock.notify_all()
            return {"ok": True, "shown": True}

    def set_page(self, url: str | None, by: str = "PC") -> dict:
        if url and not lan_url(url):
            raise ValueError("not_lan_url")
        with self._lock:
            self.seq += 1
            self.page = {"url": url, "by": clean(by, 40), "seq": self.seq} if url else None
            return {"ok": True, "url": url}

    def ask(self, question: str, choices=None, timeout: int = 120, source: str = "PC") -> dict:
        q = clean(question, 200)
        if not q:
            raise ValueError("question is required")
        ch = []
        for c in choices or []:
            c = clean(c, 24)
            if c and c not in ch:
                ch.append(c)
        ch = ch[:4] or ["Yes", "No"]
        timeout = max(10, min(300, int(timeout or 120)))
        with self._lock:
            now = self._now()
            for k in [k for k, a in self.asks.items() if a["deadline"] + 600 < now]:
                del self.asks[k]
            if sum(1 for a in self.asks.values() if self._state(a) == "pending") >= self.MAX_ASKS:
                raise OverflowError("busy")
            aid = secrets.token_hex(8)
            self.seq += 1
            self.asks[aid] = {"id": aid, "from": clean(source, 40) or "PC", "question": q, "choices": ch,
                              "deadline": now + timeout, "answer": None, "seq": self.seq}
            return self._public(self.asks[aid])

    def _state(self, a) -> str:
        return "answered" if a["answer"] is not None else "expired" if self._now() >= a["deadline"] else "pending"

    def _public(self, a) -> dict:
        return {"id": a["id"], "from": a["from"], "question": a["question"], "choices": a["choices"],
                "state": self._state(a), "answer": a["answer"], "expires": int(a["deadline"])}

    def answer(self, aid: str, choice: str) -> bool:
        with self._lock:
            a = self.asks.get(aid)
            if not a or self._state(a) != "pending" or choice not in a["choices"]:
                return False
            a["answer"] = choice
            self._lock.notify_all()
            return True

    def get_ask(self, aid: str, wait: float = 0) -> dict | None:
        end = time.monotonic() + max(0.0, min(25.0, wait))
        with self._lock:
            a = self.asks.get(aid)
            if not a:
                return None
            while self._state(a) == "pending":
                left = min(end - time.monotonic(), a["deadline"] - self._now())
                if left <= 0:
                    break
                self._lock.wait(left)
            return self._public(a)

    def since(self, seq: int) -> dict:
        """What the TV has not seen: notes after `seq`, the page, the questions still waiting."""
        with self._lock:
            return {"seq": self.seq, "notes": [n for n in self.notes if n["seq"] > seq], "page": self.page,
                    "asks": [self._public(a) for a in self.asks.values() if self._state(a) == "pending"]}


INBOX = Inbox()
