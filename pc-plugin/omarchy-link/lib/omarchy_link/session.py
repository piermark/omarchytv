"""Long-lived session for the bar widget: JSON lines in (stdin), JSON lines out (stdout).

Out: {"event":"state","state":{...}} after every change, {"event":"log","message":..}.
In:  {"op":"key","key":"home"} | {"op":"launch","package":..} | {"op":"apps"} |
     {"op":"discover"} | {"op":"pair","address":..,"port":..,"code":..} |
     {"op":"unpair"} | {"op":"sync"} | {"op":"refresh"}
One session per shell process (the QML singleton starts it once).
"""

from __future__ import annotations

import json
import os
import shutil
import subprocess
import sys
import threading
import time

from . import ai, assistant, client as link, config, grokbot, mdns, netcheck, panes, stats, sync, theme
from . import liftoff, living, media, omastorm, power

KEYS = {"home", "back", "up", "down", "left", "right", "select", "play_pause", "play", "pause",
        "stop", "next", "previous", "rewind", "fast_forward", "volume_up", "volume_down", "mute",
        "power", "sleep", "wakeup"}


class Session:
    def __init__(self, out=sys.stdout):
        self.out = out
        self.assistant = assistant.Assistant()
        self.last_providers = None
        self.out_lock = threading.Lock()
        self.cfg = config.load_config()
        self.pairing = config.Pairing.load()
        self.client: link.Client | None = None
        self.stream: link.EventStream | None = None
        self.stop = threading.Event()
        self.wake = threading.Event()
        self.state = {
            "paired": self.pairing.paired, "connected": False, "tv": self._tv_public(),
            "foreground": None, "screensaver": False, "homeVisible": False, "screenOn": True,
            "capabilities": [], "apps": [], "devices": [], "discovering": False, "pairing": False,
            "error": "", "lastSync": config.read_json(config.SYNC_FILE, {}).get("theme"),
        }
        self.last_pane = 0.0
        self.last_ai = 0.0
        self.last_fp = None
        self.agent_snap: dict | None = None    # internal (has session ids / cwd), never sent as is
        self.agent_sig = None
        self.agent_sent = 0.0
        self.agent_put = 0.0
        self.agent_was_on = False
        self.cfg_mtime = 0.0
        self.grokbot_sig = None

    # ---------------------------------------------------------------- io
    def emit(self, event: str, **data) -> None:
        line = json.dumps({"event": event, **data}, separators=(",", ":"))
        with self.out_lock:
            try:
                self.out.write(line + "\n")
                self.out.flush()
            except (BrokenPipeError, ValueError):
                self.stop.set()

    def publish(self, **changes) -> None:
        self.state.update(changes)
        self.emit("state", state=self.state)

    def log(self, message: str) -> None:
        self.emit("log", message=message)

    def _tv_public(self) -> dict | None:
        tv = self.pairing.tv
        return {k: tv.get(k) for k in ("id", "name", "address", "port")} if tv else None

    # -------------------------------------------------------- connection
    def _client(self) -> link.Client | None:
        if not self.pairing.paired:
            return None
        if self.client is None or self.client.tv is not self.pairing.tv:
            self.client = link.Client(self.pairing.tv, self.pairing.pc_id,
                                      not self.cfg.get("allowOtherSubnets"))
        return self.client

    def _relocate(self) -> bool:
        """The TV's DHCP lease changed: find it again by id over mDNS."""
        tv = self.pairing.tv
        for dev in mdns.browse(2.0):
            if dev.get("id") == tv["id"] and dev.get("address") and \
                    (dev["address"], dev["port"]) != (tv["address"], tv.get("port")):
                if not netcheck.is_lan_address(dev["address"]):
                    continue
                tv.update(address=dev["address"], port=dev["port"])
                self.pairing.set_tv(tv)
                self.client = None
                self.log(f"TV moved to {dev['address']}:{dev['port']}")
                return True
        return False

    def connection_loop(self) -> None:
        backoff = 1.0
        while not self.stop.is_set():
            c = None
            try:
                c = self._client()
            except ValueError as e:
                self.publish(error=str(e))
            if not c:
                self.wake.wait(5)
                self.wake.clear()
                continue
            try:
                st = c.status()
                self.publish(connected=True, error="", tv=self._tv_public(), **self._status_fields(st))
                self.stream = c.events()
                backoff = 1.0
                self.after_connect(c)
                while not self.stop.is_set():
                    self.handle_event(self.stream.recv())
            except (link.LinkError, OSError, ValueError) as e:
                if isinstance(e, link.LinkError) and e.status == 401 and e.code == "unauthorized":
                    self.log("the TV no longer knows this PC; pair again")
                    self.pairing.forget()
                    self.client = None
                    self.publish(paired=False, connected=False, tv=None, error="TV forgot this PC")
                    continue
                self.publish(connected=False, error=str(e)[:160])
            except Exception as e:  # noqa: BLE001 - never let the reconnect thread die (TV restart, network drop)
                self.log(f"connection error: {e!r}")
                self.publish(connected=False, error=str(e)[:160])
            finally:
                if self.stream:
                    self.stream.close()
                    self.stream = None
            if self.pairing.paired:
                try:
                    if self._relocate():
                        continue
                except OSError:
                    pass
            self.wake.wait(backoff)
            self.wake.clear()
            backoff = min(backoff * 2, 60)

    def _status_fields(self, st: dict) -> dict:
        return {
            "foreground": st.get("foreground"), "screensaver": bool(st.get("screensaver")),
            "homeVisible": bool(st.get("homeVisible")), "screenOn": st.get("screenOn", True),
            "capabilities": st.get("capabilities", []),
        }

    def after_connect(self, c: link.Client) -> None:
        threading.Thread(target=self._initial_push, args=(c,), daemon=True).start()

    def _initial_push(self, c: link.Client) -> None:
        try:
            self.publish(apps=c.apps())
            self.do_sync(force=False)
            widgets = panes.load_widgets()
            pane_list = list(widgets.get("panes", []))
            if self.cfg.get("pcPane", True):
                pane_list.insert(0, panes.pc_pane())
            c.put_widgets(panes.validate({**widgets, "panes": pane_list}, self.cfg.get("actions", {})))
            self.last_pane = time.time()
            self.push_ai(c)
            self.push_providers(c, force=True)
            self.saver_mtime = None
            self.push_home(c)
            self.grokbot_sig = None
            self.push_grokbot(c)
            self.liftoff_sig = None
            self.living_sig = None
            self.send_wol_info()
        except (link.LinkError, OSError, ValueError, RuntimeError, subprocess.SubprocessError) as e:
            self.log(f"initial push: {e}")

    def handle_event(self, msg: dict) -> None:
        kind, data = msg.get("type"), msg.get("data") or {}
        if kind in ("hello", "status"):
            self.publish(**self._status_fields(data))
        elif kind == "apps":
            self.publish(apps=data.get("apps", []))
        elif kind == "shortcut":
            self.run_action(str(data.get("command", "")), confirmed=False)
        elif kind == "theme_set":
            self.handle_theme_set(data)
        elif kind == "omastorm":   # v2.14 Omastorm radar window from this PC to the TV
            self.handle_omastorm(data)
        elif kind == "media_list":   # Cinemarchy browses this PC's media folders (read-only)
            self.handle_media_list(data)
        elif kind == "media_play":   # ...and plays one of their files on the TV
            self.handle_media_play(data)
        elif kind == "living_prefs":
            self._living().set_prefs(data)
            self.living_sig = None
        elif kind == "tv_power":
            power.tv_power(data.get("state"), self.cfg, self.log)
        elif kind == "screen_mirror":
            self.handle_screen_mirror(str(data.get("action", "")))
        elif kind == "unpaired":
            self.pairing.forget()
            self.client = None
            self.publish(paired=False, connected=False, tv=None, error="unpaired on the TV")
        elif kind == "ping" and self.stream:
            self.stream.send("pong", {})
        elif kind == "ai_sources" and self.stream:
            threading.Thread(target=self.handle_ai_sources, args=(self.stream, data), daemon=True).start()
        elif kind == "agent_snapshot" and self.stream:
            self.agent_sig = None  # next tick sends the full list
        elif kind == "agent_ask" and self.stream:
            threading.Thread(target=self.handle_agent_ask, args=(self.stream, data), daemon=True).start()
        elif kind == "ask" and self.stream:
            threading.Thread(target=self.handle_ask, args=(self.stream, data), daemon=True).start()
        elif kind == "grokbot_ask" and self.stream:
            threading.Thread(target=self.handle_grokbot_ask, args=(self.stream, data), daemon=True).start()

    # ------------------------------------------------------------ assistant (TV -> PC "ask")
    def handle_ask(self, stream, data: dict) -> None:
        """Voice question from the TV on the sealed event socket (the PC keeps every port closed).
        Replies: "answer" chunks {id,text,done:false}, then {id,text,done:true,final,...}, or
        "answer_error" {id,error,message}."""
        rid = str(data.get("id", ""))[:64]
        if not rid:
            return
        sent = [0]
        lock = threading.Lock()
        buf, last = [], [time.time()]

        def send(kind: str, payload: dict) -> None:
            try:
                stream.send(kind, {"id": rid, **payload})
            except OSError:
                pass

        def flush(force: bool = False) -> None:
            with lock:  # held while sending so chunks leave in order
                text = "".join(buf)
                if not text or (not force and len(text) < 48 and time.time() - last[0] < 0.15):
                    return
                buf.clear()
                last[0] = time.time()
                text = text[:max(0, assistant.MAX_ANSWER - sent[0])]
                if text:
                    sent[0] += len(text.encode())
                    send("answer", {"text": text, "done": False})

        def on_delta(chunk: str) -> None:
            with lock:
                buf.append(assistant.clean_chunk(chunk))
            flush()

        if not self.cfg.get("assistant", True):
            return send("answer_error", {"error": "disabled", "message": "the assistant is off on the PC"})
        try:
            res = self.assistant.ask(data.get("text", ""), provider=data.get("provider") or None,
                                     conversation=data.get("session") or None, lang=data.get("lang"),
                                     on_delta=None if data.get("stream") is False else on_delta)
            flush(force=True)
            final = res["text"][:assistant.MAX_ANSWER // 4]
            send("answer", {"text": "" if sent[0] else final, "done": True, "final": final,
                            "session": res["conversationId"], "provider": res["provider"],
                            "model": res.get("model")})
        except ValueError as e:
            send("answer_error", {"error": "bad_request", "message": str(e)[:160]})
        except assistant.NoProvider as e:
            send("answer_error", {"error": "no_provider", "message": str(e)[:160]})
        except assistant.RateLimited as e:
            send("answer_error", {"error": "busy", "message": str(e)[:160]})
        except ai.ProviderError as e:
            self.log(f"assistant: {e}")
            send("answer_error", {"error": assistant.error_code(str(e)), "message": str(e)[:200]})

    # ------------------------------------------------------------ live agent monitor (PC -> TV)
    def _reload_cfg(self) -> None:
        try:
            m = config.CONFIG_FILE.stat().st_mtime
        except OSError:
            m = 0.0
        if m != self.cfg_mtime:
            self.cfg_mtime = m
            self.cfg = config.load_config()

    def agent_loop(self) -> None:
        """Every second: rebuild the feed; on change send "agent_activity" (at most 2 per second),
        and keep PUT /v1/agents fresh (every 60 s) for TVs that render from the stored snapshot."""
        from . import agents
        while not self.stop.wait(1.0):
            self._reload_cfg()
            stream = self.stream
            if not stream or not self.state["connected"]:
                continue
            on = agents.settings(self.cfg)["enabled"]
            if not on and not self.agent_was_on:
                continue
            try:
                snap = agents.snapshot(self.cfg)
            except Exception as e:  # noqa: BLE001
                self.log(f"agent monitor: {e}")
                continue
            self.agent_snap = snap
            pub = agents.public(snap)
            sig = json.dumps([{k: v for k, v in a.items() if k != "elapsed"} for a in pub["agents"]] + [on],
                             sort_keys=True)
            now = time.time()
            if sig != self.agent_sig and now - self.agent_sent >= 0.5:
                try:
                    stream.send("agent_activity", pub)
                    self.agent_sig, self.agent_sent = sig, now
                except OSError:
                    continue
            if "agents" in self.state.get("capabilities", []) and (now - self.agent_put > 60 or not on):
                c = self._client()
                try:
                    if c:
                        c.request("PUT", "/v1/agents", pub)
                        self.agent_put = now
                except (link.LinkError, OSError) as e:
                    self.log(f"agents push: {e}")
            self.agent_was_on = on

    def handle_agent_ask(self, stream, data: dict) -> None:
        """TV -> PC "agent_ask" {id, agentId, text}: checks, PC approval, then a headless resume."""
        from . import agents
        from .agents import ask as agent_ask
        rid = str(data.get("id", ""))[:64]

        def send(kind, payload):
            try:
                stream.send(kind, {"id": rid, **payload})
            except OSError:
                pass
        if not rid:
            return
        self._reload_cfg()
        cfg = self.cfg
        try:
            if not agents.settings(cfg)["enabled"]:
                raise agent_ask.AskError("disabled", "the agent monitor is off on the PC")
            snap = agents.snapshot(cfg)
            target = next((a for a in snap["agents"] if a["id"] == str(data.get("agentId", ""))), None)
            text = agent_ask.check(cfg, target, data.get("text"))
            if cfg.get("agentConfirm", True):
                token = agent_ask.request_approval(target, text, os.environ.get("OMARCHY_LINK_BIN", sys.argv[0]))
                send("agent_ask_state", {"agentId": target["id"], "state": "awaiting_approval",
                                         "message": "Confirm on the PC"})
                if not agent_ask.wait_approval(token, stop=self.stop):
                    raise agent_ask.AskError("not_approved", "not confirmed on the PC")
            send("agent_ask_state", {"agentId": target["id"], "state": "running"})
            self.log(f"TV instruction for {target['agentName']} ({target.get('project')})")
            answer = agent_ask.run(cfg, target, text)
            send("agent_answer", {"agentId": target["id"], "text": answer})
            self.agent_sig = None
        except agent_ask.AskError as e:
            send("agent_ask_error", {"agentId": str(data.get("agentId", ""))[:64], "error": e.code,
                                     "message": str(e)[:200]})

    # ------------------------------------------------------------ AI pane sources (TV -> PC "ai_sources")
    def handle_ai_sources(self, stream, data: dict) -> None:
        """TV switched mirror sources. Only known, installed presets can be enabled; reply
        "ai_sources_ok" {id, enabled, sources} then push the refreshed pane, or "ai_sources_error"."""
        from .ai import sources
        rid = str(data.get("id", ""))[:64]
        def send(kind, payload):
            try:
                stream.send(kind, {"id": rid, **payload})
            except OSError:
                pass
        if not self.cfg.get("aiSourcesFromTv", True) or not self.cfg.get("aiCredits", True):
            return send("ai_sources_error", {"error": "disabled", "message": "source switching from the TV is off on the PC"})
        try:
            on = sources.set_enabled(data.get("enabled"), require_available=True)
        except sources.SourceError as e:
            return send("ai_sources_error", {"error": e.code, "message": str(e)[:160]})
        except OSError as e:
            return send("ai_sources_error", {"error": "io", "message": str(e)[:160]})
        self.log(f"TV set AI sources: {', '.join(on) or '(none)'}")
        send("ai_sources_ok", {"enabled": on, "sources": sources.listing()})
        c = self._client()
        if c:
            try:
                self.push_ai(c)
            except (link.LinkError, OSError, ValueError, RuntimeError, subprocess.SubprocessError) as e:
                self.log(f"AI push after source change: {e}")

    # ------------------------------------------------------------ Agent Desktop companion (experimental)
    def handle_grokbot_ask(self, stream, data: dict) -> None:
        """TV -> PC "grokbot_ask" {id, text, bot?}: forwarded to @tcballard's companion (grokbot.py).
        Replies "grokbot_answer" {id, bot, text, mode} or "grokbot_error" {id, error, message}."""
        rid = str(data.get("id", ""))[:64]

        def send(kind, payload):
            try:
                stream.send(kind, {"id": rid, **payload})
            except OSError:
                pass
        if not rid:
            return
        self._reload_cfg()
        if not self.cfg.get("grokbotBridge", True):
            return send("grokbot_error", {"error": "disabled", "message": "the Grok Bot bridge is off on the PC"})
        try:
            res = grokbot.ask(self.cfg, data.get("text"), data.get("bot") or None)
        except grokbot.GrokbotError as e:
            return send("grokbot_error", {"error": e.code, "message": str(e)[:200]})
        self.log(f"TV question for Grok Bot {res['bot']} ({res['mode']})")
        send("grokbot_answer", res)

    def push_grokbot(self, c: link.Client) -> None:
        """Mirror the companion's current bot (name, shape, colour) on the TV tile when its files change.
        An older launcher answers 404/400: ignored."""
        if not self.cfg.get("grokbotBridge", True):
            return
        sig = grokbot.state_signature(self.cfg)
        if sig == self.grokbot_sig:
            return
        self.grokbot_sig = sig
        try:
            c.put_grokbot(grokbot.current(self.cfg))
        except link.LinkError as e:
            if e.status not in (400, 404, 429):
                raise

    def push_providers(self, c: link.Client, force: bool = False) -> None:
        if not self.cfg.get("assistant", True):
            return
        provs = self.assistant.listing()
        if force or provs != self.last_providers:
            c.put_ai_providers(provs)
            self.last_providers = provs

    # ------------------------------------------------------------ actions
    # ------------------------------------------------------------ "Schermo PC" (TV asks to mirror this screen)
    def handle_screen_mirror(self, action: str) -> None:
        """TV top bar monitor icon. start: the PC asks first (desktop notification, click to confirm) unless
        config "screenMirrorFromTv" is "allow"; "off" refuses. Then it runs the existing experimental
        `omarchy-link cast --screen`. stop: ends that process and stops playback on the TV."""
        exe = os.environ.get("OMARCHY_LINK_BIN", sys.argv[0])
        mode = self.cfg.get("screenMirrorFromTv", "confirm")
        proc = getattr(self, "_mirror", None)
        if action == "stop":
            if proc and proc.poll() is None:
                proc.terminate()
            self._mirror = None
            subprocess.Popen([exe, "cast", "--stop", "--via", "link"], stdin=subprocess.DEVNULL,
                             stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, start_new_session=True)
            return
        if action != "start" or mode == "off":
            self.log(f"TV screen mirror request {action!r} ignored (screenMirrorFromTv={mode})")
            return
        if proc and proc.poll() is None:
            return
        argv = [exe, "cast", "--screen", "--via", "link", "--notify"]
        if mode != "allow":
            if shutil.which("omarchy-notification-send"):
                argv = ["omarchy-notification-send", "-g", "󰍹", "OmarchyTV",
                        "The TV asks to mirror this screen. Click to start", "--exec", *argv]
            elif shutil.which("notify-send"):  # libnotify >= 0.7.9: action button, prints the action id
                argv = ["sh", "-c", 'test "$(notify-send -a OmarchyTV -A start=Avvia --wait OmarchyTV '
                        '"La TV chiede di mostrare questo schermo")" = start && exec "$@"', "sh", *argv]
            else:
                self.log("screen mirror from TV needs a notification daemon to confirm; set screenMirrorFromTv")
                return
        try:
            self._mirror = subprocess.Popen(argv, stdin=subprocess.DEVNULL, stdout=subprocess.DEVNULL,
                                            stderr=subprocess.DEVNULL, start_new_session=True)
        except OSError:
            self.log("cannot start screen mirroring (omarchy-notification-send or omarchy-link missing)")

    def send_wol_info(self) -> None:
        """Tell the TV this PC's MAC, so "TV and PC power together" can wake it (Wake-on-LAN from the TV)."""
        tv = self.pairing.tv or {}
        info = power.wol_info(tv.get("address"))
        if info and self.stream and self.cfg.get("powerFromTv", True) is not False:
            if info.get("wifi"):
                self.log("Wake-on-LAN: this PC reaches the TV over Wi-Fi; most PCs only wake over Ethernet")
            self.stream.send("pc_wol", {"mac": info["mac"], "broadcast": info.get("broadcast")})

    def handle_omastorm(self, data: dict) -> None:
        st = getattr(self, "omastorm", None)
        if st is None:
            st = self.omastorm = omastorm.Session(self.log)
        action = data.get("action")
        if action == "start":
            res = st.start(self.cfg)
            if res not in ("ok", "running"):
                self.log(f"Omastorm on the TV: {res}")
                if self.stream:
                    self.stream.send("omastorm_state", {"state": res})
        elif action == "key":
            st.key(str(data.get("key", "")))
        elif action == "stop":
            st.stop()

    def handle_media_list(self, data: dict) -> None:
        if not self.stream:
            return
        if self.cfg.get("mediaFromTv", True) is False:
            self.stream.send("media_list", {"path": "", "dirs": [], "files": [], "error": "disabled"})
            return
        try:
            res = media.listing(str(data.get("path", ""))[:1024])
        except Exception as e:  # noqa: BLE001 - a broken folder must not stop the session
            self.log(f"media list: {e}")
            res = {"path": str(data.get("path", "")), "dirs": [], "files": [], "error": "unreadable"}
        self.stream.send("media_list", res)

    def handle_media_play(self, data: dict) -> None:
        if self.cfg.get("mediaFromTv", True) is False or not self.client:
            return
        p = media.resolve(str(data.get("path", ""))[:1024])
        if p is None or not p.is_file() or not media.kind_of(p.name):
            if self.stream:
                self.stream.send("media_list", {"path": str(data.get("path", "")), "dirs": [], "files": [], "error": "not_found"})
            return
        from . import cast   # noqa: PLC0415 - only when the TV asks for a file
        client = self.client

        def run():
            try:
                item = cast.classify(str(p), title=p.stem)
                pos = data.get("position")
                if isinstance(pos, (int, float)) and pos > 0:
                    item["position"] = float(pos)
                cast.via_link(client, item, serve_ok=True, wait=True, out=self.log)
            except Exception as e:  # noqa: BLE001
                self.log(f"media play {p.name}: {e}")
        threading.Thread(target=run, daemon=True).start()

    def _living(self) -> living.Living:
        lv = getattr(self, "living", None)
        if lv is None:
            lv = self.living = living.Living(lambda: self.cfg, self.log)
        return lv

    def push_living(self) -> None:
        """Living screensaver feed (notifications, GitHub, X) for the TV, on every change, only once it asked."""
        lv = getattr(self, "living", None)
        if lv is None or not self.stream or not lv.wanted():
            return
        lv.set_active(bool(self.state.get("screensaver")))
        pub = lv.payload()
        sig = json.dumps(pub, sort_keys=True)
        if sig != getattr(self, "living_sig", None):
            self.living_sig = sig
            self.stream.send("living", pub)

    def push_pulse(self) -> None:
        """2.15 Pulse on the TV: while the TV's screensaver is on, this PC's Pulse state (private parts removed), every 2 s."""
        if not self.stream or self.cfg.get("pulseToTv", True) is False or not self.state.get("screensaver"):
            return
        now = time.time()
        if now - getattr(self, "pulse_at", 0) < 2:
            return
        self.pulse_at = now
        from . import pulse_state   # noqa: PLC0415
        st = pulse_state.read()
        if st:
            self.stream.send("pulse", st)

    def push_agents(self) -> None:
        """2.15: an AI agent that starts waiting for you (Pulse's agents, state "waiting"), told to the TV once.

        The TV shows it only when it opted in (Settings › Grok Bot e AI). Every 3 s, from the local Pulse hub; nothing
        when Pulse isn't running. While the screensaver is on, the TV's Pulse shows the agents anyway: no alert then.
        """
        if not self.stream or self.cfg.get("agentAlertToTv", True) is False:
            return
        now = time.time()
        if now - getattr(self, "agents_at", 0) < 3:
            return
        self.agents_at = now
        from . import pulse_state   # noqa: PLC0415
        st = pulse_state.read()
        waiting = pulse_state.waiting(st)
        told = getattr(self, "agents_told", None)
        self.agents_told = set(waiting)
        if told is None or self.state.get("screensaver"):   # the first look only learns who already waits
            return
        for key, a in waiting.items():
            if key not in told:
                self.stream.send("agent", {"name": a["name"], "text": a["text"]})

    def push_liftoff(self) -> None:
        """A build/update countdown for the TV: on every change, and every 5 s while it runs."""
        if not self.stream or self.cfg.get("liftoffToTv", True) is False:
            return
        pub = liftoff.public(liftoff.read_state())
        sig = None if pub is None else (pub["id"], pub["state"], pub["stage"], pub["pct"], pub["elapsedS"] // 5)
        if sig == getattr(self, "liftoff_sig", None):
            return
        self.liftoff_sig = sig
        if pub is not None:
            self.stream.send("liftoff", pub)

    def handle_theme_set(self, data: dict) -> None:
        """Reverse theme sync: the TV changed theme; follow it on the PC (themeFromTv, default on)."""
        if self.cfg.get("themeFromTv", True) is False:
            self.log("TV asked to change the PC theme; themeFromTv is off")
            return
        name = data.get("name")
        res = theme.set_theme(name)
        if res != "ok":
            self.log(f"TV asked for theme {str(name)[:64]!r}: {res}")

    def run_action(self, action_id: str, confirmed: bool) -> None:
        actions = self.cfg.get("actions", {})
        act = actions.get(action_id)
        if not isinstance(act, dict) or not isinstance(act.get("exec"), list):
            self.log(f"TV asked for unknown PC action {action_id!r}; ignored")
            return
        if act.get("confirm", True) and not confirmed:
            label = act.get("label", action_id)
            argv = ["omarchy-notification-send", "-g", "󰔂", "OmarchyTV", f"Run \"{label}\"? Click to confirm",
                    "--exec", os.environ.get("OMARCHY_LINK_BIN", sys.argv[0]), "action", action_id, "--confirmed"]
            try:
                subprocess.Popen(argv, stdin=subprocess.DEVNULL, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
            except OSError:
                self.log("cannot ask for confirmation (omarchy-notification-send missing); action refused")
            return
        subprocess.Popen(act["exec"], stdin=subprocess.DEVNULL, stdout=subprocess.DEVNULL,
                         stderr=subprocess.DEVNULL, start_new_session=True)

    def do_sync(self, force: bool) -> None:
        c = self._client()
        if not c or not self.state["connected"]:
            return
        try:
            p = sync.sync_theme(c, self.cfg, force, self.log)
            if p:
                self.publish(lastSync=p["name"])
        except (link.LinkError, OSError, RuntimeError, subprocess.SubprocessError, ValueError) as e:
            self.log(f"theme sync failed: {e}")

    def push_ai(self, c: link.Client) -> None:
        self.last_ai = time.time()
        if not self.cfg.get("aiCredits", True):
            return
        data = ai.payload()
        if data["ai"] or data["aiSources"] or config.AI_FILE.exists():
            c.put_ai(data)

    def push_home(self, c: link.Client) -> None:
        """Home: stats every statsIntervalS (default 5 s) only while the TV reports homeVisible (CPU, RAM,
        now playing, deploys), screensaver.txt when it changes. Errors from an older launcher (404) are ignored."""
        every = max(2, int(self.cfg.get("statsIntervalS", 5)))
        if self.cfg.get("homeStats", True) and self.state["homeVisible"] and time.time() - getattr(self, "last_stats", 0) >= every:
            self.last_stats = time.time()
            try:
                c.put_stats(stats.payload(self.cfg))
            except link.LinkError as e:
                if e.status not in (404, 429):
                    raise
        if self.cfg.get("screensaverSync", True):
            text, mtime = stats.screensaver_text()
            if mtime != getattr(self, "saver_mtime", None):
                try:
                    c.put_screensaver(text)
                    self.saver_mtime = mtime
                except link.LinkError as e:
                    if e.status not in (404, 429):
                        raise

    def ticker(self) -> None:
        """Every 1 s (stats gate: statsIntervalS); every 3 s: notice theme/background changes (bg-next has no hook)."""
        tick = 0
        while not self.stop.wait(1):
            tick += 1
            if not self.state["connected"]:
                continue
            try:
                self.push_liftoff()
                self.push_living()
                self.push_pulse()
                self.push_agents()
            except (OSError, ValueError) as e:
                self.log(f"liftoff/living push failed: {e}")
            if tick % 3:
                c = self._client()
                try:
                    if c:
                        self.push_home(c)
                except (link.LinkError, OSError, ValueError) as e:
                    self.log(f"push failed: {e}")
                continue
            fp = theme.fingerprint()
            if fp != self.last_fp:
                if self.last_fp is not None:
                    self.do_sync(force=False)
                self.last_fp = fp
            c = self._client()
            try:
                if c and self.cfg.get("pcPane", True) and self.state["homeVisible"] and time.time() - self.last_pane > 60:
                    c.put_widgets(panes.validate({**panes.load_widgets(), "panes": [panes.pc_pane()] +
                                                  list(panes.load_widgets().get("panes", []))},
                                                 self.cfg.get("actions", {})))
                    self.last_pane = time.time()
                if c:
                    self.push_home(c)
                    self.push_grokbot(c)
                minutes = max(5, int(self.cfg.get("aiRefreshMinutes", 15)))
                if c and time.time() - self.last_ai > minutes * 60:
                    self.push_ai(c)
                    self.push_providers(c)
            except (link.LinkError, OSError, ValueError) as e:
                self.log(f"push failed: {e}")

    # ------------------------------------------------------------- input
    def handle_op(self, req: dict) -> None:
        op = req.get("op")
        c = self._client()
        try:
            if op == "key":
                key = str(req.get("key", ""))
                if key not in KEYS:
                    raise ValueError(f"unknown key {key}")
                if not c:
                    raise ValueError("not paired")
                c.key(key)
            elif op == "launch":
                if not c:
                    raise ValueError("not paired")
                c.launch(str(req.get("package", "")))
            elif op == "apps" and c:
                self.publish(apps=c.apps())
            elif op == "refresh" and c:
                self.publish(**self._status_fields(c.status()))
            elif op in ("cast", "cast_stop"):
                # Share to TV runs as its own process: it may serve a file or follow playback for a while
                exe = os.environ.get("OMARCHY_LINK_BIN", sys.argv[0])
                argv = [exe, "cast", "--notify"]
                if op == "cast_stop":
                    argv.append("--stop")
                elif req.get("target"):
                    argv.append(str(req["target"])[:4096])
                else:
                    argv.append("--clipboard")
                subprocess.Popen(argv, stdin=subprocess.DEVNULL, stdout=subprocess.DEVNULL,
                                 stderr=subprocess.DEVNULL, start_new_session=True)
            elif op == "sync":
                self.do_sync(force=True)
            elif op == "discover":
                self.publish(discovering=True, error="")
                devs = mdns.browse(2.5)
                self.publish(discovering=False, devices=devs,
                             error="" if devs else "No TV found. Open “Connect Omarchy Linux” on the TV, or enter its IP.")
            elif op == "pair":
                self.pair(str(req.get("address", "")), int(req.get("port") or 47800), str(req.get("code", "")))
            elif op == "unpair":
                if c:
                    try:
                        c.unpair()
                    except link.LinkError:
                        pass
                self.pairing.forget()
                self.client = None
                if self.stream:
                    self.stream.close()
                self.publish(paired=False, connected=False, tv=None, apps=[], foreground=None)
            else:
                return
            self.publish(error="")
        except (link.LinkError, ValueError, OSError) as e:
            self.publish(error=str(e)[:160], discovering=False, pairing=False)

    def pair(self, address: str, port: int, code: str) -> None:
        netcheck.check_host(address, not self.cfg.get("allowOtherSubnets"))
        self.publish(pairing=True, error="")
        info = link.probe(address, port)
        tv = link.pair(address, port, code, self.pairing.pc_id, self.pairing.pc_name)
        tv["name"] = tv.get("name") or info.get("name")
        self.pairing.set_tv(tv)
        self.client = None
        self.publish(paired=True, pairing=False, tv=self._tv_public())
        self.wake.set()

    def stdin_loop(self, wait_for_eof: bool = True) -> None:
        """Handle JSON lines from stdin until it is closed.

        A closed stdin normally means the widget that spawned us is gone, so the session ends. With ``--daemon``
        (systemd, /dev/null) EOF is ignored and the session keeps running, which is what #79 asked for.
        """
        for line in sys.stdin:
            line = line.strip()
            if not line:
                continue
            try:
                req = json.loads(line)
            except ValueError:
                req = {"op": "key", "key": line}  # plain "home", "back"... like the TV remote plugins
            threading.Thread(target=self.handle_op, args=(req,), daemon=True).start()
        if wait_for_eof:
            self.stop.set()
            self.wake.set()
            return
        # #79 --daemon: stdin belongs to a service, not to a widget, so its EOF says nothing: stay up.
        self.log("stdin chiuso: la sessione resta attiva (SIGTERM o Ctrl-C per fermarla)")
        while not self.stop.wait(3600):
            pass   # the other loops do the work; this thread just keeps the process alive

    def run(self, daemon: bool = False) -> int:
        self.publish()
        threading.Thread(target=self.connection_loop, daemon=True).start()
        threading.Thread(target=self.ticker, daemon=True).start()
        threading.Thread(target=self.agent_loop, daemon=True).start()
        self.stdin_loop(wait_for_eof=not daemon)
        return 0
