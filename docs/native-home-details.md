# Native Home details

Home reuses the existing cached RemotePanes feed for additional details inside its
native widgets. The weather and PC widgets keep their existing styling. The Mac
widget uses the same CPU/RAM rows below PC. AI credits sit below the terminal and
use its width; the remote can scroll the panel when all rows do not fit.

The feed can include these reserved pane IDs:

| ID | Lines |
| --- | --- |
| `native-pc-details` | `uptime`, `load`, `memory`, `disk`, `network`, `services` |
| `native-mac` | `cpu`, `ram`, plus the same detail labels |
| `native-ai` | Account/window label and a value such as `80%\|2h` (remaining allowance and reset) |

Use percentages from 0 to 100 for CPU, RAM, and remaining allowance. Invalid or
missing metrics must not be interpreted as zero usage. Native AI rows use low
remaining allowance as the warning condition. Keep detail values short enough
for the native PC column; do not put composite dashboard images in these panes.
The reserved panes do not also render in the generic lower row.

This consumes the configured feed's existing cache and refresh interval. It adds
no endpoint, poller, service, pairing flow, or remote-control permission.

Set `homeWidgets.grokbot` to `false` to hide the Grok Bot widget on Home. It
defaults to `true` and does not disable the plugin or its other entry points.
