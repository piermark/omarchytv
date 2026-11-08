# Safe, reversible debloat (Android TV 9 / Sony BRAVIA)

> **Optional and at your own risk.** OmarchyTV works without any debloat. Nothing in this guide removes an app:
> every step is undone with one command.

**OmarchyTV does not hide Sony apps.** The launcher's built-in hidden list only contains two non-Sony tuner/gallery
packages (`com.mediatek.wwtv.tvcenter`, `com.mitv.gallery`); every Sony app stays visible as a tile, and the Live TV
app (`com.sony.dtv.tvx`) is also offered at the end of the Inputs menu. Disabling packages is a separate, manual choice.

## Rules

1. **Back up first** (below). Keep the files.
2. Only ever use `pm disable-user --user 0 <package>`. It is reversible.
3. **Never** `pm uninstall` (not even `--user 0`), never root, never flash anything.
4. **Never** change DNS or `private_dns_*` settings as part of a debloat.
5. **One package at a time**, confirm each one, test the TV (Home, inputs, apps, remote, screensaver) before the next.

## 1. Backup (read-only)

```sh
adb connect <TV-IP>:5555
mkdir -p backup && cd backup
adb shell pm list packages -f        > packages-with-paths.txt
adb shell pm list packages -d        > disabled-before.txt
adb shell cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME > home-before.txt
adb shell settings list global       > settings-global.txt
adb shell settings list secure       > settings-secure.txt
adb shell settings list system       > settings-system.txt
adb shell getprop                    > getprop.txt
```

From a clone of this repo, `tools/backup-state.sh` takes the same snapshot (plus memory figures) into
`backup/state-<timestamp>/`. It changes nothing; private DNS values are only recorded.

## 2. Disable one package (with confirmation)

```sh
PKG=com.example.package
adb shell pm list packages | grep -x "package:$PKG"    # make sure it exists on YOUR TV
read -p "Disable $PKG? [y/N] " a && [ "$a" = y ] && adb shell pm disable-user --user 0 "$PKG"
echo "$PKG" >> disabled-by-me.txt                       # your own undo list
```

`tools/disable.sh <list>` (or `tools/disable.ps1` on Windows) runs the same `pm disable-user --user 0` for every
package in a list file **without asking**, so only use it with a list you have already reviewed line by line;
`tools/restore.sh <same list>` undoes it.

## 3. Re-enable

```sh
adb shell pm enable <package>                           # undo disable-user
adb shell cmd package install-existing <package>        # only if the app was removed for user 0 by something else
while read p; do adb shell pm enable "$p"; done < disabled-by-me.txt   # undo everything you did
```

Compare `adb shell pm list packages -d` against `disabled-before.txt`. A factory reset also re-enables every
system app.

## 4. Package table

**Verify on your model.** Names differ between Sony model years and firmware; a package that is harmless on one TV
can be required on another. "Commonly safe" means "commonly reported as safe to disable and reversible", not tested
by this project on every TV.

| Package | Typical role | Verdict |
|---|---|---|
| `com.google.android.tvrecommendations` | home-screen recommendation rows | commonly safe · verify on your model |
| `com.google.android.backdrop` | Google "Backdrop" screensaver | commonly safe if you use OmarchyDream · verify on your model |
| `com.google.android.tvlauncher` | stock Google TV home | only if the stock home keeps returning; check `home-before.txt` first · verify on your model |
| Sony promo / demo / store-mode apps (`com.sony.dtv.*` with "promo", "demo", "retail" in the name) | ads, shop demo | commonly safe · verify on your model |
| Preinstalled third-party apps you never use | — | usually safe (they are normal apps) · verify on your model |
| `android`, `com.android.systemui`, `com.android.shell`, `com.android.providers.*` | the OS itself | **never touch** |
| `com.google.android.gms`, `com.google.android.gsf`, `com.android.vending` | Play services / Play Store | **never touch** |
| `com.android.tv.settings`, `com.android.packageinstaller`, input methods (`*inputmethod*`, `*leanback.ime*`) | Settings, installs, keyboard | **never touch** |
| `com.google.android.katniss` and other Assistant/voice packages | voice search / mic key | **never touch** (the remote's mic stops working) |
| `com.sony.dtv.tvx` and Sony tuner/input/HDMI-CEC/remote services | Live TV, inputs, CEC, remote | **never touch** |
| `com.google.android.tungsten.setupwraith` | Google TV setup / Wi-Fi setup | **never touch** (disabling it breaks Wi-Fi on Google TV; thanks Michael Pfeifer, @TheFifeDawg) |
| `com.omarchytv.home` | OmarchyTV | never disable while it is your home |

## Warnings

- If the TV boots to a black screen, the home app you rely on is probably disabled: `adb shell pm enable` it (adb over
  the network still works), or `cmd package set-home-activity` another home.
- OmarchyTV's crash watchdog (`Watchdog`) restores the stock home after 3 crashes in 5 minutes; that only works if
  the stock home is still **enabled**.
- System updates can re-enable packages or add new ones; re-run the backup after an update.
- Turn network debugging off when you are done.
