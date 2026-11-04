# Campfire — an Omarchy theme

![Campfire](preview.png)

A night by the lake: moonlight, pines and the fire. The palette is sampled from the scene of
[OmaFire](https://github.com/wesleygrimes/omafire) by Wes Grimes, and the wallpaper is a frame of his fire
burning in that scene. Made by [OmarchyTV](https://github.com/piermark/omarchytv), where the same theme
runs on the TV (Android TV, LG webOS, Samsung Tizen) with OmaFire as the live wallpaper.

## Install on your Omarchy PC

```
omarchy-theme-install https://github.com/piermark/omarchy-campfire-theme
```

Or by hand: copy this folder to `~/.config/omarchy/themes/campfire/`, then run

```
omarchy-theme-set campfire
```

(or pick **campfire** from Omarchy's theme menu).

## What it themes

- `colors.toml`: the Omarchy palette (terminal, Waybar, Walker, Mako, Hyprlock and the rest through Omarchy's templates)
- `hyprland.conf`: the active window border burns from fire orange to embers
- `btop.theme`, `icons.theme` (Yaru), `backgrounds/01-campfire.png`, `preview.png`

## The live fire and its sound

A theme can only set colours and a still wallpaper. For the fire burning and crackling on the PC, with its sound,
install Wes Grimes' **OmaFire** (see its README): https://github.com/wesleygrimes/omafire

On the TV, OmarchyTV shows the campfire as the live wallpaper of this theme; turn on
**Wallpaper → OmaFire sound** for the crackle and the night.

## With OmarchyTV

With omarchy-link paired, the TV and the PC follow each other: pick **campfire** on the PC and the TV switches
(and the other way round, with the TV switch "Also change the PC theme"). The TV's random wallpaper sometimes
brings the fire with this theme too.

## Credits and licence

- Theme: © 2026 Marco Porro ([x.com/MarcoPorro](https://x.com/MarcoPorro)), OmarchyTV, MIT.
- Scene, fire and sounds: OmaFire © 2026 Wes Grimes, MIT. The wallpaper is a frame of his `fire.js` drawing his
  `campsite-approved.webp`, cropped to 16:9 and scaled up without smoothing.

See [LICENSE](LICENSE).
