# OmarchyTV — installazione / installation

Versione / version: **2.12.0-omarchytv** (versionCode 20) · package `com.omarchytv.home`

## Verifica del file / verify the download

| | SHA-256 |
|---|---|
| `omarchytv-release-2.12.0-beta.apk` | `9dedc335fb1150afa6027f4123bd295bec8241a4c654842a6be65caff993f035` |
| `omarchytv-release-2.11.0.apk` | `33248d55f359905dd6068bfeca16e76a02da54150a119aae3ecfa0938ddc5111` |
| Certificato di firma / signing certificate | `90aa68d9f45e623eadfd349ab1eb66042356f95ff6347a4568c3f3a78f8ea33d` |

```sh
sha256sum omarchytv-release-2.12.0-beta.apk                    # Linux   (macOS: shasum -a 256, Windows: certutil -hashfile <file> SHA256)
apksigner verify --print-certs omarchytv-release-2.12.0-beta.apk  # "Signer #1 certificate SHA-256 digest" must match
```
Ogni aggiornamento è firmato con lo stesso certificato, quindi si installa sopra la versione precedente.
Every update is signed with the same certificate, so it installs over the previous version.

## TV provate / Tested TVs

| TV | Android | WebView | OmarchyTV | Stato / Status | Note |
|---|---|---|---|---|---|
| Sony KD-55XE9005 (2017) | 9 | 138 | 2.10.1-beta | in attesa del test di domenica / pending Sunday test | 2.9 installed and working |
| Android TV emulator (API 28, x86) | 9 | 66 | 2.10.1-beta | ok (software rendering) | official tte.js needs WebView ≥ 72: bundle fallback used |

Aggiungi la tua TV con il modello di issue "TV report". · Add your TV with the "TV report" issue template.

---

## Italiano

### 1. Attivare ADB sulla TV
1. Impostazioni → Preferenze dispositivo → Info → premi 7 volte **Build** (opzioni sviluppatore attive).
2. Impostazioni → Preferenze dispositivo → Opzioni sviluppatore → attiva **Debug USB** e, se presente, **Debug di rete / ADB in rete**.
3. Annota l'indirizzo IP della TV (Impostazioni → Rete → stato).

### 2. Installare adb sul computer
Scarica **Android SDK Platform-Tools** da developer.android.com/tools/releases/platform-tools (Windows, macOS, Linux)
oppure: macOS `brew install android-platform-tools`, Debian/Ubuntu `sudo apt install adb`, Arch/Omarchy `sudo pacman -S android-tools`.

### 3. Collegarsi e installare
```sh
adb connect <IP-TV>:5555        # sulla TV accetta "Consentire il debug?"
adb install -r omarchytv-release-2.12.0-beta.apk
```
Windows: stessi comandi in PowerShell dalla cartella platform-tools (`.\adb.exe ...`).

### 4. Usare OmarchyTV come salvaschermo (facoltativo)
```sh
adb shell settings put secure screensaver_components com.omarchytv.home/.OmarchyDream
adb shell settings put secure screensaver_enabled 1
```
Prima annota il valore attuale per poterlo ripristinare: `adb shell settings get secure screensaver_components`.

### 5. Usarlo come home (facoltativo)
Premi Home e scegli OmarchyTV, oppure:
`adb shell cmd package set-home-activity --user 0 com.omarchytv.home/.MainActivity`.
Se la TV torna sempre al launcher originale puoi *disattivarlo* (mai disinstallarlo):
`adb shell pm disable-user --user 0 com.google.android.tvlauncher` (sui TV Sony può avere un altro nome).
Su Google TV (Google TV Streamer, alcuni Philips con Android 14) nemmeno questo basta: Home finisce su
`setupwraith/.RecoveryActivity`, che non va mai disattivato. Lascia il launcher originale attivo e accendi invece
**Il tasto Home apre OmarchyTV** (menu → Impostazioni launcher → *Il tasto Home apre OmarchyTV*, che apre Accessibilità:
attiva *Tasto Home OmarchyTV*).
Se il tuo telecomando è supportato (per esempio onn. 4K Pro), il tasto Home può aprire OmarchyTV sempre, anche quando la
home originale è già sullo schermo: menu → Impostazioni launcher → *Tasti del telecomando* → attiva il *Servizio di
accessibilità* (*OmarchyTV tasti telecomando*), poi *Home button* → *Pressione singola* → *Azione OmarchyTV* → *Home*.

### 6. Debloat (facoltativo, a tuo rischio)
- Usa **solo** `adb shell pm disable-user --user 0 <pacchetto>`; è reversibile con `adb shell pm enable <pacchetto>`.
- **Non** usare mai `pm uninstall`.
- **Non** toccare DNS né `private_dns_*`: gli script del progetto non li modificano.
- Fai prima un backup dello stato (`tools/backup-state.sh`) e disattiva un pacchetto alla volta.

### 7. Tornare all'originale
```sh
adb shell pm enable com.google.android.tvlauncher      # se l'avevi disattivato
adb shell settings put secure screensaver_components <valore annotato>   # es. com.google.android.backdrop/.Backdrop
```
Oppure `tools/restore.sh <lista>` per riattivare tutti i pacchetti disattivati.

### 8. Disinstallare
Prima ripristina launcher e salvaschermo (punto 7), poi `adb uninstall com.omarchytv.home`.

### Note di sicurezza
- Il debug di rete dà pieno accesso alla TV a chi è sulla stessa rete: disattivalo quando hai finito.
- Il cast è spento di default; nessun dato personale viene inviato fuori dalla rete locale.

---

## English

Also see: [safe debloat](../../docs/DEBLOAT.md) · [AI-assisted setup](../../docs/AI-ASSISTED-SETUP.md).

### 1. Enable developer options and network debugging
1. Settings → Device Preferences → About → select **Build** 7 times ("You are now a developer").
2. Settings → Device Preferences → Developer options → turn on **USB debugging**, and **Network debugging /
   ADB over network** if your TV shows it (on many Android 9 TVs, USB debugging alone already opens port 5555).
3. Note the TV's IP address (Settings → Network & Internet → your network). A DHCP reservation in your router keeps it stable.

### 2. Install adb on your computer
Download **Android SDK Platform-Tools** (Windows, macOS, Linux) from developer.android.com/tools/releases/platform-tools,
or: macOS `brew install android-platform-tools`, Debian/Ubuntu `sudo apt install adb`, Arch/Omarchy `sudo pacman -S android-tools`.

### 3. Connect
```sh
adb connect <TV-IP>:5555        # accept "Allow USB debugging?" on the TV (tick "Always allow")
adb devices                     # must list <TV-IP>:5555  device   (not "unauthorized")
```

### 4. Verify and install the signed public APK
```sh
sha256sum omarchytv-release-2.12.0-beta.apk                      # must match the table at the top
apksigner verify --print-certs omarchytv-release-2.12.0-beta.apk  # signer SHA-256 must match the certificate above
adb install -r omarchytv-release-2.12.0-beta.apk
adb shell dumpsys package com.omarchytv.home | grep -E 'versionName|versionCode'
```
(`apksigner` is in Android SDK build-tools; if you don't have it, the SHA-256 check is the minimum.)

### 5. Set as home
Press **Home** on the remote and choose **OmarchyTV** if the TV asks, or set it directly (Android 9+):
```sh
adb shell cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME   # note the current home first
adb shell cmd package set-home-activity --user 0 com.omarchytv.home/.MainActivity
```
`com.omarchytv.home/.MainActivity` is the launcher's only HOME activity. After a reboot, if a Sony/Google boot
receiver puts the stock home back, OmarchyTV's `HomeCheck` notices it once per boot and asks; it never changes the
default home on its own. If the stock home keeps coming back you may *disable* it (never uninstall):
`adb shell pm disable-user --user 0 <stock home package from the resolve-activity output>` — see [DEBLOAT](../../docs/DEBLOAT.md).
**Google TV** (Google TV Streamer, some Philips/TPV Android 14 TVs): `set-home-activity` prints `Success` and nothing
changes, and disabling the stock launcher makes Home open `setupwraith/.RecoveryActivity` (never disable setupwraith: it
breaks Wi-Fi). Keep the stock launcher enabled and turn on **Home button opens OmarchyTV** instead (menu → Launcher
settings → *Home button opens OmarchyTV*, which opens Accessibility: turn on *OmarchyTV home button*). Home briefly
shows the stock home and OmarchyTV opens over it. It only sees which home screen opened and reads nothing on screen.
If your remote is supported (for example the onn. 4K Pro), Home can always open OmarchyTV, even when the stock home is
already on screen: menu → Launcher settings → *Remote buttons* → turn on the *Accessibility service* (*OmarchyTV remote
buttons*), then *Home button* → *Single press* → *OmarchyTV action* → *Home*.
Over adb:
`adb shell settings put secure enabled_accessibility_services com.omarchytv.home/.HomeOverride` (this replaces the list:
add `:com.omarchytv.home/.OmarchyRemote` if you use the remote too) and `adb shell settings put secure accessibility_enabled 1`.
Crash safety: after 3 crashes within 5 minutes, `Watchdog` puts the stock home back (or opens the system home picker).

### 6. Screensaver (OmarchyDream)
```sh
adb shell settings get secure screensaver_components     # note the current value first
adb shell settings put secure screensaver_components com.omarchytv.home/.OmarchyDream
adb shell settings put secure screensaver_enabled 1
```
On some TVs you can also pick it in Settings → Device Preferences → Screen saver as "OmarchyTV". Inside the launcher,
the home screen has its own idle screensaver (menu → Salvaschermo / Screensaver), independent of the system one.

### 7. Update
Download the newer public APK, verify it as in step 4, then `adb install -r <new>.apk`. Updates are signed with the
same certificate, so settings are kept. A different signature fails with `INSTALL_FAILED_UPDATE_INCOMPATIBLE`: do
not uninstall to "fix" it unless you know where the APK came from. From an Omarchy PC, `omarchy-tv update` (see
[pc-plugin](../../pc-plugin/README.md)) performs the same version and certificate checks before installing.

### 8. Roll back / restore the original / uninstall
- **Roll back to an older OmarchyTV:** Android refuses downgrades over `install -r`. Uninstall, then install the older
  APK. This erases OmarchyTV settings; save `/sdcard/Android/data/com.omarchytv.home/files/omarchytv.json` first
  (`adb pull`) and push it back afterwards.
- **Restore the stock home:** `adb shell pm enable <stock home package>` (if you disabled it), then
  `adb shell cmd package set-home-activity --user 0 <the activity you noted in step 5>`.
- **Restore the screensaver:** `adb shell settings put secure screensaver_components <value noted in step 6>`.
- **Re-enable debloated packages:** `adb shell pm enable <package>` or `tools/restore.sh <list>`.
- **Uninstall:** restore home and screensaver first, then `adb uninstall com.omarchytv.home`.

### 9. Troubleshooting
| Symptom | Fix |
|---|---|
| `adb connect` times out / refused | Network debugging off, wrong IP, or TV on another subnet/VLAN. Re-check step 1; try USB debugging toggled off/on. |
| `unauthorized` in `adb devices` | Accept the prompt on the TV; if none appears, `adb kill-server`, reconnect; Developer options → Revoke USB debugging authorisations, then retry. |
| `INSTALL_FAILED_UPDATE_INCOMPATIBLE` | An APK with another signature is installed. Check where it came from; only then uninstall and install the official one. |
| `INSTALL_FAILED_VERSION_DOWNGRADE` | You are installing an older version: see step 8. |
| Home button opens the stock launcher | Run step 5 again; check `resolve-activity`. On Google TV turn on *Home button opens OmarchyTV* (step 5). |
| Black screen after boot | `adb shell pm enable` whatever home you disabled, or `set-home-activity` to another home. |
| Screensaver doesn't start | Check `screensaver_components` / `screensaver_enabled`; some TVs override it in their own picture/eco settings. |
| Animated screensaver effects look plain | Old system WebView (< 72): the bundled fallback is used. Update "Android System WebView" from the Play Store. |
| PC can't pair | TV and PC must be on the same LAN; the Omarchy Link listens on TCP 47800 on the LAN only. |
| Link refuses or times out after boot, an update or sleep | Once a PC is paired, Omarchy Link starts at boot and after an update, and stays running as a foreground service (a small low-priority notification), which keeps its network access while the TV is idle. Older builds lose the network in doze (`dumpsys netpolicy` shows the app `REJECT_ALL`): exempt it with `adb shell dumpsys deviceidle whitelist +com.omarchytv.home` (undo with `-com.omarchytv.home`). |

### Safety notes
Network debugging gives anyone on your LAN full access to the TV: turn it off when done. Cast and live screen sharing
are off by default. Never use `pm uninstall`, never change DNS / `private_dns_*` as part of the setup.
