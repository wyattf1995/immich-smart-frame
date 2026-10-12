# FrameOS touchscreen Home

`examples/frameos/frameos-touchscreen-panel.html` opts into the dashboard mounted
at `/frameos-touchscreen`; it does not replace the existing `/wall-panel` used by
the Lenovo frames. Its Home view is a custom interface, with layout and controls
owned by FrameOS. Home Assistant supplies authenticated state and services behind
that interface. It is no longer just a color theme for the thermostat card.

The custom Home JavaScript and CSS sit beside the HTML under `/config/www`.
Deploy all three together, verify their hashes, and change their versioned URLs
when their contents change. Also bump the native `TOUCHSCREEN_WRAPPER_VERSION`
for an APK release: Home Assistant's serviceworker can retain an older wrapper
under a fixed query even after the HTTP cache is refreshed. Do not clear profile
or authentication data to refresh visual assets.

The light daytime design follows the user's Wes Bos reference: paper background,
large clock, clear rules and a yellow next-event card. Dark styling follows local
evening hours. Rendered household data comes from the existing connection;
missing entities must stay unavailable rather than being filled with mock data.
The separately developed native Today screen supplies photo previews and the
navigation rail; this Home view owns the actual home controls.

## Authentication and lifecycle

The one same-origin iframe retains the existing HA session. On Home, bounded
bridge discovery finds the frontend's in-memory `home-assistant.hass` object.
Once available, FrameOS renders its own DOM and hides the iframe. No credentials
are read, copied or stored and no second API connection is opened. HA's `hass`
property is an integration seam that must be checked after frontend upgrades.
If discovery is unavailable, the existing HA view remains usable as a fallback.
Authentication routes always restore the visible iframe. Navigation, new iframe
documents and page teardown dispose the custom view and its refresh work.

## Thermostat and existing routes

The thermostat entity is `climate.entryway_entryway`. Controls are derived from
its actual supported modes, target attributes, limits and step. Off does not
silently enable heating or cooling. Temperature edits require the explicit Apply
action; dual targets must preserve their valid low/high ordering. Only explicit
user actions may call the climate service, with pending/error feedback and
connection guards. Do not send actual HVAC changes merely to validate the UI.
Use fake service callbacks for payload tests; physical write acceptance requires
an intentional user temperature or mode choice.

The companion YAML retains existing HA Calendar and Cameras routes. The camera
chooser uses tiles only; each detail subview contains one live stream, avoiding
four concurrent streams on the 1 GiB PF1007. These routes remain separate from the
custom Home and from the existing Lenovo wall panel.

## Validation

Run the custom Home model/interaction suite, wrapper integration lifecycle suite,
existing wrapper tests and structure/privacy validator. Keep test changes separate
from guarded source changes; retain source-only red/green evidence. Verify Home,
auth routing, navigation, touch target sizes and no clipping on the actual QA frame
(1280×800, Android 6), then check state/connection failure behavior before
bedroom rollout. Preserve authenticated camera playback and one-stream cleanup.
