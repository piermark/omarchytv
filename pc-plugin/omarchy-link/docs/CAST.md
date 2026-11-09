# Send to TV ("AirPlay for Omarchy")

`omarchy-link cast` sends a link, a local file, the clipboard, some text or (experimental) the
screen from the Omarchy PC to the TV.

```sh
omarchy-link cast https://www.youtube.com/watch?v=dQw4w9WgXcQ --at 1:30   # YouTube app on the TV
omarchy-link cast https://example.org/talk.mp4                           # plays in the TV player
omarchy-link cast ~/Pictures/holiday.jpg                                 # image on the TV
omarchy-link cast ~/Videos/film.mkv --serve                              # big local file (see below)
omarchy-link cast --clipboard                                            # whatever you copied
omarchy-link cast --text "Dinner is ready"                               # a text card (OmarchyTV)
omarchy-link cast --screen                                               # EXPERIMENTAL mirroring
omarchy-link cast --stop | --control pause | --control seek --at 10:00
omarchy-link cast --devices                                              # what can receive
omarchy-link cast --to "Living Room TV" --via cast|link …
omarchy-link desktop                                                     # launcher + "Open with" entries
```

## Options considered

| | (a) Google Cast (`catt`) | (b) OmarchyTV Link `cast` | (c) Screen mirroring |
|---|---|---|---|
| Needs the launcher | no: any Cast TV (Sony Android TV has Cast built in) | yes, a launcher with the `cast` capability | yes (b) |
| YouTube | the TV's YouTube app (catt drives the YouTube receiver) | the YouTube app via intent | n/a |
| Media URLs | the Cast player (via yt-dlp for sites) | launcher player (Media3/ExoPlayer) or `ACTION_VIEW` | n/a |
| Web pages | DashCast (`catt cast_site`), patchy | the TV browser via `ACTION_VIEW` | n/a |
| Images | Cast default receiver | Omarchy-styled viewer | n/a |
| Text | no | yes, text card in Omarchy colours | n/a |
| Local files | catt serves them from the PC (port 45000-47000) | upload over the signed channel (≤ `castUploadMax`), or an opt-in temporary server | live stream from the PC |
| Auth | none (Cast is open on the LAN by design) | paired, HMAC-signed, LAN-only | the same plus a one-time URL |

**Choice:**
- **Cast first when `catt` is installed:** it works today on any Cast TV and needs nothing on the TV.
- **The Link channel** is the Omarchy-native path. It handles text, the screen and images in Omarchy style, and it is the fallback when no Cast device answers.
- `--via` forces a route, and `"castPrefer": "link"` in `config.json` flips the order.
- A local file without `--serve` goes to Link first (upload, no port opened), because Cast would need catt's server.

## Ports and privacy

The PC opens **no listening port** unless you ask for one:
- `--serve`, or `"castServe": true` in `config.json`, for local files;
- `--screen`, for mirroring.

When one is open:
- **Link route:** omarchy-link's own server binds only to the PC address that faces the TV, on a random port. It answers only the TV's IP and serves one URL with a 192-bit random token, with Range support for seeking. It closes when the TV reports `ended`/`stopped`/`error`, after 10 minutes idle (1 minute for the screen), or after 4 hours at most. The CLI stays running until then.
- **Cast route:** catt's own server is used, and it serves while catt runs.

Small files (at most the TV's `castUploadMax`, 64 MiB on the mock) are uploaded over the signed channel instead, with no port at all. Above 8 MiB, `--serve` streams rather than uploads, so playback starts sooner.

### Firewall (ufw on Omarchy)

Stock Omarchy turns **ufw** on and denies incoming connections, so when the PC serves a file or the screen
(`--serve`, `--screen`) the TV can't fetch it: the cast ends as `disconnected`, `error` or `timeout` and nothing
plays ([#82](https://github.com/piermark/omarchytv/issues/82)). Allow your TV once:

```sh
sudo ufw allow from <tv-ip> proto tcp comment 'OmarchyTV'
```

The rule lets only that TV in, on any TCP port (the stream port is random). The CLI prints this hint, with the TV's
address filled in, when a served cast fails while ufw is active. Uploads and links the TV fetches from the internet
need no rule.

## Screen mirroring on Hyprland (experimental)

**Pipeline:**
1. Capture and encode with `gpu-screen-recorder` (hardware H.264 via VA-API/NVENC; capture plus encode is about one frame). The fallback is `wf-recorder` (wlr-screencopy plus x264 `ultrafast`/`zerolatency`).
2. The encoder writes MPEG-TS to stdout.
3. The temporary server streams it to the launcher's player as `kind:"video", live:true`. The recorder only starts when the TV connects and is stopped with the stream.

**Latency:** capture plus encode is about 20-50 ms and the LAN adds a few ms. The TV player's buffer dominates:
- about **0.5-1.5 s** with Media3/ExoPlayer and a small buffer (see the checklist);
- **2-5 s** with plain `MediaPlayer`/`VideoView`.

That is fine for slides, photos and videos, but not for games or typing.

**Why not Cast:** the Cast default receiver does not play a progressive MPEG-TS live stream well, and HLS would add 3-10 s. Mirroring is therefore Link-only.

**Notes:**
- Many Sony Android TVs also have Miracast ("Screen mirroring"). Wayland Miracast senders such as gnome-network-displays exist but are outside this plugin, and Wi-Fi Direct tends to be fragile.
- Override the recorder with `"screenCommand": ["gpu-screen-recorder", …, "-o", "/dev/stdout"]`. Any command that writes MPEG-TS to stdout works.

## Omarchy integration

- **Bar widget:** **SEND CLIPBOARD** and **STOP** buttons in the remote view, and key `C`. They work with Cast even before an OmarchyTV is paired.
- **Launcher (Super+Space):** `omarchy-link desktop` installs "Send clipboard to TV", "Stop casting to TV" and "Mirror screen to TV (experimental)". `--remove` takes them out.
- **File managers:** the same command adds **Open with → Send to TV** (`omarchy-link-share.desktop`, for video/audio/image/text and http(s) links).
- **Browser:** copy the URL, then run "Send clipboard to TV". Chromium's own Cast menu also works for tabs.
- **Hyprland binding:** `bind = SUPER ALT, T, exec, omarchy-link cast --clipboard --notify`
- **Results** appear as an Omarchy notification (`--notify`).

## Requirements

- `catt` for the Cast route: `pipx install catt` or AUR `catt`. It is BSD-2-Clause and uses pychromecast (MIT) and yt-dlp.
- `wl-clipboard` for `--clipboard` (macOS: the built-in `pbpaste`).
- `gpu-screen-recorder` or `wf-recorder` for `--screen`.
