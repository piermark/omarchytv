"""Voice assistant relay. The TV sends recognised text over the link; the PC asks an
AI that is already signed in here (Claude Code, Codex, Gemini CLI, Grok CLI,
ollama) or, as a fallback, an API configured in assistant.json, and streams the
reply back. The TV never talks to an AI service and never sees a credential.

Provider module interface (assistant/providers/<type>.py):
    KIND = "cli" | "local" | "api"
    LABEL = "Claude"                          default display name
    def available(cfg) -> dict | None         {"model": ...} when usable now
    def ask(cfg, req: Request) -> str         call req.delta(text) while streaming; return full text
"""

from __future__ import annotations

import collections
import importlib
import ipaddress
import json
import os
import re
import secrets
import shutil
import signal
import socket
import subprocess
import tempfile
import threading
import time
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path

from .. import config
from ..ai import Context, ProviderError

ASSISTANT_FILE = config.CONFIG_DIR / "assistant.json"
TYPE_RE = re.compile(r"^[a-z][a-z0-9_]{0,31}$")
ID_RE = re.compile(r"^[a-z0-9][a-z0-9._-]{0,31}$")
CONV_RE = re.compile(r"^[A-Za-z0-9._-]{1,64}$")
MAX_TEXT = 2000
MAX_ANSWER = 16 * 1024          # bytes of answer text per question (TV limit)
MIN_INTERVAL = 3.0              # seconds between questions (TV sends at most 1 per 3 s)
AUTO_ORDER = ["claude_code", "codex", "grok_cli", "grok_bot", "hermes_cli", "gemini_cli", "ollama"]

SYSTEM_PROMPT = (
    "You are the voice assistant of a living-room TV. Your reply is shown on the TV and read aloud. "
    "Answer in the user's language, in at most three short sentences (about 60 words), plain text only: "
    "no markdown, no lists, no code, no URLs, no emoji. If the question needs a long answer, give the gist "
    "and offer to go on. You cannot see the screen, use tools, run commands or browse files; never try to. "
    "If you do not know something current, say so briefly."
)


class Request:
    def __init__(self, text: str, history: list, system: str, lang: str | None, on_delta, timeout: float,
                 ctx: Context):
        self.text, self.history, self.system, self.lang = text, history, system, lang
        self.on_delta, self.timeout, self.ctx = on_delta, timeout, ctx
        self.cancel = threading.Event()

    def delta(self, text: str) -> None:
        if text and self.on_delta:
            self.on_delta(text)

    def messages(self) -> list:
        """OpenAI-style message list without the system prompt."""
        return [*self.history, {"role": "user", "content": self.text}]

    def transcript(self) -> str:
        """History plus the new question, without the system prompt."""
        lines = []
        if self.lang:
            lines.append(f"(The user speaks {self.lang}.)")
        if self.history:
            lines.append("Conversation so far:")
            lines += [("User: " if m["role"] == "user" else "Assistant: ") + m["content"] for m in self.history]
            lines.append("New question from the user:")
        lines.append(self.text)
        return "\n".join(lines)

    def flat_prompt(self) -> str:
        """For CLIs: system prompt, short transcript and the new question in one text."""
        parts = [self.system]
        if self.lang:
            parts.append(f"The user speaks {self.lang}.")
        if self.history:
            parts.append("Conversation so far:")
            for m in self.history:
                parts.append(("User: " if m["role"] == "user" else "Assistant: ") + m["content"])
        parts.append("User: " + self.text)
        parts.append("Reply as the assistant, following the rules above.")
        return "\n\n".join(parts)


# ---------------------------------------------------------------- memory
class Memory:
    """Short-term memory per conversation: last `turns` exchanges, forgotten after `ttl` s."""

    def __init__(self, turns: int = 6, ttl: float = 900, max_convs: int = 32, max_chars: int = 6000):
        self.turns, self.ttl, self.max_convs, self.max_chars = turns, ttl, max_convs, max_chars
        self.convs: "collections.OrderedDict[str, tuple[float, list]]" = collections.OrderedDict()
        self.lock = threading.Lock()

    def _gc(self, now: float) -> None:
        for k in [k for k, (t, _) in self.convs.items() if now - t > self.ttl]:
            del self.convs[k]
        while len(self.convs) > self.max_convs:
            self.convs.popitem(last=False)

    def get(self, conv: str) -> list:
        with self.lock:
            self._gc(time.time())
            hit = self.convs.get(conv)
            return list(hit[1]) if hit else []

    def add(self, conv: str, user: str, assistant: str) -> None:
        with self.lock:
            now = time.time()
            msgs = list(self.convs.pop(conv, (now, []))[1])
            msgs += [{"role": "user", "content": user}, {"role": "assistant", "content": assistant}]
            msgs = msgs[-2 * self.turns:]
            while sum(len(m["content"]) for m in msgs) > self.max_chars and len(msgs) > 2:
                msgs = msgs[2:]
            self.convs[conv] = (now, msgs)
            self._gc(now)

    def forget(self, conv: str | None = None) -> None:
        with self.lock:
            if conv is None:
                self.convs.clear()
            else:
                self.convs.pop(conv, None)


# ---------------------------------------------------------------- registry
def load_type(kind: str):
    if not TYPE_RE.match(kind or ""):
        raise ProviderError(f"bad assistant provider type {kind!r}")
    try:
        return importlib.import_module(f"{__name__}.providers.{kind}")
    except ImportError as e:
        raise ProviderError(f"unknown assistant provider type {kind!r}") from e


def load_settings(path: Path | None = None) -> dict:
    path = path or ASSISTANT_FILE
    s = config.read_json(path, {})
    return s if isinstance(s, dict) else {}


def configured(settings: dict) -> list[dict]:
    """Provider configs in priority order: explicit entries (CLI types first, then
    API), then auto-detected signed-in CLIs not already listed."""
    entries = [dict(p) for p in settings.get("providers", []) if isinstance(p, dict) and p.get("enabled") is not False]
    for p in entries:
        p.setdefault("id", p.get("type", "").replace("_", "-"))
    if settings.get("autoDetect", True):
        have = {p.get("type") for p in entries}
        for t in AUTO_ORDER:
            if t not in have and t not in settings.get("disabled", []):
                entries.append({"type": t, "id": t.replace("_", "-"), "auto": True})

    def rank(p):
        try:
            return {"cli": 0, "local": 1, "api": 2}.get(load_type(p.get("type", "")).KIND, 3)
        except ProviderError:
            return 3
    return sorted(entries[:16], key=rank)


def available(settings: dict) -> list[dict]:
    out, seen = [], set()
    for p in configured(settings):
        if not ID_RE.match(str(p.get("id", ""))) or p["id"] in seen:
            continue
        try:
            mod = load_type(p["type"])
            info = mod.available(p)
        except ProviderError:
            info = None
        if not info:
            continue
        seen.add(p["id"])
        out.append({"id": p["id"], "name": str(p.get("name") or mod.LABEL)[:32], "kind": mod.KIND,
                    "model": info.get("model"), "streaming": bool(getattr(mod, "STREAMING", False)),
                    "_cfg": p, "_mod": mod})
    return out


def public(providers: list[dict]) -> list[dict]:
    return [{k: v for k, v in p.items() if not k.startswith("_")} for p in providers]


class Assistant:
    """One per PC session. Thread-safe; one ask at a time per conversation."""

    def __init__(self, settings_path: Path | None = None):
        self.settings_path = settings_path
        self.memory = Memory()
        self._providers: tuple[float, list] | None = None
        self.busy = threading.Semaphore(2)
        self.recent: collections.deque = collections.deque(maxlen=20)

    def settings(self) -> dict:
        s = load_settings(self.settings_path)
        mem = s.get("memory", {}) if isinstance(s.get("memory"), dict) else {}
        self.memory.turns = max(0, min(int(mem.get("turns", 6)), 20))
        self.memory.ttl = max(60, min(int(mem.get("minutes", 15)) * 60, 86400))
        return s

    def providers(self, fresh: bool = False) -> list[dict]:
        if fresh or not self._providers or time.time() - self._providers[0] > 60:
            self._providers = (time.time(), available(self.settings()))
        return self._providers[1]

    def listing(self) -> dict:
        """Body of PUT /v1/ai/providers: {"providers":[{id,name,kind,model,streaming}], "default"}."""
        provs = self.providers(fresh=True)
        default = self.settings().get("default", "auto")
        ids = [p["id"] for p in provs]
        return {"providers": public(provs), "default": default if default in ids else (ids[0] if ids else None)}

    def ask(self, text: str, provider: str | None = None, conversation: str | None = None,
            lang: str | None = None, on_delta=None) -> dict:
        text = " ".join(str(text or "").split())
        if not text:
            raise ValueError("empty text")
        if len(text) > MAX_TEXT:
            raise ValueError(f"text longer than {MAX_TEXT} characters")
        conversation = conversation or secrets.token_hex(8)
        if not CONV_RE.match(conversation):
            raise ValueError("bad conversationId")
        if lang is not None and not re.match(r"^[A-Za-z]{2,3}(-[A-Za-z0-9]{2,8})?$", str(lang)):
            lang = None
        settings = self.settings()
        interval = max(0.0, min(float(settings.get("minIntervalSeconds", MIN_INTERVAL)), 60.0))
        now = time.time()
        while self.recent and now - self.recent[0] > 60:
            self.recent.popleft()
        if len(self.recent) >= 20 or (self.recent and now - self.recent[-1] < interval):
            raise RateLimited("too many questions, wait a moment")
        provs = self.providers()
        if not provs:
            raise NoProvider("no AI is signed in on the PC (see omarchy-link ai providers)")
        want = provider or settings.get("default", "auto")
        chosen = next((p for p in provs if p["id"] == want), None)
        if chosen is None:
            if provider:
                raise NoProvider(f"provider {provider!r} is not available on the PC")
            chosen = provs[0]
        if not self.busy.acquire(timeout=1):
            raise RateLimited("the PC is still answering")
        self.recent.append(now)
        try:
            ai_file = self.settings_path or ASSISTANT_FILE
            ctx = Context(ai_file_private=ai_file.exists() and config.is_private_file(ai_file),
                          timeout=float(settings.get("timeoutSeconds", 90)))
            system = str(settings.get("systemPrompt") or SYSTEM_PROMPT)[:4000]
            if chosen["_cfg"].get("system"):   # 2.20 GrokBot Office: a hired bot's name and role come first
                system = str(chosen["_cfg"]["system"])[:1500] + "\n\n" + system
            req = Request(text, self.memory.get(conversation), system, lang, on_delta, ctx.timeout, ctx)
            reply = clean_reply(chosen["_mod"].ask(chosen["_cfg"], req))
            if not reply:
                raise ProviderError("empty answer")
        finally:
            self.busy.release()
        self.memory.add(conversation, text, reply)
        return {"conversationId": conversation, "provider": chosen["id"], "model": chosen.get("model"),
                "text": reply}


def error_code(message: str) -> str:
    """Maps a provider failure to the TV's answer_error codes."""
    m = message.lower()
    if "timed out" in m or "timeout" in m:
        return "timeout"
    if any(w in m for w in ("limit", "quota", "429", "credit", "usage")):
        return "quota"
    return "provider_error"


class RateLimited(Exception):
    pass


class NoProvider(Exception):
    pass


def clean_chunk(text: str) -> str:
    """Markdown markers removed from a streamed chunk (they can span chunks, so only characters)."""
    return re.sub(r"[*`#]+", "", text)


def clean_reply(text: str, limit: int = 4000) -> str:
    """Strip the markdown CLIs like to add; the TV shows and speaks plain text."""
    t = re.sub(r"```.*?```", "", str(text or ""), flags=re.S)
    t = re.sub(r"[*`#>]+", "", t)
    t = re.sub(r"\[([^\]]+)\]\([^)]+\)", r"\1", t)
    return re.sub(r"[ \t]+", " ", t).strip()[:limit]


# ---------------------------------------------------------------- helpers for provider modules
def run_cli(argv: list[str], req: Request, on_line=None, env_extra: dict | None = None) -> str:
    """Runs a signed-in CLI in an empty private temp dir (no project files to read),
    stdin closed, its own process group (killed on timeout). Returns stdout."""
    with tempfile.TemporaryDirectory(prefix="omarchy-link-ask-") as cwd:
        env = {**os.environ, "NO_COLOR": "1", "CI": "1", **(env_extra or {})}
        proc = subprocess.Popen(argv, cwd=cwd, env=env, stdin=subprocess.DEVNULL, stdout=subprocess.PIPE,
                                stderr=subprocess.PIPE, text=True, start_new_session=True)
        timer = threading.Timer(req.timeout, lambda: _kill(proc))
        timer.start()
        out, err = [], []
        t_err = threading.Thread(target=lambda: err.append(proc.stderr.read(65536)), daemon=True)
        t_err.start()
        try:
            for line in proc.stdout:
                out.append(line)
                if on_line:
                    on_line(line)
                if sum(map(len, out)) > 4 << 20:
                    _kill(proc)
                    raise ProviderError("CLI output too large")
            proc.wait()
        finally:
            timer.cancel()
            if proc.poll() is None:
                _kill(proc)
        t_err.join(1)
        if proc.returncode != 0:
            msg = ("".join(err).strip().splitlines() or [f"exit {proc.returncode}"])[-1]
            if proc.returncode < 0:
                msg = "timed out"
            raise ProviderError(f"{Path(argv[0]).name}: {msg[:160]}")
        return "".join(out)


def _kill(proc) -> None:
    try:
        os.killpg(proc.pid, signal.SIGKILL)
    except (OSError, ProcessLookupError):
        pass


def check_url(url: str) -> None:
    """https anywhere; plain http only to loopback or the LAN (local model servers)."""
    u = urllib.parse.urlparse(url)
    if u.scheme == "https":
        return
    if u.scheme != "http" or not u.hostname:
        raise ProviderError("provider URLs must be https (or http on this PC / the LAN)")
    try:
        infos = socket.getaddrinfo(u.hostname, u.port or 80, proto=socket.IPPROTO_TCP)
    except OSError as e:
        raise ProviderError(f"cannot resolve {u.hostname}") from e
    for info in infos:
        ip = ipaddress.ip_address(info[4][0].split("%")[0])
        if not (ip.is_loopback or ip.is_private or ip.is_link_local):
            raise ProviderError("plain http is only allowed to this PC or the local network")


def stream_http(url: str, headers: dict, body: dict, req: Request, on_line) -> None:
    """POST JSON and feed each response line to on_line (SSE or NDJSON)."""
    check_url(url)
    data = json.dumps(body).encode()
    r = urllib.request.Request(url, data=data, method="POST", headers={
        "User-Agent": "omarchy-link/0.1", "Content-Type": "application/json", **headers})
    try:
        with urllib.request.urlopen(r, timeout=req.timeout) as resp:
            total = 0
            for raw in resp:
                total += len(raw)
                if total > 4 << 20:
                    raise ProviderError("response too large")
                on_line(raw.decode("utf-8", "replace").rstrip("\r\n"))
    except urllib.error.HTTPError as e:
        detail = ""
        try:
            err = json.loads(e.read(65536)).get("error", "")
            detail = err.get("message", "") if isinstance(err, dict) else str(err)
        except (ValueError, AttributeError, OSError):
            pass
        raise ProviderError(f"HTTP {e.code} {detail}".strip()[:200]) from e
    except (urllib.error.URLError, OSError) as e:
        raise ProviderError(str(e)[:200]) from e


def which(cfg: dict, default: str) -> str | None:
    return shutil.which(cfg.get("bin") or default)


def key_ready(ref) -> bool:
    """Cheap check that a key reference can resolve, without reading it."""
    ref = str(ref or "")
    if ref.startswith("env:"):
        return bool(os.environ.get(ref[4:]))
    if ref.startswith("file:"):
        return Path(os.path.expanduser(ref[5:])).is_file()
    return bool(ref)


def json_lines(text_or_line: str):
    for line in text_or_line.splitlines():
        line = line.strip()
        if line.startswith("{"):
            try:
                yield json.loads(line)
            except ValueError:
                continue
