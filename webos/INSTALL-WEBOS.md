# OmarchyTV for LG webOS — Installation Guide (`2.13.9`)

> **Status: Experimental (`testing`, NOT beta)**
> This is an experimental **`testing`** build of **OmarchyTV** for **LG webOS TVs** (webOS 3.x / 4.0 / 5.0 / 6.0 / 22 / 23 / 24; on webOS 3.x the bundled `compat.js` fills in the missing browser features), based on the latest Android TV release (**2.13.0**). Tested on a real LG 32LQ63006LA (webOS 22).
> Community feedback and compatibility reports from LG webOS TV users are warmly welcome!

---

## Downloads (`v2.13.9`)

- **LG webOS Package (`.ipk`):** [`omarchytv-webos-2.13.9.ipk`](https://github.com/piermark/omarchytv/releases/download/v2.13.9/omarchytv-webos-2.13.9.ipk) ([GitLab](https://gitlab.com/piermark/omarchytv/-/releases/v2.13.9))
  - SHA-256: `3a7efc5452711bb7323f9ff369e9e7c10508f5ea599d27db6062a108f81a9d6f`
- **PC Plugin (Android TV + LG webOS):** [`omarchy-tv-pc-plugin-2.13.0-beta.tar.gz`](https://github.com/piermark/omarchytv/releases/tag/v2.13.0-beta)
  - SHA-256: `968c5ffb79f5b88254df6ff922888027c1776c49bcf154980f1427c3338521bf`

---

## Important Note on LG webOS Limitations

Unlike Android TV, **unrooted LG webOS does not allow third-party apps to replace the system Home launcher** when pressing the physical `Home` button on the Magic Remote.
Instead, **OmarchyTV (`com.omarchytv.home`) installs as a standard webOS app** on the LG Home Launcher bar.

**Pro Tip — Single-button access via Quick Access:**
Once OmarchyTV is installed, open it on your LG TV and **long-press the `0` (or `1`–`9`) number button** on your Magic Remote to assign OmarchyTV to a Quick Access shortcut. From then on, holding that number key from anywhere on the TV jumps straight into OmarchyTV.

## Optional: make OmarchyTV act like the home screen

The remote's `Home` button still opens LG Home. If you have an always-on machine on your network (a NAS, home server or Raspberry Pi), the [OmarchyTV Home watcher](../homewatch/README.md) can send `Home` to OmarchyTV instead, so it feels like the TV's home screen. It runs off the TV in Docker or as a service; stop it and `Home` works as before.

**Rooted TVs (Homebrew Channel):** no other machine is needed. In OmarchyTV open **Workspace 5 › All OmarchyTV settings** and turn on:

- **Open when the TV starts**: OmarchyTV opens once the TV has booted.
- **Home button opens OmarchyTV**: when LG Home comes up, OmarchyTV opens instead. Press `Home` while you are already in OmarchyTV to reach LG Home and its menus.
- **Keep loaded in the background**: webOS stops closing OmarchyTV when another app needs memory, so going back to it (Home, Quick Access) doesn't restart it. It adds OmarchyTV to the `KeepAlive` list in `/home/root/memorymanager-conf.json`, the writable override of LG's memory-manager settings, and restarts the memory manager.

Both run on the TV through Homebrew Channel's start-up scripts ([`app/rooted/omarchytv-home.sh`](app/rooted/omarchytv-home.sh)); turning both off removes the start-up hook. On unrooted TVs the two settings show "rooted TV only".

---

## Method A — Easiest GUI Install (webOS Dev Manager on PC / Mac / Linux)

1. **Create a free LG Developer Account** (if you don't already have one) at [webostv.developer.lge.com](https://webostv.developer.lge.com).
2. **On your LG TV:**
   - Open the **LG Content Store** (or **Apps**), search for **Developer Mode**, and install it.
   - Launch the **Developer Mode** app and sign in with your LG Developer account.
   - Toggle **Dev Mode Status** to **ON** (the TV will reboot).
   - After the reboot, open **Developer Mode** again and enable **Key Server**. Note the **IP Address** and **Passphrase** displayed on screen.
3. **On your computer:**
   - Install and open **[webOS Dev Manager](https://github.com/webosbrew/dev-manager-desktop)** (available on Linux, macOS, and Windows; on Arch / Omarchy: `yay -S webos-dev-manager-bin` or AppImage).
   - Add your TV using its IP address and the 6-character Passphrase from the Developer Mode app.
   - Click **Apps → Install** (top right) and select **`omarchytv-webos-2.13.9.ipk`**.
4. **Launch OmarchyTV** from your LG TV's Home bar!

---

## Method B — Command-Line Install (`ares-cli` or `omarchy-tv update`)

### 1. Install `@webos-tools/cli` on your PC

```bash
npm install -g @webos-tools/cli
```

### 2. Register your LG TV (`lgtv`) and fetch its SSH developer key

Make sure **Developer Mode** and **Key Server** are enabled on your TV, then run:

```bash
# Replace 192.168.1.50 with your LG TV's LAN IP address
ares-setup-device -a lgtv -i "host=192.168.1.50" -i "port=9922" -i "username=prisoner"

# Enter the 6-character Passphrase shown in the TV's Developer Mode app
ares-novacom --device lgtv --getkey
```

### 3. Install & Launch the `.ipk`

With `ares-install` directly:

```bash
ares-install --device lgtv omarchytv-webos-2.13.9.ipk
ares-launch  --device lgtv com.omarchytv.home
```

Or using the **OmarchyTV PC Plugin (`2.13.0-beta`)**:

```bash
omarchy-tv update omarchytv-webos-2.13.9.ipk --tv lgtv
```

---

## Method C — Rooted TV (Homebrew Channel, no Developer Mode)

If your TV is rooted with [Homebrew Channel](https://github.com/webosbrew/webos-homebrew-channel), you can install over SSH as root. There is no Developer Mode session to renew, and the rooted-only settings described [above](#optional-make-omarchytv-act-like-the-home-screen) become available.

### 1. Set up SSH

In Homebrew Channel's settings, make sure **SSH Server** is on. Then copy your SSH key to the TV once, so you aren't asked for the root password every time:

```bash
# Replace 192.168.1.50 with your LG TV's LAN IP address
ssh-copy-id root@192.168.1.50
```

### 2. Install & Launch the `.ipk`

```bash
scp omarchytv-webos-2.13.9.ipk root@192.168.1.50:/tmp/omarchytv.ipk
ssh -t root@192.168.1.50 'luna-send-pub -i luna://com.webos.appInstallService/dev/install "{\"id\":\"com.ares.defaultName\",\"ipkUrl\":\"/tmp/omarchytv.ipk\",\"subscribe\":true}"'
```

Wait for `"state":"installed"`, then press `Ctrl+C`: the command keeps listening after the install. Launch it with:

```bash
ssh -t root@192.168.1.50 'luna-send -n 1 luna://com.webos.service.applicationmanager/launch "{\"id\":\"com.omarchytv.home\"}"'
```

> **Keep the `-t`.** On webOS, `luna-send` and `luna-send-pub` print nothing unless they run in a terminal, so without `ssh -t` every command looks like it silently did nothing.

To update, install the new `.ipk` the same way. To uninstall (it ends on its own after `"state":"removed"`):

```bash
ssh -t root@192.168.1.50 'luna-send-pub -i luna://com.webos.appInstallService/dev/remove "{\"id\":\"com.omarchytv.home\",\"subscribe\":true}"'
```

---

## Connecting Your Omarchy Linux PC (Live Stats, Themes & Wallpaper)

The `2.13.0-beta` PC plugin includes a lightweight HTTP + CORS bridge (`omarchy-tv webos-serve`) so your LG webOS TV can display live PC telemetry (CPU, RAM, uptime, GPU, active theme, and AI credit usage) without requiring native sockets on the TV.

### 1. Start the bridge on your Omarchy PC

```bash
# Extract and install the 2.13.0-beta PC plugin
tar -xzf omarchy-tv-pc-plugin-2.13.0-beta.tar.gz
cd pc-plugin
mkdir -p ~/.local/share ~/.local/bin
cp -r omarchy-link ~/.local/share/omarchy-link
cp -r omarchy-tv   ~/.local/share/omarchy-tv
ln -sf ~/.local/share/omarchy-link/bin/omarchy-link ~/.local/bin/omarchy-link
ln -sf ~/.local/share/omarchy-tv/bin/omarchy-tv     ~/.local/bin/omarchy-tv

# Start the webOS LAN bridge on port 8765
omarchy-tv webos-serve --port 8765
```

### 2. Pair from your LG TV

1. On your LG TV inside OmarchyTV, switch to **Workspace `4` (`pc`)** using the top bar or D-pad, or open the **Menu → Connect Omarchy Linux**.
2. Select **`[ pair ]`** (or **Connect Omarchy Linux**) and enter your PC's LAN IP address (for example `192.168.1.100` or `192.168.1.100:8765`).
3. The TV will immediately begin polling `/v1/status` every 5 seconds and display your PC's hostname, CPU/RAM load, uptime, and active Omarchy theme.

---

## Your installed TV apps (new in `2.13.8`)

OmarchyTV reads the apps installed on your LG TV (for example NOW, MUBI, DAZN and the LG app store) and shows them on the dock with their own icons. Built-in tiles like Netflix or YouTube use the TV's real icon when the app is installed, falling back to their styled tile if the icon can't load. Built-in tiles for apps that aren't installed are hidden (TV and LG Settings always stay).

- **One-time permission prompt:** the first time, the TV asks whether to allow OmarchyTV to control it. Press **OK / Allow** on the remote. The TV gives OmarchyTV a pairing key, which stays only on the TV.
- If you decline or the prompt times out, the built-in app list stays and OmarchyTV doesn't ask again on its own.
- **Settings > All settings > Read installed TV apps** turns this on or off (on by default).
- **Settings > All settings > Re-pair with TV** forgets the key and shows the prompt again, for example after a TV reset or if new apps don't appear.

## Grok Bot · Omarchy TUI (new in `2.13.9`)

I ported the Android Grok Bot TUI to LG and Samsung. Open it from **Menu > ⌨ Grok Bot (Omarchy TUI)**, **Menu > Grok Bot & AI**, **Plugins**, the companion chat or Companion settings. It's an Omarchy-style box with the legend `grok bot · omarchy tui [theme]`, theme colours and CaskaydiaMono.

- Type with the on-screen keyboard (or select **🎤 Speak**), then **❯ Run**. **Back** closes it.
- Local commands, no PC needed: `help`, `themes`, `theme <name>`, `next theme`, `wallpaper`, `screensaver [aquarium|classic|logo|cinemarchy|slow|normal|fast]`, `speed slow|normal|fast`, `open <app>` / `apri <app>`, `apps`, `status`, `clear`. Italian works too.
- **Agents, like on Android:** `agents` lists them and `agent <name>` (or the **Agent** row) picks one: Grok Bot (companion), every AI signed in on your PC (Claude, ChatGPT/Codex, Grok, Grok Bot, Hermes, Gemini, ollama, API keys) and Grok Web. PC agents need `omarchy-tv webos serve` from this release with omarchy-link installed and the ask code set in Companion settings.
- `web ▾`, `open grok.com`, `chatgpt`, `claude`, `x grok` or `hermes` opens that web app in the TV browser. **Menu > Web apps** lists them and lets you add your own (`Name=https://…`).
- With no PC, questions go to Grok Bot diretto (if on), otherwise to grok.com. Replies type out in the window.

## Text size (new in `2.13.2-testing`)

**Bigger text on LG webOS and Samsung Tizen:** launcher text can now be enlarged from **Settings > System > Text size**: Small 90%, Normal 100% (default), Large 115% and Extra large 130%. It applies to the whole launcher, it is saved across restarts, and it is translated in English, Italian and German.

## Controls on LG Magic Remote / Standard IR Remote

- **Down from the app row:** reaches the Grok Bot companion in the corner, then **Right** for the SpaceX TV pill. **OK** opens it, **Up** goes back. The **Blue** key also opens the companion chat.

- **Arrow keys (Up / Down / Left / Right):** Navigate between top bar workspaces, dashboard panels, app dock tiles, and menu items.
- **OK / Wheel Click (`Enter`):** Activate the focused app tile, workspace, or menu action.
- **Back (`keyCode 461` / `Backspace` / `Escape`):** Close open menus, exit the screensaver, or return to Workspace 1.
- **Number keys `1`–`5`:** Jump directly to Workspace `1` (`home`), `2` (`tv`), `3` (`apps`), `4` (`pc`), or `5` (`sys`).
- **Magic Remote Pointer (Cursor):** Hover and click any workspace tab, panel button, app tile, or menu entry directly.

---

## Uninstalling or Returning to Stock LG Home

OmarchyTV on LG webOS never modifies system files or disables any built-in TV packages:
- Press the **Home** button on your LG remote at any time to return to the stock LG webOS launcher.
- To uninstall OmarchyTV completely, either hold **OK** on the OmarchyTV icon on the LG Home bar and select **Delete**, or run:
  ```bash
  ares-install --device lgtv --remove com.omarchytv.home
  ```
