# Optional iPhone Remote for rooted webOS

Adds **Workspace 5 → Remotes → iPhone Remote**. Enable installs the bundled
backend on the TV and displays a HomeKit QR code. Scan it using iPhone Camera
or Home → Add Accessory, then use **Control Center → Apple TV Remote →
OmarchyTV**. Apple Home's wheel selects apps/inputs; it is not the remote.
Apple may label the accessory uncertified and require “Add Anyway”.

The service runs entirely on the TV. No PC, cloud service, LG account, LG
AirPlay or LG agreement acceptance is involved. It does not change LG consent
settings. Homebrew Channel, root access and Node 16+ are required. The backend
has been exercised on an LG OLED77C5AUA running webOS 10.3.1-3007. Other firmware
and ordinary app identities require their own hardware validation.

## Building

The default webOS package stays unchanged in its dependency requirements.
Build the optional bundle on your development machine (Node 16+, npm and GNU
tar required), then package it together with the normal launcher:

```sh
OMARCHY_IPHONE_REMOTE=1 ./webos/build-ipk.sh /path/to/output
node --test webos/tests/*.test.js
```

`build-ipk.sh` installs the exact locked dependencies on the build machine,
without install scripts, and embeds a deterministic, checksummed backend archive.
There are no npm downloads on the TV. Dependency licences are included in the
archive. The HAP implementation is `hap-nodejs`; QR rendering uses `qrcode`.
These dependencies belong to the optional backend, not the Android launcher.

The regular settings module is ES5 syntax, uses the existing modal/theme and
D-pad system, and does not expose this entry on other TV platforms. Root-only
work runs through the same Homebrew Channel exec service as existing rooted
settings. A missing bundle/root/runtime produces an actionable message.

## Installation and lifecycle

Opening settings reads status; it does not enable a new remote. **Enable**
verifies and installs the bundled version. Once installed, opening OmarchyTV
synchronizes newer bundled versions while preserving identity and pairing keys.
Concurrent setup actions are serialized by a filesystem lock. Versioned code
lives in `/var/lib/omarchytv-remote/releases`; `app` points to the current version.
Failed startup restores the previous code. The first standalone 1.0 installation
is migrated without replacing its identity or pairing store.

Boot hook: `/var/lib/webosbrew/init.d/66-omarchytv-remote`. The supervisor has a
single-instance lock, bounded retries and bounded logs. OmarchyTV's own system
Home helper is not needed. Home/Exit targets the installed launcher identity.

- **Turn off:** unpublishes the accessory and keeps pairing for re-enable.
- **Reset pairing:** asks for confirmation, revokes all paired Homes and creates
  a new setup code. Remove the old accessory from Apple Home, then pair again.
- **Remove remote service:** asks for confirmation, stops it, removes the owned
  boot hook and installed code. Pairing data stays for a later reinstall.
- Remove the remote service before uninstalling the launcher IPK. Since webOS
  does not give this web app an uninstall callback, uninstalling the IPK alone
  is not a substitute for removing this optional rooted service.

Private configuration, setup code, management token and HAP pairing keys stay
under `/var/lib/omarchytv-remote` (root-only directory). No private state is
packaged or logged. Connected status omits pairing material. Management listens
on loopback port 18767 with a private bearer token and exact Host check; the UI
reads it through a local privileged controller, not an embedded shared token.
HomeKit uses port 51826 and mDNS. The management API has only status, enable,
disable and reset; it cannot execute arbitrary commands.

## Coverage and limitations

Navigation, Select, Back, Home/Exit, settings, volume, mute, app/input selection
and media keys are mapped to the TV's own services. Repeated keys are ordered
and a failed delivery does not block later keys. Playback toggle reads media
state when available. Volume keys preserve normal webOS routing, including CEC
where the TV supports it. Individual apps and audio routes determine support.

This is a HomeKit Television accessory, not Apple TV protocol emulation. It does
not provide Apple TV keyboard sessions, Now Playing artwork, Siri, or touch
coordinates beyond the directional commands iOS sends. A TV-hosted process
cannot wake the TV from a state where its CPU/network are powered down.

Tests cover command mapping, queue recovery, setup UI lifecycle, explicit enable,
reset/removal confirmation, unsupported platforms, update preservation, failed
startup rollback, corrupt bundles and removal with retained identity. The
previous implementation was paired and its Control Center controls confirmed by
the owner. See the integration validation record for the newly packaged build.

## Local integrations

An optional root-owned `local-platform.js` in the persistent directory can
export `createPlatform()` with `call`, `key`, `remote` and `launch` methods. This
lets a custom installation keep additional button behavior outside the shipped
backend. `local-actions.json` may provide extra `inputs` and a `settings` launch
action. These files are never bundled or replaced by updates. They are trusted
local administrator code/configuration, not a remotely writable plugin API.

A system-Home replacement can supply `remoteRequest(action)` in the UI module's
bindings. It must authorize the caller and implement only the documented fixed
operations. The ordinary app uses Homebrew Channel directly; LG's system Home
identity does not have the same permission to call that public service.
