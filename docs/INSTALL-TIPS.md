# Install tips for some TVs

These notes cover things the TV's maker does, not OmarchyTV. Each section names the issue where it was reported.

## ADB over the network (every Android TV)

1. On the TV, open **Settings › Device preferences › About** and press OK on **Build** seven times. This unlocks
   **Developer options**.
2. In **Developer options**, turn on **USB debugging**. On Google TV, also turn on **Wireless debugging**.
3. From the PC, run `adb connect TV_IP:5555` and accept the prompt on the TV. With *Wireless debugging*, pair first
   with `adb pair TV_IP:PORT CODE` (port and code are shown on the TV), or use `omarchy-tv adb-pair`.

## Hisense (#102)

On some Hisense Google TV sets, ADB over Ethernet only starts working after Wi-Fi debugging was set up once.

1. Connect the TV over Wi-Fi.
2. Turn on **Wireless debugging** and pair with `adb pair`.
3. Switch back to Ethernet and run `adb connect TV_IP:5555` again.

## TCL (#75)

- **Home is not kept.** TCL resets the Home choice. Disable the stock launcher once with `adb shell pm disable-user`
  (see [DEBLOAT.md](DEBLOAT.md)). `pm enable` undoes it.
- **No network after installing apps.** TCL's *SmartConnect* service
  (`com.google.android.apps.nbu.smartconnect.tv`) can run a local VPN that breaks name resolution. If websites and
  app stores stop loading, turn it off:
  `adb shell pm disable-user --user 0 com.google.android.apps.nbu.smartconnect.tv`.
  To undo it: `adb shell pm enable com.google.android.apps.nbu.smartconnect.tv`.

## Accessibility turned off after an update or force stop (#75, #58)

Reinstalling the APK with `pm install -r` or force-stopping it can turn off OmarchyTV's accessibility services on
some TVs (Home button, remote keys). Turn them on again in **Settings › Accessibility**, or from the PC:

```sh
adb shell settings put secure enabled_accessibility_services \
  com.omarchytv.home/.HomeOverride:com.omarchytv.home/.KeyMapService
adb shell settings put secure accessibility_enabled 1
```

This replaces the list of enabled services, so add any other accessibility app you use to it. Fire TV clears the
setting on every reboot: see #58.

## Link after a reboot on 1-2 GB TVs (#143)

On TVs with more than 2.5 GB of RAM, Link starts at boot. On 1-2 GB TVs it starts when OmarchyTV opens, which is
right away if OmarchyTV is your Home. If agents must reach the TV before that, set
**Settings › Omarchy Link › Keep Link reachable after boot** to *on*, or run `omarchy-link tv keepalive on` on the PC.

Some TVs (for example Sharp in standby) turn Wi-Fi off while they sleep. Nothing on the TV can answer then. Use
Ethernet, or wake the TV first.
