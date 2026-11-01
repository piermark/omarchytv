# Omarchy Link protocol, version 1

The wire contract between an Omarchy PC (plugin `com.omarchytv.link`) and the
OmarchyTV launcher (`com.omarchytv.home`). The PC side and a mock TV
(`mock/mock_tv.py`) implement this document. The TV launcher implements the
**TV** column. `docs/vectors.json` has byte-exact test vectors. The mock does not enforce the rate limits (§3), except `pair/*` with `pair_limit=True`, or the 5-minute pairing window. Everything else it does. MUST, SHOULD and
MAY are used as in RFC 2119.

## 1. Roles and why the TV is the server

| | TV (launcher) | PC (Omarchy) |
|---|---|---|
| Role | HTTP + WebSocket **server**, TCP **47800** | client |
| Advertises | mDNS `_omarchytv._tcp` | nothing, opens no port (except an opt-in, temporary, TV-only one for `cast --serve` / `--screen`, §6 Cast) |
| Shows | IP + 6-digit code (logo menu → *Connect Omarchy Linux*) | asks for the code |
| Holds | paired PCs + tokens (internal storage) | token (`~/.local/state/omarchy-link/pairing.json`, 0600) |

The TV is the server because:

1. **Omarchy's firewall denies incoming traffic by default** (`ufw default deny
   incoming`, `install/config/firewall.sh`). A PC-side server would force every
   user to open a port. As a client the PC needs no firewall change.
2. The TV is the fixed, always-on device that shows the code and its IP, so the
   user types where they already have a keyboard: on the PC.
3. Laptops sleep and roam. The PC reconnects when it comes back, and the TV never
   has to chase a PC.
4. One TV can serve several PCs (up to 8 paired).

This supersedes the "PC runs the daemon" sketch in `launcher/PLUGINS.md`.

## 2. Discovery

The TV registers through `NsdManager` while the launcher process is alive:

- type `_omarchytv._tcp`, port `47800` (from `MainActivity.LINK_PORT`), instance name = the TV's friendly name
- TXT: `v=1` (protocol), `id=<tvId>` (32 hex chars, random at first start, never changes),
  `name=<friendly name>`, `pair=1` while the pairing screen is open, otherwise `pair=0`

Nothing else goes in TXT: no model, no serial, no paired-PC data.

The PC sends a PTR query from an ephemeral port (legacy unicast, RFC 6762 §6.7).
Android's mDNS responder and Avahi answer it. **Manual fallback:** the user types
the IP shown on the TV, and the PC calls `GET /v1/info`.

When a paired TV's DHCP address changes, the PC finds it again by `id` over
mDNS and updates its stored address. The token stays valid.

## 3. Transport and limits

- HTTP/1.1 and RFC 6455 WebSocket on the same port. JSON is UTF-8. No TLS in v1
  (see §9). Every message that needs integrity is MACed.
- The TV MUST accept connections only from its own subnet (check the peer
  address against the active network's `LinkProperties` prefix). Loopback is
  only for the mock. Anything else gets `403 {"error":"not_lan"}`, then the
  connection closes. The TV SHOULD bind to the LAN address, not every interface.
- Size caps: JSON bodies 64 KiB, wallpaper 8 MiB, cast uploads `castUploadMax` (Status; 64 MiB
  suggested, 256 MiB at most), WebSocket messages 1 MiB.
  Larger requests get `413`.
- Rate limits per peer IP: `pair/*` 1 request per 2 s. Authenticated requests
  20/s. `PUT /v1/wallpaper` 1 per 10 s. `PUT /v1/widgets`, `PUT /v1/ai` and
  `PUT /v1/ai/providers` 1 per 5 s each. `POST /v1/cast` 1 per s, `PUT /v1/cast/file` 1 per 5 s. WebSocket `ask` 1 per 3 s (TV side, also enforced by the PC).
  Over the limit gets `429` with `Retry-After`.
- Every response carries `X-OTV-Time: <unix seconds>` and `Cache-Control: no-store`.
- Errors are `{"error": "<code>", "message": "<human text>"}`.

## 4. Pairing (SPAKE2, no secret on the wire)

The 6-digit code never leaves the screen, and the token is never transmitted.
Both sides derive the token with SPAKE2 (RFC 9382 construction) over the
**RFC 3526 2048-bit MODP group** (group 14: `p` safe prime, `q=(p-1)/2`,
`g=2`). This group needs only `BigInteger.modPow` on Android 8 and Python's
`pow`, with no elliptic-curve library.

A passive sniffer learns nothing. An active attacker gets **one guess of the
code per attempt**, and the TV allows 5 failed attempts per code.

### Constants

```
M = H2G("OmarchyTV-Link SPAKE2 M v1")   N = H2G("OmarchyTV-Link SPAKE2 N v1")
H2G(label) = ( int_be( SHA256(label||0x00) || SHA256(label||0x01) || … || SHA256(label||0x08) ) mod p )^2 mod p
w = int_be( SHA256("OTV1 spake2 w" || 0x00 || code_ascii) ) mod q        (code = exactly 6 digits)
```

Group elements are sent as **256-byte big-endian**, base64url without padding (`b64u`).
On receipt, an element `X` MUST satisfy `1 < X < p-1` and `X^q mod p == 1`.
Otherwise answer `400`.

### Flow

```
PC                                                     TV (pairing screen open)
x ←R [1,q-1];  T = g^x · M^w
POST /v1/pair/start {protocol:1, pcId, pcName, t:b64u(T)}  ──►
                                                       check T; y ←R [1,q-1]; S = g^y · N^w
                                                       K = (T · M^(q-w))^y
                                 ◄── 200 {pairingId, tvId, tvName, s:b64u(S), expiresIn:60}
check S; K = (S · N^(q-w))^x
POST /v1/pair/confirm {pairingId, confirm:b64u(cPC)} ──►
                                                       cPC ok? store PC, rotate code
                                 ◄── 200 {confirm:b64u(cTV), tvId}  | 401 {error:"bad_code", attemptsLeft}
check cTV (else: "not the real TV")
```

`pcId` is 8–64 alphanumeric characters, random per PC install. `pcName` is at most 40 characters (the hostname).

### Key schedule

```
TT   = LP("pc:"+pcId) || LP("tv:"+tvId) || LP(T) || LP(S) || LP(K) || LP(w)   LP(x) = uint64_le(len(x)) || x
                                                                           T,S,K,w as 256-byte BE
h    = SHA256(TT);   Ke = h[0:16];   Ka = h[16:32]
cPC  = HMAC(HMAC(Ka, "OTV1 confirm pc"), h)
cTV  = HMAC(HMAC(Ka, "OTV1 confirm tv"), h)
token = HKDF-SHA256(ikm=Ke, salt=h, info="OTV1 token", L=32)
```

### TV rules

- `pair/*` answers **only while the Connect Omarchy Linux page is visible**
  (and at most 5 minutes after it opened). Otherwise it returns
  `403 {"error":"pairing_closed"}`.
- One pending pairing at a time. It expires after 60 s, and a new `start` replaces it.
- After 5 wrong confirmations, generate a new code and show it. After a success,
  also generate a new code: **a code pairs one PC**.
- Compare MACs in constant time (`MessageDigest.isEqual`).
- Store `{pcId → name, token, pairedAt, lastSeen, lastAddress}` in **internal
  storage** (`getFilesDir()` or `MODE_PRIVATE` SharedPreferences). The
  public `omarchytv.json` only gets `link.pcs: [{name, address, lastSeen}]`.

## 5. Authenticated requests

Every request except `GET /v1/info` and `POST /v1/pair/*` MUST carry:

```
Authorization: OTV1 pc=<pcId>, ts=<unix seconds>, nonce=<b64u 16 random bytes>, sig=<b64u HMAC>
sig = HMAC-SHA256(token, "OTV1\n" + METHOD + "\n" + target + "\n" + ts + "\n" + nonce + "\n" + hex(SHA256(body)))
```

`target` is the request target exactly as sent (path and query). The body is the
raw body bytes (empty for GET).

The TV:
1. Looks up `pc`. If unknown, `401 unauthorized`.
2. `|now − ts| ≤ 120 s`. If not, `401 clock_skew`. The PC then retries once with
   its offset taken from `X-OTV-Time`.
3. Checks `sig` in constant time. If wrong, `401 unauthorized`.
4. Rejects a `(pc, nonce)` seen in the last 240 s with `401 replay`.

**Every response to an authenticated request is signed**, errors included
(except the four 401s above):

```
X-OTV-Sig: b64u(HMAC-SHA256(token, "OTV1-R\n" + nonce + "\n" + status + "\n" + hex(SHA256(responseBody))))
```

The PC rejects unsigned or badly signed answers, so a spoofed TV cannot report
fake status or swallow commands.

## 6. Endpoints

| Method & path | Auth | Body → Response |
|---|---|---|
| `GET /v1/info` | none | → `{service:"omarchytv-link", protocol:1, tvId, name, pairingOpen}`. Nothing else. |
| `POST /v1/pair/start` | none, pairing window | §4 |
| `POST /v1/pair/confirm` | none, pairing window | §4 |
| `GET /v1/status` | ✓ | → Status (below) |
| `GET /v1/apps` | ✓ | → `{apps:[{package,label,dock:bool}]}` launcher order, hidden apps excluded |
| `POST /v1/apps/launch` | ✓ | `{package}` → `{ok:true}` / `404 no_such_app`. Only packages with a leanback launcher intent. |
| `POST /v1/remote/key` | ✓ | `{key}` → `{ok:true}` / `400 bad_key` / `409 {error:"capability_missing"}` |
| `PUT /v1/theme` | ✓ | Theme → `{ok:true}` / `409 unknown_wallpaper` |
| `GET /v1/wallpaper` | ✓ | → `{sha256: <hex of current pushed JPEG> \| null}` |
| `PUT /v1/wallpaper` | ✓ | `Content-Type: image/jpeg`, ≤ 8 MiB, ≤ 3840x2160 → `{sha256}` / `415 not_jpeg` |
| `PUT /v1/widgets` | ✓ | Widgets → `{ok:true}` |
| `PUT /v1/ai` | ✓ | AI credits → `{ok:true}` |
| `PUT /v1/ai/providers` | ✓ | Assistant providers → `{ok:true}` (§6, Assistant) |
| `PUT /v1/agents` | ✓ | Agent activity snapshot → `{ok:true}` (§6, Agents) |
| `GET /v1/agents` | ✓ | → the last stored snapshot `{agents:[…], at}` |
| `POST /v1/cast` | ✓ | Cast → `{id, state:"opening", app}` (§6, Cast) |
| `PUT /v1/cast/file` | ✓ | raw bytes, `Content-Type` = media type, ≤ `castUploadMax` → `{file:<sha256 hex>, size}` |
| `POST /v1/cast/control` | ✓ | `{action:"play"\|"pause"\|"stop"\|"seek", id?, position?}` → `{ok:true}` |
| `POST /v1/unpair` | ✓ | `{}` → `{ok:true}`. The TV deletes this PC. |
| `GET /v1/events` | ✓ (on the upgrade) | WebSocket, §7 |

Anything else returns `404` (signed).

### Status

```json
{ "tvId": "…", "name": "Sony KD-55XE9005", "protocol": 1, "launcherVersion": "2.1",
  "foreground": {"package": "com.netflix.ninja", "label": "Netflix", "source": "usage-stats"},
  "homeVisible": false, "screensaver": false, "screenOn": true, "time": 1790000000,
  "capabilities": ["theme","wallpaper","widgets","ai","ai-ask","apps","launch","events",
                   "key:home","key:play_pause","key:next","key:previous","key:volume_up","key:volume_down","key:mute","key:back"] }
```

- `foreground`: from `UsageStatsManager` when the user granted Usage access
  (`source:"usage-stats"`). Otherwise it is the last app the launcher itself
  opened, or the launcher while resumed (`source:"launcher"`). `null` when unknown.
- `screensaver`: `Intent.ACTION_DREAMING_STARTED` / `STOPPED` (registered at
  runtime), or `OmarchyDream` start/stop.
- `capabilities` lists only what works right now. The PC greys out the rest.

### Remote keys

| key | Android implementation | capability |
|---|---|---|
| `home` | `startActivity` of the launcher (`FLAG_ACTIVITY_NEW_TASK \| CLEAR_TOP`) | always |
| `play_pause` `play` `pause` `stop` `next` `previous` `rewind` `fast_forward` | `AudioManager.dispatchMediaKeyEvent` (down+up) | always |
| `volume_up` `volume_down` `mute` | `AudioManager.adjustSuggestedStreamVolume(ADJUST_RAISE/LOWER/TOGGLE_MUTE, USE_DEFAULT_STREAM_TYPE, FLAG_SHOW_UI)` | always |
| `back` (and later `up/down/left/right/select`) | `AccessibilityService.performGlobalAction(GLOBAL_ACTION_BACK)`. Needs the user to enable an "OmarchyTV remote" accessibility service. | only while enabled |
| `power` `sleep` `wakeup` | not in v1 (needs system privileges or HDMI-CEC) | never |

A normal app cannot inject key events into other apps (`INJECT_EVENTS` is
signature-only), so this is the exact set the launcher can honour.

### Theme (`PUT /v1/theme`)

```json
{ "name": "tokyo-night", "mode": "dark", "accent": "#7aa2f7", "accent2": "#bb9af7",
  "colors": { "background": "#1a1b26", "foreground": "#a9b1d6", "…": "24 keys, #rrggbb" },
  "wallpaper": { "sha256": "e3b1…" } }
```

Colours are Omarchy's `colors.toml`, resolved like `omarchy-theme-color --all`.
`accent` = `accent`, and `accent2` = `bright_magenta`, falling back to `magenta`
(Tokyo Night gives exactly the launcher defaults `#7aa2f7` / `#bb9af7`).
The PC sends `PUT /v1/wallpaper` first and skips it when `GET /v1/wallpaper`
already reports the same sha256. When `wallpaper.sha256` is not the
last stored JPEG, the TV returns `409 unknown_wallpaper`.

The TV then performs one `LauncherConfig.update()`:

```json
"theme": { "name": "tokyo-night", "mode": "dark", "accent": "#7aa2f7", "accent2": "#bb9af7",
           "colors": { … }, "source": "omarchy-link" },
"wallpaper": "/sdcard/Android/data/com.omarchytv.home/files/link/wallpaper-e3b1249fb5d4.jpg"
```

**Optional `prompt`** (launcher ≥ 2.7.1): `"prompt": {"user": "user", "host": "omarchy"}`.
- Both parts are optional. Each is lowercase `[a-z0-9._-]`, at most 16 characters.
- The TV sanitises each part and merges the non-empty ones into top-level `"prompt"` in `omarchytv.json`, which drives the home prompt `user@omarchy:~$`.
- The PC sends it only when `config.json` has `"prompt"` (`omarchy-link prompt set <user> [host]`). Otherwise the key is absent and the TV keeps what it has.

(keep only the newest `link/wallpaper-*.jpg`). The launcher already reloads on
mtime change, and today it reads `theme.accent/accent2` and an absolute
`wallpaper` path. `colors` and `mode` are for later use (bar, tiles, screensaver
palette).

### Widgets (`PUT /v1/widgets`)

```json
{ "panes": [ { "id": "pc", "title": "omarchy", "icon": "omarchy", "ttl": 180,
               "lines": [ {"label":"CPU","value":"12%"}, {"label":"RAM","value":"41% of 32 GB"},
                          {"label":"♪","value":"Daft Punk - Veridis Quo"} ] } ],
  "shortcuts": [ { "id": "lofi", "label": "Lofi", "icon": "",
                   "action": {"type":"view","uri":"https://www.youtube.com/watch?v=jfKfPfyJRdk","package":"com.google.android.youtube.tv"} },
                 { "id": "netflix", "label": "Netflix", "action": {"type":"app","package":"com.netflix.ninja"} },
                 { "id": "lock", "label": "Lock PC", "action": {"type":"pc","command":"lock"} } ] }
```

Limits: 8 panes × 8 lines, title ≤ 48, icon ≤ 16, label ≤ 16 and value ≤ 64 printable characters,
16 shortcuts. `id` matches `^[a-z0-9][a-z0-9._-]{0,47}$`. The TV stores the
lists in `link.panes` / `link.shortcuts` (plus `link.widgetsAt`) and
renders text only, never HTML. A pane is stale after `ttl` seconds: dim it, or
hide it after 2×ttl. The PC pushes `pc` every 60 s, only while `homeVisible`.

Shortcut actions on the TV:
- `app`: launch `package`.
- `view`: `ACTION_VIEW` on `uri`, which must be `https://`, `vnd.youtube:` or
  `nflx://`. Use `setPackage(package)` when given. Never `intent:`/`file:`/`content:`.
- `pc`: send the event `{"type":"shortcut","data":{"command":"lock"}}` to the
  PC (§7). The PC runs only commands defined in its own `config.json`, and by
  default asks for confirmation with a desktop notification. The TV never sends
  a command line.

### AI credits (`PUT /v1/ai`)

```json
{ "ai": [ { "name": "Grok API", "used": 7.0, "limit": 25.0, "unit": "$", "resetsAt": null,
            "updatedAt": "2026-10-19T08:00:00Z", "remaining": 18.0, "provider": "xai", "status": "ok" },
          { "name": "Claude Max week", "used": 42, "limit": 100, "unit": "%",
            "resetsAt": "2026-10-23T09:00:00Z", "updatedAt": "…", "status": "manual" } ] }
```

The required fields are `name, used, limit, unit, resetsAt, updatedAt`. `used`,
`limit` and `resetsAt` may be `null`. `unit` is `"$"`, `"tokens"`, `"%"`, `"credits"`, or an
ISO currency code. The optional fields are `remaining`, `provider`,
`status` (`ok|error|manual`) and `error` (short text). Entries mirrored from a PC bar
widget (`provider: "mirror"`) may also carry `id`, `source` (widget name), `detail` (the
widget's own one-line text, e.g. `"$96.60 used of $120.00"`), `severity`
(`ok|low|critical|unknown`), `percentRemaining` (0..1), `stale` and `estimated` (booleans).
A TV that wants to look identical to the PC should prefer `detail` when present.
Unknown fields must be ignored. The list has at most 16
entries. **Canonical storage: the TV's Link service writes the list verbatim to
the top-level `"ai"` key of `omarchytv.json`, plus `"aiAt"` (unix seconds).** That
is where `LauncherConfig` already reads it (first 6 entries, AI pane). There is no `link.ai`.
`used`/`limit` may be JSON `null` ("unknown"). The TV MUST show those as "?" and not as 0
(`optDouble` turns null into NaN, so check `isNull`). Optional extra fields (`source`, `resetsText`,
`observedAt`) can be ignored. **API keys never reach the TV**:
the PC collects (docs/AI-CREDITS.md) and pushes numbers only. Most entries come from
AI tools already signed in on the PC (Claude Code `/usage`, Codex logs), without any API key.

**Sources (optional, same body):** when the PC mirrors bar widgets, `PUT /v1/ai` also carries

```json
"aiSources": [ {"id":"omarchy-agents","name":"Omarchy Agents panel","enabled":true,"available":true,"inBar":true},
               {"id":"omacash","name":"Omacash","enabled":false,"available":true,"inBar":false} ]
```

These are the AI-usage widgets found on the PC that the AI pane can mirror (at most 16; `id` is
a fixed preset name: `omarchy-agents`, `omacash`, `agent-usage`, `claudebar`, `ai-usagebar`,
`model-usage`). Enabled ones come first, in the order their rows appear. `available:false`
means the source is enabled on the PC but the widget was not found there. The TV stores the
list as top-level **`aiSources`** in `omarchytv.json`, next to `ai`, and MAY show one switch
per entry in the AI pane options. An `aiSources` field in a `PUT /v1/ai` body is how the TV
knows the PC accepts the `ai_sources` event. Without it, the TV must not show switches.

**Event `ai_sources`** (TV → PC), sent when the user flips a switch. `enabled` is the complete
new list of enabled ids, in display order, not a delta:

```json
{"type":"ai_sources","data":{"id":"a1b2…","enabled":["omarchy-agents","omacash"]}}
```

The PC replies with **`ai_sources_ok`** `{"id","enabled","sources"}` (`sources` has the same
shape as `aiSources`), then pushes a fresh `PUT /v1/ai` with the new rows and `aiSources`.
On failure it replies **`ai_sources_error`** `{"id","error","message"}`, where `error` is one of:
- `bad_request`: not a list of at most 8 strings;
- `unknown_source`: not a known preset;
- `not_installed`: the widget is not on the PC;
- `disabled`: the user turned remote switching off with `"aiSourcesFromTv": false`;
- `io`.

The TV should then revert the switch and show `message`. The TV can only switch known presets
that are already installed. It can never add commands, files, paths or keys. The PC keeps every
other option in its `ai.json` (`include`, manual entries, …). An empty list is allowed and
empties the pane.

### Agents (live AI agent activity)

The PC watches its AI coding agents (docs/AGENTS.md) and tells the TV what they are doing. This is off on
the PC until the user enables it. The TV advertises capability `agents` once it stores `PUT /v1/agents`.

**Activity list** (the same shape everywhere: `agent_activity` event data, `PUT /v1/agents` body, `GET /v1/agents`):

```json
{ "at": "2026-10-19T08:12:03Z", "enabled": true,
  "agents": [ { "id": "claude-code:7d0c6a2e-111", "agent": "claude-code", "agentName": "Claude",
                "project": "shop", "title": null, "status": "waiting", "step": "Read: .env",
                "since": "2026-10-19T08:11:58Z", "startedAt": "2026-10-19T08:02:10Z",
                "updatedAt": "2026-10-19T08:11:58Z", "elapsed": 5,
                "needsInput": true, "question": "Allow Read: .env?", "canAsk": true,
                "steps": [ {"at": "2026-10-19T08:11:40Z", "text": "Bash: npm test", "status": "error"},
                           {"at": "2026-10-19T08:11:58Z", "text": "Read: .env", "status": "running"} ] } ] }
```

| field | meaning |
|---|---|
| `id` | opaque, stable per session. Use it in `agent_ask`. |
| `agent` | `claude-code` \| `codex` \| `grok` \| `hermes` \| `gemini` \| `grok-bot` (more may come) |
| `status` | `thinking` \| `running` (a tool) \| `waiting` (needs the user) \| `idle` \| `done` \| `error` |
| `step` | current step, ≤ 72 characters, already sanitised. Plain text only. |
| `since` / `elapsed` | when the current step began, and seconds at send time. The TV counts up from `since`. |
| `needsInput`, `question` | `question` is ≤ 160 characters, plain text |
| `canAsk` | the PC may accept `agent_ask` for this agent (still subject to the PC's opt-in) |
| `steps` | the last ≤ 8 steps: `status` `running` \| `ok` \| `error` |

At most 8 agents, sorted waiting → running → thinking → error → done → idle. `enabled:false` with
an empty list means the user turned the monitor off: hide the widget.

**Event `agent_activity`** (PC → TV):
- sent on every change, at most **2 per second**;
- always the full list, not a delta;
- `elapsed` changes alone do not trigger a send.

**Event `agent_snapshot`** (TV → PC, `{}`): asks for an immediate full `agent_activity`, e.g. when the widget opens.

**`PUT /v1/agents`:** the same list, once on connect and every 60 s. The TV stores it as `link.agents` and `link.agentsAt`, and answers `GET /v1/agents` with it.

**Event `agent_ask`** (TV → PC), an instruction typed or spoken on the TV:

```json
{"type":"agent_ask","data":{"id":"b71e…","agentId":"claude-code:7d0c6a2e-111","text":"now run the e2e tests"}}
```

`text` is 1-2000 characters. The PC answers on the same socket, echoing `id`:
- **`agent_ask_state`** `{id, agentId, state}`:
  - `awaiting_approval`: the user must confirm on the PC (the default). Show "Confirm on your PC".
  - `running`.
- **`agent_answer`** `{id, agentId, text}`: the agent's reply, redacted, ≤ 1200 characters, plain text.
- **`agent_ask_error`** `{id, agentId, error, message}`, where `error` is one of:

| `error` | meaning |
|---|---|
| `disabled` | the PC has not enabled instructions from the TV, or the monitor is off |
| `unsupported` | this agent cannot take input (e.g. Grok Bot), or it is waiting for a terminal answer |
| `busy` | the agent is working; ask again when it is done |
| `not_found` | no such active session |
| `not_approved` | the user did not confirm on the PC within 2 minutes |
| `cannot_confirm` | the PC cannot show its confirmation |
| `bad_request` | empty or too-long text |
| `timeout` / `failed` | the run failed |

Show `message`. The PC never auto-approves the agent's tool permissions because of a TV instruction, unless
the user explicitly enabled that on the PC.

### What the TV launcher must implement (agents)

**Protocol**
- [ ] Capability `agents`. Store `PUT /v1/agents` (≤ 8 agents, ≤ 64 KiB) as `link.agents` and `link.agentsAt`; `GET /v1/agents` returns it.
- [ ] Handle the `agent_activity` event; it replaces the whole list. Send `agent_snapshot` when the widget becomes visible.

**Widget**
- [ ] One row per agent: name, project, status chip, `step`, and a timer counting up from `since`.
- [ ] Expand to the last `steps`.
- [ ] `needsInput` gets a highlighted row with `question` and a "Answer on your PC" hint.
- [ ] Hide it when `enabled` is false or the list is empty.
- [ ] Render every string as plain text.
- [ ] Optional: a screensaver/notification when an agent switches to `waiting`.

**Ask**
- [ ] Show an "Ask" action only when `canAsk` is true and the status is `done`/`idle`.
- [ ] Input is the remote microphone (`SpeechRecognizer`, as for `> ask`) or the on-screen keyboard. Send `agent_ask` with a fresh `id`.
- [ ] Show `agent_ask_state` (`awaiting_approval` → "Confirm on your PC"), then `agent_answer` (TTS optional), or `agent_ask_error.message`.
- [ ] Rate-limit it to 1 per 3 s like `ask`.

**Security**
- [ ] Never send anything but `text` (no tool approvals, no commands, no paths).

### Cast ("Send to TV")

The PC sends something for the TV to show now. The launcher advertises it in Status:
- capability `cast`;
- capability `cast-upload` when it accepts `PUT /v1/cast/file`;
- `castKinds`, the kinds it can open right now;
- `castUploadMax`, in bytes.

The PC never sends a kind that is not listed. A launcher without `cast` keeps working: the PC
uses Google Cast instead (docs/CAST.md).

**`POST /v1/cast`** (PC → TV):

```json
{ "id": "9f2c4e1ab0d37a55", "kind": "youtube", "videoId": "dQw4w9WgXcQ",
  "url": "https://www.youtube.com/watch?v=dQw4w9WgXcQ", "title": "YouTube", "position": 90, "from": "omarchy" }
```

| field | rules |
|---|---|
| `id` | 1-64 characters. It names this cast in `cast_state` and `control`. |
| `kind` | `url` \| `youtube` \| `video` \| `audio` \| `image` \| `text` |
| `url` | `http`/`https` only, ≤ 4096 characters. Refuse anything else (`intent:`, `file:`, `content:`, `javascript:`). |
| `videoId` | `youtube` only, `^[A-Za-z0-9_-]{11}$` |
| `file` | a sha256 returned by `PUT /v1/cast/file`, instead of `url` |
| `mime` | media type hint, e.g. `video/mp4`, `video/mp2t`, `application/vnd.apple.mpegurl` |
| `title` | ≤ 200 characters, shown on the player or viewer |
| `position` | start offset in seconds (video/audio/youtube) |
| `text` | `text` only, ≤ 32 KiB UTF-8, rendered as **plain text** |
| `live` | `true` for a live stream (screen): no seek bar, small buffer, no "ended" at the end of buffered data |
| `size`, `from` | informational (bytes; PC hostname) |

What the launcher opens (`app` in the answer):

| kind | app | how |
|---|---|---|
| `youtube` | `youtube` | `Intent(ACTION_VIEW, "https://www.youtube.com/watch?v=<videoId>")` with `t`/`position`, package `com.google.android.youtube.tv`. Fall back to `vnd.youtube:<videoId>` (YouTube TV 5.30 accepts it but does not play it) |
| `video`, `audio` | `player` | the launcher's own player activity: Media3/ExoPlayer, or `VideoView` as a minimum. Title overlay, Omarchy colours, D-pad play/pause/seek, back to stop. |
| `image` | `viewer` | full-screen viewer on the theme background, the title in the theme font |
| `text` | `text` | a full-screen card in the theme colours, large font, scrollable, no links or HTML |
| `url` | `browser` | `Intent(ACTION_VIEW, url)` with `CATEGORY_BROWSABLE`; `409 {"error":"no_app"}` when nothing handles it |

Errors (all signed):
- `400 bad_cast` (bad or missing field);
- `415 unsupported_kind`;
- `404 no_such_file`;
- `409 capability_missing`;
- `409 no_app`;
- `429`.

A new cast replaces the one playing; send `cast_state stopped` for the old one.

**`PUT /v1/cast/file`:** raw body bytes, at most `castUploadMax`; anything larger gets `413`. The TV:
- stores the file under `getCacheDir()/cast/<sha256>`, never in shared storage;
- answers `{file: sha256, size}`. The PC checks that the hash matches its own;
- keeps at most a few files or 512 MiB, deleting the oldest first;
- deletes each one within 24 h.

**Event `cast_state`** (TV → PC), on every change:

```json
{"type":"cast_state","data":{"id":"9f2c4e1ab0d37a55","state":"playing","position":12.5,"duration":3600}}
```

- `state` is one of `opening`, `buffering`, `playing`, `paused`, `ended`, `stopped` (user, control or replaced) or `error`, with `error` holding a short text.
- `position` and `duration` are in seconds and optional.
- Send it at least on each state change and every 10 s while playing.
- The PC stops its temporary server on `ended`, `stopped` or `error`.

**Local files the TV fetches from the PC** (opt-in on the PC, `url` like
`http://192.168.1.20:41234/cast/<token>/film.mkv`): the PC answers only the TV's address and
supports `Range` (use it for seeking). The URL works only while the cast lasts. The TV must not
store it or show it to other apps.

**`POST /v1/cast/control`:** `{action, id?, position?}`, where `id` defaults to the current cast and `seek` takes `position` in seconds. The reply is `{ok:true}`, or `409 {"error":"nothing_playing"}`.

### What the TV launcher must implement (cast)

**Protocol**
- [ ] Status: add `cast` and `cast-upload` to `capabilities`, plus `castKinds` (only the kinds that work, e.g. drop `youtube` if the YouTube app is missing) and `castUploadMax`.
- [ ] `POST /v1/cast` with the validation above, including the http/https-only URL rule and `videoId` regex. Answer `{id, state:"opening", app}` quickly and open the app on the main thread.
- [ ] `PUT /v1/cast/file`: stream it to `getCacheDir()/cast/<sha256>` while hashing, and enforce `castUploadMax` before reading. Add the quota and 24 h cleanup.
- [ ] `POST /v1/cast/control`: play, pause, stop and seek on the current player. `stop` also closes the viewer or text card.
- [ ] Event `cast_state` on the PC sockets: on every change, every 10 s while playing, and `stopped` when replaced or when the user presses Back.

**Apps it opens**
- [ ] YouTube intent with a fallback.
- [ ] Player activity: Media3/ExoPlayer is recommended (HLS, DASH, MKV, MPEG-TS) and `VideoView` is the minimum.
  - For `live:true`, use a small `DefaultLoadControl` buffer (about 300-500 ms) and hide the seek bar.
  - Show the title and the sender (`from`) in the Omarchy theme.
  - Keep the screen awake while playing.
- [ ] Image viewer and text card in the theme colours and font. Text is plain text only.
- [ ] `url` via `ACTION_VIEW` + `CATEGORY_BROWSABLE`, with `409 no_app` when nothing handles it.

**Security**
- [ ] Never pass PC-supplied strings into intents other than the fixed ones above. No `Intent.parseUri`.
- [ ] Cast is only for paired PCs (every request above is signed). Opening the player must not bypass the screensaver PIN or parental controls, if the launcher has them.
- [ ] Do not log full URLs, since the token is a secret for the cast's lifetime.

**Nice to have**
- [ ] A notification when a cast starts ("Playing from omarchy").
- [ ] A "Received from PC" row in the launcher's recents.

### Assistant (`> ask`): providers and the ask flow

The TV cannot call the PC (the PC opens no port, §1), so the assistant rides on the
PC-initiated, sealed event socket (§7). This adopts the launcher's v1.1 proposal
(`launcher/PLUGINS.md`). There is no HTTP `GET /ai/providers` or `POST /ai/ask`. Their roles
are taken by `PUT /v1/ai/providers` and the `ask` event.

**`PUT /v1/ai/providers`** (PC → TV, right after the socket opens and whenever the list
changes):

```json
{ "providers": [ {"id":"claude-code","name":"Claude","kind":"cli","model":"haiku","streaming":true},
                 {"id":"codex","name":"ChatGPT (Codex)","kind":"cli","model":null,"streaming":false},
                 {"id":"ollama","name":"Local (ollama)","kind":"local","model":"llama3.2","streaming":true},
                 {"id":"grok-api","name":"Grok (API)","kind":"api","model":"grok-4.7","streaming":true} ],
  "default": "claude-code" }
```

The list holds at most 16 entries. `id` matches `^[a-z0-9][a-z0-9._-]{0,31}$` and `name` is ≤ 32 characters. Only
providers that work right now are listed: CLI tools installed and signed in on the PC first,
then local models, then API fallbacks. The TV stores the list as `link.aiProviders`
(read by `LauncherConfig`: `id`, `name`; other fields are informational) and `default` as
`link.aiDefault`. The user's choice stays in the TV's `assistant.provider`. When that
is empty or no longer listed, the TV omits `provider` and the PC uses its default. The TV adds
capability `ai-ask` to Status while `link.aiProviders` is non-empty.
No credential is ever in this list.

**Event `ask`** (TV → PC):

```json
{"seq":7,"type":"ask","data":{"id":"6f1c…","text":"che tempo fa domani a Roma?","lang":"it-IT",
                               "provider":"claude-code","session":"tv-1729324800"}}
```

- `id` is 1–64 characters, unique per question.
- `text` is the recognised speech, at most 2000 characters.
- `provider` is optional.
- `session` is optional, `^[A-Za-z0-9._-]{1,64}$`. Questions with the same `session` share short-term memory
  (the last 6 exchanges, forgotten after 15 minutes idle; set on the PC). The TV starts a new
  session when the ask screen opens, so "and tomorrow?" works within one visit.
- `lang` is an optional BCP-47 tag.
- `stream:false` is optional and asks for one final message.

**Event `answer`** (PC → TV): zero or more chunks, then exactly one `done:true`:

```json
{"type":"answer","data":{"id":"6f1c…","text":"Domani a Roma ","done":false}}
{"type":"answer","data":{"id":"6f1c…","text":"sole e 24 gradi.","done":false}}
{"type":"answer","data":{"id":"6f1c…","text":"","done":true,"final":"Domani a Roma sole e 24 gradi.",
                         "session":"tv-1729324800","provider":"claude-code","model":"haiku"}}
```

- The TV **appends** each `text`, typewriter style. The `done:true` message's `text` is
  appended too. It holds the whole answer when nothing was streamed (non-streaming providers)
  and is empty otherwise.
- `final` is the complete cleaned answer. Use it for TTS and to replace the streamed text
  if they differ (markdown markers are stripped on the PC).
- `session` echoes the session used, or one the PC created. Send it back to continue the conversation.
- Answers are plain text, ≤ 16 KiB in total, never HTML. Render them as text only.

**Event `answer_error`** (PC → TV): `{"id","error","message"}`, where `error` is one of
`no_provider` (nothing signed in, or the requested provider is not available), `quota` (the
provider reported a usage limit), `timeout` (no answer within 90 s by default), `busy` (asked
again within 3 s or more than 20 per minute), `bad_request` (empty or too long text, bad
session), `provider_error` (anything else; `message` says what) or `disabled` (the user
turned the assistant off on the PC). Show `message` to the user.

The PC answers with a fixed TV system prompt: at most three short sentences, plain text, the
user's language, no tools. CLI agents run with tools and MCP servers disabled, in an empty
temporary directory. See docs/ASSISTANT.md.

## 7. Events (WebSocket `GET /v1/events`)

The upgrade request is authenticated as in §5 (empty body). It carries
`Sec-WebSocket-Protocol: omarchytv-link.v1`. The TV answers `101` with:

```
X-OTV-Nonce: <b64u 16 random bytes>      (server nonce)
X-OTV-Sig:   b64u(HMAC(token, "OTV1-R\n" + clientNonce + "\n101\n" + hex(SHA256(serverNonce_ascii))))
```

Session key: `ks = HMAC(token, "OTV1-WS\n" + clientNonce + "\n" + serverNonce)`.
Every text frame in either direction is

```
b64u(HMAC(ks, payload)) + " " + payload        payload = {"seq":n,"type":"…","data":{…}}
```

`seq` starts at 1 per direction and increases by exactly 1. A bad MAC or seq
closes the socket. Opening is MAC verification of the exact payload bytes, so no
JSON canonicalisation is needed.

| TV → PC | when |
|---|---|
| `hello` | first frame, data = Status |
| `status` | foreground app, homeVisible, screensaver or screen state changed, data = Status |
| `apps` | an app was installed/removed/hidden, data = `{apps:[…]}` |
| `shortcut` | the user pressed a `pc` shortcut, data = `{command}` |
| `unpaired` | the user chose *Forget PC* on the TV. Close afterwards. |
| `ping` | optional keep-alive. The PC answers `pong`. |
| `ask` | voice question for the assistant (§6, Assistant) |
| `ai_sources` | the user switched AI-pane sources (§6, AI credits) |
| `cast_state` | playback state of a cast (§6, Cast) |
| `agent_snapshot` | ask for a full `agent_activity` now (§6, Agents) |
| `agent_ask` | instruction for a PC agent session (§6, Agents) |

PC → TV: `ping` (every 25 s), `pong`, `answer`, `answer_error`, `ai_sources_ok`, `ai_sources_error`,
`agent_activity`, `agent_ask_state`, `agent_answer`, `agent_ask_error`. The TV also uses WebSocket ping frames.
The TV holds sockets only while the launcher process runs. It SHOULD keep them
during the screensaver (that is when the PC shows "screensaver"), with at most
4 clients.

## 8. What lives where on the TV

| Data | Where | Why |
|---|---|---|
| tvId, paired PCs and tokens | internal storage (`getFilesDir()/link.json`, `MODE_PRIVATE`) | `/sdcard/Android/data/<pkg>` is readable by other apps with storage permission on Android 8/9 |
| current pairing code | memory only (regenerated when the page opens) | same |
| `link.pcs[] {name,address,lastSeen}` | `omarchytv.json` | shown on the pairing page |
| `theme`, `wallpaper`, `link.panes`, `link.shortcuts`, **top-level `ai` + `aiAt` + `aiSources`**, `link.aiProviders`, `link.aiDefault` | `omarchytv.json` | the launcher's live reload |

### Differences from the current launcher draft (branch `omarchytv`)

| Launcher today (`MainActivity.linkPage`) | This protocol | Action for the TV |
|---|---|---|
| port 47800 (`LINK_PORT`, provisional) | **47800** | keep, now final |
| page label "Connect Omarchy Linux" | same, used in PC messages | none |
| `link.pairingCode` in `omarchytv.json` | **memory only** | move it out: any app with storage access could read it and pair |
| `link.token` in `omarchytv.json` | **internal storage, per PC** | move it out. *Forget PC* deletes the internal entry. |
| `link.pc {name,address}` (one PC) | `link.pcs[]` (up to 8) | show the list. *Forget PC* per entry, which also sends the `unpaired` event. |
| shows `ip:47800` + code `123 456` | same | none. Show `pair=1` in TXT while the page is open. |
| top-level `ai` read by `LauncherConfig` | top-level `ai` (+ `aiAt`), canonical | keep. The Link service writes it from `PUT /v1/ai`. |
| `link.aiProviders [{id,name}]` + `ask`/`answer`/`answer_error` (PLUGINS.md v1.1 proposal) | adopted as-is, plus optional `kind`, `model`, `streaming`, `default` and the `answer.final` / `session` fields | none. Optionally use `final` for TTS. |
| example provider `grok-bot` | **not offered**: Grok Bot has no official request/reply API (only webhook routines that start a run and return 200, with no answer) | do not hard-code provider ids. Show what the PC sends. |
| "Omarchy Link for PC: coming soon" row | remove | add rows for the *Remote control (Back)* accessibility setting and Usage access |

## 9. Security notes and limits of v1

- **Authenticated everywhere, encrypted only in part.** Integrity and authenticity hold
  for every request, response and event (HMAC-SHA256 with the pairing token, timestamp,
  nonce). Since 2.13, REST bodies can also be encrypted: the TV advertises `enc-a256gcm`
  in `/v1/status` capabilities, and a PC with `python-cryptography` then sends
  `X-OTV-Enc: A256GCM` and seals the body with AES-256-GCM (key = HKDF of the token,
  AAD bound to the request nonce and direction; the HMAC signs the ciphertext). The TV
  seals the response the same way. `OMARCHY_LINK_ENCRYPT=0` turns it off.
  Not encrypted, so readable by someone on the same Wi-Fi: request paths, headers and
  query strings, bodies when the PC lacks `python-cryptography`, file uploads (themes,
  wallpapers), WebSocket event frames (pane text, telemetry and AI `ask`/`answer` text),
  and cast streams. No tokens or keys are ever sent. v2 can add TLS with the TV's
  AndroidKeyStore self-signed certificate. Its SHA-256 would be bound into the
  SPAKE2 transcript (`LP("cert:"+fp)`), so pinning would come with pairing.
- This section covers Omarchy Link (Android TV). The LG webOS / Samsung Tizen bridge
  (`omarchy-tv webos serve`) is plain HTTP with a static ask code in the
  `X-Omarchy-Token` header: no encryption and no per-request signing.
- No unauthenticated endpoint does anything: `/v1/info` is read-only and minimal,
  and `pair/*` only work while the user has the pairing page open.
- LAN only, enforced on both sides. The TV checks its subnet. The PC refuses
  non-private targets and, by default, targets outside its own subnets
  (`allowOtherSubnets`).
- No shell or arbitrary intents from either side. Shortcuts that run on the PC are
  ids, mapped to argv lists the user wrote in `config.json` and confirmed per run.
- The assistant passes TV text to AI tools on the PC. Only a paired TV can send `ask`
  (sealed socket). CLI tools run with tools and MCP disabled (Claude Code `--tools ""
  --strict-mcp-config`, Codex `--sandbox read-only -c mcp_servers={}`, Grok `--max-turns 1`),
  in an empty temp dir, with a timeout, 1 question per 3 s and 20 per minute.
- Brute force: 10^6 codes, one guess per attempt, 5 attempts per code, plus the
  rate limit. The expected attacker success is 5/10^6 per code shown.
