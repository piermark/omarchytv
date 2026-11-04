# Contributing

Thanks for helping more TVs lose their ads. Five kinds of contributions are especially welcome.

## 1. Add your TV model

Every brand ships different bloat. If you ran the prompt (or did it by hand) on a TV that isn't in
`examples/`, add it:

1. Create `examples/<brand>-<model>/debloat.txt`. Copy the format from
   `examples/xiaomi-mi-tv-4a/debloat.txt`: one package per line, grouped with a comment that says
   what the group is, and a trailing comment on anything non-obvious ("re-enable for firmware
   updates", "needed for HDMI-CEC", etc.).
2. Put the exact model name, Android version and firmware build in the header so people can tell
   whether it matches theirs. `tools/tv.py --probe` prints all three.
3. Only list packages you have actually disabled and lived with for a few days. Note anything that
   broke and what you re-enabled.
4. Add a row to `examples/README.md`.
5. If your TV's Live TV app or stock launcher has a different package name, say so in the header;
   `tools/restore.sh` and `launcher/build-and-install.sh` accept them as arguments.

Never list packages to *uninstall*. Everything in this repo disables with `pm disable-user`, which
is reversible from the TV's own settings if the tools are gone.

## 2. Improve the launcher

The launcher lives in `launcher/` and is deliberately small: plain Java, AndroidX RecyclerView and
DynamicAnimation, no Kotlin, no Compose, no network. It targets TVs with 1 GB of RAM, so:

- No blur, no video backgrounds, no polling, no background services.
- Anything that depends on a specific TV goes in `app/src/main/res/values/config.xml`, not in code.
- Keep D-pad navigation deterministic. Test Up/Down/Left/Right from every edge.
- Verify on a real TV, not only the emulator. The easiest way is remote key injection plus a
  screenshot: `tools/tv.py 'input keyevent KEYCODE_DPAD_DOWN; screencap -p /sdcard/s.png'` then
  `tools/tv.py --pull /sdcard/s.png s.png`. Include a screenshot in your pull request.

Build with `TV_HOST=<ip> launcher/build-and-install.sh` (JDK 17, Android SDK platform 34 and
build-tools 34.0.0; on macOS `brew install openjdk@17 android-commandlinetools` then
`sdkmanager "platforms;android-34" "build-tools;34.0.0"`).

Ideas that fit the direction: an idle screensaver with clock and ambient colour, per-TV icon packs
for apps without banners, a settings screen to edit the dock from the TV, localisation.
Ideas that don't: recommendation rows, content feeds, ads, telemetry.

## 3. Improve the prompt and tools

`PROMPT.md` is what most people will actually use. If your assistant got stuck somewhere, the fix
usually belongs in the prompt as one extra sentence, not in a script. Keep it brand-neutral; put
brand-specific knowledge in `examples/`.

`tools/tv.py` and `tools/b64push.py` exist because the stock `adb` is blocked on recent macOS. If
you find a cleaner workaround, open an issue first.

## 4. Add your remote's buttons

The launcher can remap a remote's extra buttons (single, double, triple or long press), but only on remotes
it knows. Adding one is a data change in `launcher/app/src/main/assets/remotes.json`:

1. Find the remote's vendor and product id and the keycode of the button you want.
2. Check the button really reaches an accessibility service. Some are handled by the system first and can't
   be supported.
3. Add the entry and fill in `notes` with the TV or box, Android version and firmware you verified it on.
4. Run `./gradlew :app:testReleaseUnitTest` in `launcher/`; it checks the file.

`launcher/REMOTES.md` has the commands for each step.

## 5. Fix a translation

OmarchyTV speaks 23 languages. English, Italian and German were written by hand; the other 20 (French, Spanish,
Portuguese for Portugal and Brazil, Dutch, Polish, Swedish, Danish, Norwegian, Finnish, Czech, Hungarian, Romanian,
Greek, Turkish, Ukrainian, Russian, Arabic, Simplified Chinese, Japanese) were machine-translated from English and
are waiting for native speakers. If a label reads wrong in your language:

1. Android TV: edit `launcher/app/src/main/res/values-<lang>/strings.xml` (Portugal is `values-pt`, Brazil
   `values-pt-rBR`, Chinese `values-zh`, Norwegian `values-nb` and its copy `values-no`). Keep every `%1$s` / `%1$d` placeholder, write apostrophes as `\'`.
2. LG webOS / Samsung Tizen: make the same change in `webos/app/i18n.js` under your language.
3. Keep product and Linux words as they are: OmarchyTV, Omarchy Linux, Walker, Hyprland, Cinemarchy, Grok Bot,
   `> search`, `> ask`.
4. Run `./gradlew :app:testReleaseUnitTest` in `launcher/` and `node --test webos/tests/` (they check that every
   language has every string with the same placeholders), and add a screenshot from your TV if you can.

## Pull requests

- One topic per PR. Small is fine.
- Say which TV you tested on.
- No new dependencies in the launcher without discussing it in an issue.
- Be kind in reviews. Most contributors here are doing this for their parents.

By contributing you agree your work is released under the repo's MIT licence.
