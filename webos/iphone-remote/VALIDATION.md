# Validation — 2026-11-03

Hardware: LG OLED77C5AUA, webOS 10.3.1-3007, Node 16.20.2.
Backend 1.1.0, Home integration release `2.13.11-remotes2`.

- 96 upstream webOS tests passed (`node --test webos/tests/*.test.js`).
- 21 existing system Home integration tests passed separately.
- Optional webOS IPK built successfully with a bundled, checksummed backend.
- Locked dependency audit: zero known advisories at validation time.
- Installer tests verified identity retention through update, failed-startup
  rollback, corrupt-bundle refusal, disable/re-enable and service removal.
- The live TV migrated the standalone service to versioned releases. Its paired
  Home survived, and the owner confirmed Control Center navigation still works.
- Connected settings rendered with D-pad actions and fit within the TV viewport.
- Pairing reset displayed confirmation. Cancel retained the existing pairing.
- Requests without the management token returned HTTP 403.
- System Home's direct Homebrew exec call was denied. A narrow authenticated
  management adapter was implemented for that separate Home integration.
- A temporary ordinary web app using the normal launcher permissions successfully
  called Homebrew Channel as root. This verifies the permission boundary; it is
  not a claim that the complete ordinary-IPK lifecycle was hardware-qualified.
- The temporary permission-test app was removed after qualification.
- Existing device-specific button behavior was retained outside the packaged code.

Not tested: deep-standby wake, physical AC removal, all playback applications,
all audio/CEC routes, older webOS versions, or full ordinary-IPK install/update/
uninstall on an otherwise clean TV. Reset/removal destructive paths were tested
with isolated fixtures; the user's real pairing was not reset. The original
Control Center implementation and the integrated migration were both confirmed
working by the owner.
