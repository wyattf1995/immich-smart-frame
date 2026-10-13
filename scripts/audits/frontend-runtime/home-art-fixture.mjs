/**
 * Synthetic, same-origin fixture routes for frontend runtime profiling.
 * This module serves checked-in web source and generated, non-personal data only.
 * It is not a production HTTP server and intentionally uses SVG bytes with an
 * image/svg+xml MIME type for .jpg fixture paths; it does not exercise JPEG decode.
 */
import { readFile } from "node:fs/promises";
import path from "node:path";

const HOME_STATE_COUNT = 320;
const DEFAULT_ART_COUNT = 200;
const MAX_ART_COUNT = 2048;
const ART_IMAGE_HASH = /^[a-f0-9]{64}$/;

const sourceFiles = Object.freeze({
  "/home": ["examples", "frameos", "frameos-touchscreen-panel.html", "text/html; charset=utf-8"],
  "/local/frameos-custom-home.js": ["examples", "frameos", "frameos-custom-home.js", "text/javascript; charset=utf-8"],
  "/local/frameos-custom-home.css": ["examples", "frameos", "frameos-custom-home.css", "text/css; charset=utf-8"],
  "/local/frameos-home-forecast.js": ["examples", "frameos", "frameos-home-forecast.js", "text/javascript; charset=utf-8"],
  "/art/": ["examples", "frameos-art", "index.html", "text/html; charset=utf-8"],
  "/art/index.html": ["examples", "frameos-art", "index.html", "text/html; charset=utf-8"],
  "/art/art.js": ["examples", "frameos-art", "art.js", "text/javascript; charset=utf-8"],
  "/art/art.css": ["examples", "frameos-art", "art.css", "text/css; charset=utf-8"],
});

export const fixtureMetadata = Object.freeze({
  homeStateCount: HOME_STATE_COUNT,
  defaultArtCount: DEFAULT_ART_COUNT,
  maxArtCount: MAX_ART_COUNT,
  imageFormat: "SVG payload served with image/svg+xml at a .jpg URL; not a JPEG decoder test.",
  routes: Object.freeze([
    "/home",
    "/local/frameos-custom-home.js",
    "/local/frameos-custom-home.css",
    "/local/frameos-home-forecast.js",
    "/frameos-touchscreen/home?kiosk",
    "/frameos-touchscreen/cameras?kiosk",
    "/frameos-touchscreen/calendar?kiosk",
    "/art/?count=200",
    "/art/collection.json",
    "/art/images/<64-hex>.jpg",
  ]),
  interactionSelectors: Object.freeze({
    hostIframe: "#panel",
    customHome: "#custom-home",
    homeThermostat: "[data-home-action='set-temperature']",
    homeHvacMode: "[data-home-action='set-hvac-mode']",
    artGallery: "#art-gallery",
    artNext: "[data-art-action='next']",
    artPrevious: "[data-art-action='previous']",
  }),
});

const send = (res, status, contentType, body) => {
  res.statusCode = status;
  res.setHeader("Content-Type", contentType);
  res.setHeader("Cache-Control", "no-store");
  res.end(body);
};

const fixtureCount = (url, req) => {
  const direct = url.searchParams.get("count");
  let candidate = direct;
  if (!candidate) {
    try { candidate = new URL(req.headers.referer || "", "http://fixture.invalid").searchParams.get("count"); }
    catch { candidate = null; }
  }
  const count = Number(candidate || DEFAULT_ART_COUNT);
  return Number.isInteger(count) && count > 0 ? Math.min(count, MAX_ART_COUNT) : DEFAULT_ART_COUNT;
};

const hashFor = (number) => number.toString(16).padStart(64, "0");
const artCollection = (count) => JSON.stringify({
  version: 1,
  title: "Synthetic runtime-art collection",
  artworks: Array.from({ length: count }, (_, index) => ({
    id: `synthetic-art-${index + 1}`,
    image: `images/${hashFor(index + 1)}.jpg`,
    title: `Synthetic landscape ${index + 1}`,
    artist: "Fixture generator",
    year: String(1870 + (index % 45)),
    sourceUrl: "https://example.invalid/synthetic-art",
  })),
});

const fixtureImage = (hash) => `<?xml version="1.0" encoding="UTF-8"?>
<svg xmlns="http://www.w3.org/2000/svg" width="3840" height="2160" viewBox="0 0 3840 2160" role="img" aria-label="Synthetic high-resolution art fixture ${hash.slice(0, 8)}">
  <defs><linearGradient id="sky" x1="0" y1="0" x2="1" y2="1"><stop stop-color="#6ca6d9"/><stop offset="1" stop-color="#f0bf76"/></linearGradient></defs>
  <rect width="3840" height="2160" fill="url(#sky)"/><path d="M0 1480 L820 930 L1600 1530 L2440 770 L3840 1500 V2160 H0Z" fill="#47745a"/>
  <path d="M0 1760 C650 1530 1100 1950 1750 1700 S3000 1530 3840 1830 V2160 H0Z" fill="#294c42"/>
  <text x="96" y="2020" fill="#fff" font-family="sans-serif" font-size="72">Synthetic runtime fixture ${hash.slice(0, 12)}</text>
</svg>`;

const homeFixture = () => `<!doctype html>
<html lang="en"><head><meta charset="utf-8"><title>Synthetic Home Assistant fixture</title></head>
<body><home-assistant></home-assistant><section id="fixture-native-home"><h1>Today</h1><h2>Up next</h2><h2>Home status</h2><h2>Comfort</h2></section>
<script>
(() => {
  const counters = { stateReads: 0, callService: 0, callWS: 0, stateIdentityReplacements: 0 };
  let churn = false;
  const baseStates = {};
  for (let index = 0; index < ${HOME_STATE_COUNT}; index += 1) baseStates['sensor.synthetic_' + index] = { entity_id: 'sensor.synthetic_' + index, state: String(index), attributes: { friendly_name: 'Synthetic sensor ' + index } };
  Object.assign(baseStates, {
    'climate.entryway_entryway': { entity_id: 'climate.entryway_entryway', state: 'heat', attributes: { friendly_name: 'Entryway', current_temperature: 69, temperature: 70, target_temp_step: 1, hvac_modes: ['off','heat','cool'], temperature_unit: '°F' } },
    'weather.forecast_home': { entity_id: 'weather.forecast_home', state: 'sunny', attributes: { temperature: 72, temperature_unit: '°F' } },
    'weather.home': { entity_id: 'weather.home', state: 'sunny', attributes: { temperature: 72, temperature_unit: '°F' } },
    'calendar.family': { entity_id: 'calendar.family', state: 'on', attributes: { friendly_name: 'Family', message: 'Synthetic family event', start_time: '2026-10-12T10:00:00', end_time: '2026-10-12T11:00:00', all_day: false } },
    'calendar.synthetic_one': { entity_id: 'calendar.synthetic_one', state: 'on', attributes: { friendly_name: 'Synthetic One', message: 'Fixture event', start_time: '2026-10-12T12:00:00', end_time: '2026-10-12T13:00:00', all_day: false } },
    'calendar.synthetic_two': { entity_id: 'calendar.synthetic_two', state: 'off', attributes: { friendly_name: 'Synthetic Two' } },
    'calendar.synthetic_three': { entity_id: 'calendar.synthetic_three', state: 'off', attributes: { friendly_name: 'Synthetic Three' } },
    'camera.backyard_backyard_camera': { entity_id: 'camera.backyard_backyard_camera', state: 'idle', attributes: { friendly_name: 'Backyard' } },
    'camera.dining_room_dining_room_camera': { entity_id: 'camera.dining_room_dining_room_camera', state: 'idle', attributes: { friendly_name: 'Dining room' } },
    'camera.garage_garage_camera': { entity_id: 'camera.garage_garage_camera', state: 'idle', attributes: { friendly_name: 'Garage' } },
    'camera.living_room_living_room_camera': { entity_id: 'camera.living_room_living_room_camera', state: 'idle', attributes: { friendly_name: 'Living room' } },
  });
  const connection = { connected: true };
  const hass = { connected: true, connection, config: { unit_system: { temperature: '°F' } },
    callService(domain, service, data) { counters.callService += 1; if (domain === 'climate' && data && data.entity_id === 'climate.entryway_entryway') { if (service === 'set_temperature' && Number.isFinite(Number(data.temperature))) baseStates['climate.entryway_entryway'].attributes.temperature = Number(data.temperature); if (service === 'set_hvac_mode' && typeof data.hvac_mode === 'string') baseStates['climate.entryway_entryway'].state = data.hvac_mode; } return Promise.resolve(); },
    callWS() { counters.callWS += 1; return Promise.resolve({ response: { 'weather.forecast_home': { forecast: [{ datetime: '2026-10-12T09:00:00-07:00', condition: 'sunny', temperature: 72, templow: 56, precipitation_probability: 0 }, { datetime: '2026-10-13T09:00:00-07:00', condition: 'partlycloudy', temperature: 71, templow: 55, precipitation_probability: 10 }, { datetime: '2026-10-14T09:00:00-07:00', condition: 'rainy', temperature: 63, templow: 51, precipitation_probability: 70 }] } } }); },
  };
  Object.defineProperty(hass, 'states', { enumerable: true, get() { counters.stateReads += 1; if (!churn) return baseStates; counters.stateIdentityReplacements += 1; return Object.assign({}, baseStates); } });
  document.querySelector('home-assistant').hass = hass;
  window.__fixture = Object.freeze({ counters, setStatesIdentityChurn(value) { churn = Boolean(value); }, getStatesIdentityChurn() { return churn; }, hass });
})();
</script></body></html>`;

const routePlaceholder = (kind) => `<!doctype html><html><head><meta charset="utf-8"><title>Synthetic ${kind} fixture</title></head><body><main data-fixture-route="${kind}"><h1>${kind === "calendar" ? "ha-full-calendar" : "Camera chooser"}</h1><p>Fixture route only; no camera or calendar data is real.</p></main></body></html>`;

/**
 * Handles Home and Art profiling routes. `url` must be a WHATWG URL.
 * Returns true when a response was sent, false for an unowned route.
 */
export async function handleHomeArt(req, res, url, repoRoot) {
  const pathname = url.pathname;
  const file = sourceFiles[pathname];
  if (file) {
    const [first, second, third, contentType] = file;
    try { send(res, 200, contentType, await readFile(path.join(repoRoot, first, second, third))); }
    catch { send(res, 500, "text/plain; charset=utf-8", "Fixture source file unavailable"); }
    return true;
  }
  if (pathname === "/frameos-touchscreen/home") { send(res, 200, "text/html; charset=utf-8", homeFixture()); return true; }
  if (pathname === "/frameos-touchscreen/cameras") { send(res, 200, "text/html; charset=utf-8", routePlaceholder("cameras")); return true; }
  if (pathname === "/frameos-touchscreen/calendar") { send(res, 200, "text/html; charset=utf-8", routePlaceholder("calendar")); return true; }
  if (pathname === "/art/collection.json") { send(res, 200, "application/json; charset=utf-8", artCollection(fixtureCount(url, req))); return true; }
  const image = pathname.match(/^\/art\/images\/([a-f0-9]{64})\.jpg$/);
  if (image && ART_IMAGE_HASH.test(image[1])) { send(res, 200, "image/svg+xml; charset=utf-8", fixtureImage(image[1])); return true; }
  return false;
}
