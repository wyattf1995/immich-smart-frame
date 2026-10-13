# FrameOS Touch design handoff

This handoff describes the design surface for **FrameOS Touch**, a separate
Android application for touchscreen frames. It is a source and product brief,
not a release approval or a record of a particular household configuration.

## What is being designed

FrameOS Touch has two deliberate rendering boundaries:

| Surface | Implementation | Design consequence |
| --- | --- | --- |
| Home, Weather, Calendar, Cameras, navigation rail, and Settings return | Native Android Views | These are the primary touch controls. Keep them immediate, readable, and usable with last-known data while a bounded refresh runs. |
| Photos and Art | A retained Gecko surface | These are immersive media experiences. They must not inherit the native rail permanently, and must preserve their separate media/session behavior. |

Do not redesign the old Lenovo-oriented FrameOS activity through this work. It
is a separate product with its own release and interaction model. The Touch app
shares reviewed data and authentication components where appropriate, but does
not share another app's activity, credentials, or media ownership.

## Source map

The main composition and lifecycle owner is
[`TouchFrameActivity`](../frameos/touchframe/src/main/kotlin/com/wyattfleming/touchframe/TouchFrameActivity.kt).
It selects one destination and coordinates native refresh work without making
navigation wait for the network.

| Area | Primary source | Notes for design work |
| --- | --- | --- |
| Native Home | [`TouchHomeView`](../frameos/touchframe/src/main/kotlin/com/wyattfleming/touchframe/ui/TouchHomeView.kt) | Direct climate controls and compact household summaries are native controls, not an embedded HA dashboard. |
| Weather | [`WeatherContentView`](../frameos/app/src/main/kotlin/com/wyattfleming/frameos/ui/WeatherContentView.kt) | Native current conditions and forecast; its optional place label is configured display text, never GPS collection. |
| Calendar | [`TouchCalendarView`](../frameos/touchframe/src/main/kotlin/com/wyattfleming/touchframe/calendar/TouchCalendarView.kt) and [`TouchCalendarWeekPresentation`](../frameos/touchframe/src/main/kotlin/com/wyattfleming/touchframe/calendar/TouchCalendarWeekPresentation.kt) | Week is events-only: seven dates, timed/all-day events, three previews per day, and a locally paged selected-day agenda. Agenda and Month remain available. Tasks are out of scope. |
| Cameras | [`TouchCameraView`](../frameos/touchframe/src/main/kotlin/com/wyattfleming/touchframe/media/TouchCameraView.kt) | Authenticated snapshots are the current product surface; do not imply live video controls. |
| Rail and immersive menu | [`TouchShellLayout`](../frameos/touchframe/src/main/kotlin/com/wyattfleming/touchframe/ui/TouchShellLayout.kt) and [`TouchDestination`](../frameos/touchframe/src/main/kotlin/com/wyattfleming/touchframe/ui/TouchDestination.kt) | Native destinations retain the rail. Photos and Art use the full viewport; a deliberate edge gesture reveals their menu. |
| Settings return | [`TouchSettingsReturn`](../frameos/touchframe/src/main/kotlin/com/wyattfleming/touchframe/ui/TouchSettingsReturn.kt) | Android Settings is an intentional escape hatch with a return path; do not hide it behind a new custom settings screen. |
| Photos and Art media | [`TouchMediaView`](../frameos/touchframe/src/main/kotlin/com/wyattfleming/touchframe/media/TouchMediaView.kt) and [`TouchOfflinePhotos`](../frameos/touchframe/src/main/kotlin/com/wyattfleming/touchframe/media/TouchOfflinePhotos.kt) | Photos has a scoped offline reserve. Art never reads that reserve and must show art-specific unavailable state rather than family-photo fallback. |

Home Assistant supplies authenticated data and service calls; it is not the
frontend for the native Home, Weather, or Calendar screens. The browser surface
is reserved for its documented media uses and the short-lived sign-in flow.

Entity IDs in the existing defaults and legacy examples are installation-specific
configuration conventions, not a discovery mechanism or a universal Home
Assistant schema. Provision explicit climate, calendar and camera entity IDs for
each installation. A design change must preserve persisted configuration and must
not assume that the example room names identify available entities.

## Design target

The intended result is a calm wall-frame interface: warm neutral surfaces,
clear hierarchy, and large controls that are useful from arm's length without
becoming sparse. Preserve the existing functional boundaries while improving
visual quality:

- Make direct controls visibly actionable, with generous targets and useful
  selected, pending, offline, and error states.
- Keep the primary native rail simple and consistent. Avoid a second in-content
  navigation rail.
- Preserve **Photos as the default experience**. Art is an easy-to-reach,
  separate mode for artwork, not a replacement photo feed.
- Treat the Week calendar as a visual overview first. All seven weekdays need
  to be legible together; tapping a day reveals its complete event agenda. It
  must never invent task data or turn an empty calendar into a fake schedule.
- Keep the Weather screen focused on current conditions and a useful forecast.
  A location label is optional display metadata, not a location-permission
  feature.
- Design day and night palettes together. Selected/today states, long event
  titles, camera status, and disabled controls must remain legible in both.

The original small touchscreen was observed at 1280×800 on API 23; a larger
touchscreen was observed at 1920×1080 on API 27. Those are useful reference
canvases, not a promise that every device at either API level is accepted.
Retain layout behavior at narrower effective widths, font scaling, and screen
rotation only where the existing product supports them.

## Interaction and performance guardrails

- A tap on a native control should update its visible state promptly. Do not
  rebuild an entire destination merely to update a clock, selection, or one
  pending control.
- Calendar day selection and detail-page navigation are local state changes;
  they must not refetch the same bounded range. Keep event previews bounded and
  let vertical scrolling reveal the complete selected-day agenda.
- Maintain lifecycle generations and cancellation boundaries. A stale network
  response must not repaint a destination that the user has left.
- Do not create competing Gecko sessions, duplicate media attachments, or a
  hidden browser Home page. Photos' offline capture/reserve remains scoped to
  Photos only.
- Preserve authentication and configuration state through visual changes,
  normal app restarts, and app updates. Do not add token display, logging, or
  credential-copy paths to support a mockup.
- Keep accessibility descriptions, touch targets, text contrast, and focus
  order meaningful. Test long titles, unavailable data, and empty calendar
  days rather than only ideal populated screens.

## Acceptance for a design proposal

A design proposal is ready for implementation review when it supplies native
layouts for Home, Weather, Week/Agenda/Month Calendar, Cameras, the rail, and
immersive Photos/Art transitions; documents day/night and loading/offline
states; and identifies the target source seams above. It should also show:

1. a readable 1280×800 reference layout and a 1920×1080 layout without
   assuming either is a universal device guarantee;
2. direct-touch climate and calendar interactions, including a busy-day
   calendar page and empty/offline states;
3. an edge-menu path from Photos and Art back to the rail, while keeping media
   full-screen by default; and
4. no changes to OAuth, configuration persistence, offline photo scoping, or
   Home Assistant service allowlists unless separately reviewed.

Use the existing [Android app build guidance](android-apps.md),
[release gates](releasing.md), and [compatibility guide](compatibility.md) for
implementation and validation. Design review is not an APK build, signing, or
hardware acceptance gate.

## Evidence boundary

A prior small-frame verification covered Calendar and Weather on source
`e7403b4f`. The combined Touch candidate at `9cde0afd` is newer and has not
thereby gained installation or device-acceptance status. Keep candidate source,
compiled artifacts, signed artifacts, and device evidence distinct in future
design and release notes. Household screenshots, authentication, device
identifiers and operational receipts stay in the private handoff; use synthetic
events, artwork and camera placeholders for public design examples.
