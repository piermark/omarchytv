# Contributing

Thanks for helping a TV lose its ads and look like Omarchy. This page says what the project is, where things live,
how to run the tests, and what a good pull request looks like here.

By contributing you agree your work is released under the repo's MIT licence (see `LICENSE`), and that any theme,
font, sound or code you bring from somewhere else is credited (see the last section).

## The project

OmarchyTV is an Android TV launcher that makes a TV look and behave like an Omarchy Linux desktop: Omarchy stock
themes, tiling-friendly workspaces, Walker, the `user@host:~$` prompt, an ASCII screensaver. Around the launcher there
are two ports of the same interface (LG webOS and Samsung Tizen, in JavaScript) and PC-side plugins that talk to the
TV over the LAN. The TV is the server and the PC the client; nothing is published to a store, and the launcher touches
the network only for the features you turn on.

## Layout

| Path | What |
|---|---|
| `launcher/` | the Android TV app (Java, Gradle) |
| `webos/` | the LG webOS port (`app/`, JavaScript) |
| `tizen/` | the Samsung Tizen port (JavaScript) |
| `pc-plugin/omarchy-link/` | the Omarchy bar widget, the remote and the CLI (Python, standard library only) |
| `pc-plugin/omarchy-tv/` | themes, updates and the MCP server (Python) |
| `docs/` | user guides and the wire protocol |
| `themes/`, `examples/`, `tools/` | theme notes, debloat examples, helper scripts |

## 1. Propose a change

1. **Open an issue first** for anything bigger than a typo: what the TV does, what you expected, and the TV model,
   Android version and firmware you saw it on. A fix for a bug nobody reported is hard to review.
2. **One topic per pull request.** A theme is one PR, a language is one PR, a screensaver fix is one PR. Small is
   fine: a PR that does four things cannot be reviewed, and cannot be reverted on its own if one of them breaks a TV.
3. Branch from `main` and leave `main` alone; say which TV you tested on in the description.
4. Don't add dependencies: no new Gradle dependency in the launcher, no npm package in `webos/` or `tizen/`, nothing
   outside the Python standard library in `pc-plugin/`. If you think you need one, discuss it in the issue first.
5. Don't bump `versionCode`, `versionName`, `CHANGELOG.md` or the version in `README.md`; the maintainer does that
   when cutting a release.

## 2. Code style

- **Launcher (Java):** Java 17, no Kotlin, no Compose. AndroidX RecyclerView and DynamicAnimation are the only
  libraries. The app has to stay comfortable on a TV with 1 GB of RAM: no blur, no video backgrounds, no polling
  loops, no background services. Keep D-pad navigation deterministic: walk Up/Down/Left/Right into and out of every
  screen, and remember that OK must stay reachable while an on-screen keyboard is up (see `DialogInput`). Anything
  that depends on one TV model goes in `app/src/main/res/values/config.xml`, not in code. Comments explain why, not
  what, and are in English.
- **webOS and Tizen (JavaScript):** vanilla, no build step, no framework. The TV browsers are old: check syntax and
  APIs against the engine in `webos/` before using them.
- **PC plugins (Python):** standard library only, and no `shell=True` in subprocess calls.
- **Docs:** English, dry, present tense.

## 3. Run the tests

From the repo root:

```bash
cd launcher && ./gradlew :app:testDebugUnitTest            # JUnit, no device needed
node --test webos/tests/*.test.js                          # webOS port
node --test tizen/*.test.cjs                               # Tizen port
bash pc-plugin/omarchy-link/tests/run.sh                   # link plugin, against a mock TV (python3, ffmpeg)
cd pc-plugin/omarchy-tv/tests && python3 -m unittest        # themes, updates, media bridge, MCP
```

The launcher tests read sources and assets directly (`AndroidManifest.xml`, `omarchy_themes.json`, every
`values-*/strings.xml`), so a missing permission or a broken translation fails without an emulator.

**Test on a real TV before saying something works.** The emulator has a different WebView, a different decoder and no
remote, and several past bugs existed on one brand only. For the launcher, install with
`TV_HOST=<ip> launcher/build-and-install.sh`, exercise the change with the remote, and put a screenshot in the pull
request. Say which TV, Android version and firmware you used.

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

## 6. Credits

Third-party themes, fonts, sounds, artwork and code must be credited where the licence requires it:

- add the project, its author and its licence to `THIRD-PARTY.md`;
- add the attribution line to the Credits section of `README.md`, with a link to the original;
- keep the original licence file next to the vendored files where one exists;
- don't add anything whose licence forbids redistribution or needs a paid account to run.

Bundled themes are MIT Omarchy themes; `omaFire` (`fire.js`, `sound.js`, `night.js`) is Wes Grimes' work and
`qrcode.js` is Kazuhiko Arase's. Leave those files as they are.

## Pull requests

- One topic per PR. Small is fine.
- Say which TV you tested on, and what you could not test.
- No new dependencies in the launcher without discussing it in an issue.
- Be kind in reviews. Most contributors here are doing this for their parents.

By contributing you agree your work is released under the repo's MIT licence.
