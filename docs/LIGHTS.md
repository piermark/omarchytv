# Ambilight & Lights: Philips Hue and Home Assistant

OmarchyTV can colour your room lights with the active theme. I tested this on real hardware on Oct 31, 2026: on a Sony BRAVIA 4K GB (Android TV 9) running 2.13.7-testing, OmarchyTV found the Philips Hue bridge on the LAN automatically, paired with it, and the lights synced to the theme's colour. I haven't tested other TV models yet.

## How it works

- **Discovery:** `Find Hue Bridge` asks Philips' N-UPnP service (`https://discovery.meethue.com/`) for the bridges on your network. You can also type the bridge address under `Bridge IP`.
- **Pairing:** you press the round link button on the bridge, then `Pair` within 30 seconds. The bridge returns a username, and OmarchyTV saves it on the TV only. If you press `Pair` too early or too late, the bridge answers "press the link button on the bridge" (error 101). Just try again.
- **Lights:** OmarchyTV drives one Hue group, which can be an Entertainment area, a room or a zone. Entertainment areas are listed first. With Home Assistant, it drives the `light.` entities you list.
- **Theme sync:** while any OmarchyTV screen is visible (Home, Cinemarchy, player, Settings), the lights take the theme's accent colour at the Ambilight `Brightness`. Changing theme repaints them, and Cinemarchy dims them.
- **Leaving OmarchyTV:** when another app takes over (Netflix, the YouTube app…) or the screen turns off, the `When leaving the launcher` policy runs after about 2.5 s:
  - `Turn off` (default). In Italian: *Spegni*.
  - `Restore previous`: puts back the colours and state the lights had before OmarchyTV painted them. In Italian: *Ripristina come prima*.
  - `Keep on`: leaves the lights as they are. In Italian: *Lascia accese*.
  When you come back, the theme colour is painted again. If Ambilight is off, OmarchyTV never touches the hub, so leaving never turns your lights off.

## How to connect it (Android TV)

Menu path: **Settings → Ambilight & Lights → Lights hub**. In Italian it's **Quando esco dal launcher** for the leave policy.

### Philips Hue
1. Under `Hub`, pick Philips Hue.
2. Select `Find Hue Bridge` and choose your bridge from the list. If none is found, enter the address under `Bridge IP`.
3. Press the round button on the bridge, then select `Pair` within 30 s. It now shows `paired`.
4. Under `Entertainment area / room`, choose the area, room or zone to colour.
5. Select `Test hub`, then `Apply theme colours now`.
6. Choose `When leaving the launcher`: `Turn off` / `Restore previous` / `Keep on`.

### Home Assistant
1. Under `Hub`, pick Home Assistant.
2. Fill in `Home Assistant URL`, for example `http://homeassistant.local:8123`.
3. Fill in `Access token`: in Home Assistant, go to your profile → Security → Long-lived access tokens → Create token, and paste it (`<your-long-lived-token>`). It is stored on the TV only and shown masked.
4. Fill in `Lights (entity ids)`, for example `light.living_room, light.tv_strip`.
5. Select `Test hub`, then `Apply theme colours now`.

`Forget hub` removes the saved address and credentials from the TV, after asking you to confirm.

### LG webOS / Samsung Tizen
Go to **Settings → Ambilight & Lights**. Cycle `Lights hub` (off → Philips Hue → Home Assistant).
- **Hue:** `Find Hue Bridge` (or type the address), press the bridge button, then `Pair`.
- **Home Assistant:** enter the URL, a long-lived token and the `light.` entity ids.

## Troubleshooting

- **"not found" after `Find Hue Bridge`:** the TV needs internet access for the discovery service, and the bridge must be on the same LAN. Otherwise, type the bridge address shown in the Hue app (Settings → Bridges).
- **`Pair` says "press the link button":** press the round button on the bridge and select `Pair` within 30 s.
- **`setup incomplete`:** a step is missing, for example no area chosen, no token or no entities.
- **Lights don't change:** check that the Ambilight mode isn't `off`, run `Test hub`, and make sure the chosen room actually contains the lights.
- **Lights turn off when I open Netflix:** that's the default `Turn off` policy. Pick `Keep on` or `Restore previous`.
- **Home Assistant 401:** the token is wrong or was revoked. Create a new long-lived token.
- **Start over:** use `Forget hub`.
