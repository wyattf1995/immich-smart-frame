/* FrameOS custom Home: receives Home Assistant's in-memory hass object from its host. */
(() => {
  "use strict";
  const CLIMATE = "climate.entryway_entryway";
  const WEATHER = "weather.home";
  const FAMILY = "calendar.family";
  const safe = (value) => String(value ?? "").replace(/[&<>"]/g, (char) => ({ "&":"&amp;", "<":"&lt;", ">":"&gt;", '"':"&quot;" })[char]);
  const finite = (value) => Number.isFinite(Number(value)) ? Number(value) : null;
  const clampTemperature = (value, limits) => {
    const number = finite(value); if (number === null) return null;
    const min = finite(limits.min); const max = finite(limits.max); const step = finite(limits.step) || 1;
    if (min === null || max === null || min > max) return null;
    return Math.min(max, Math.max(min, Math.round((number - min) / step) * step + min));
  };
  const climateModel = (entity, unit) => {
    const attributes = entity?.attributes || {}; const modes = Array.isArray(attributes.hvac_modes) ? attributes.hvac_modes.filter((mode) => ["off", "heat", "cool", "heat_cool"].includes(mode)) : [];
    const mode = entity?.state || "unknown"; const dual = mode === "heat_cool";
    const limits = { min: attributes.min_temp, max: attributes.max_temp, step: attributes.target_temp_step || 1 };
    return { mode, modes, dual, unit: unit || "°", limits, temperature: clampTemperature(attributes.temperature, limits), low: clampTemperature(attributes.target_temp_low, limits), high: clampTemperature(attributes.target_temp_high, limits), temperatureEnabled: mode !== "off" && ["heat", "cool", "heat_cool"].includes(mode) };
  };
  const modePayload = (mode) => JSON.stringify({ hvac_mode: mode });
  const setpointPayload = ({ mode, temperature, low, high }) => {
    if (mode === "heat_cool") return finite(low) !== null && finite(high) !== null && Number(low) <= Number(high) ? JSON.stringify({ target_temp_low:Number(low), target_temp_high:Number(high) }) : null;
    return finite(temperature) === null ? null : JSON.stringify({ temperature:Number(temperature) });
  };
  const isWritable = ({ connected, pending, state }) => Boolean(connected && !pending && ["heat", "cool", "heat_cool"].includes(state));
  const clock = (date = new Date()) => ({ time: new Intl.DateTimeFormat(undefined, { hour:"numeric", minute:"2-digit" }).format(date), date: new Intl.DateTimeFormat(undefined, { weekday:"long", month:"short", day:"numeric" }).format(date), night: date.getHours() < 7 || date.getHours() >= 19 });
  const weatherIcon = (state) => /rain|pour|storm/.test(state) ? "☂" : /cloud/.test(state) ? "☁" : /snow/.test(state) ? "❄" : "☀";
  const eventData = (entity) => {
    const a = entity?.attributes || {}; const title = typeof a.message === "string" ? a.message.trim() : ""; const start = a.start_time || a.start || null;
    return title ? { title, start } : null;
  };
  const mount = (root, options = {}) => {
    if (!root?.replaceChildren || typeof options.getHass !== "function") throw new TypeError("FrameOSCustomHome requires a root and getHass");
    let destroyed = false, timer = null, draft = null, pending = null, feedback = "";
    const navigate = (view) => { if (!destroyed && typeof options.navigate === "function") options.navigate(view); };
    const syncPending = (model) => { if (!pending) return; if ((pending.kind === "mode" && model.mode === pending.value) || (pending.kind === "temperature" && ((model.dual && model.low === pending.low && model.high === pending.high) || (!model.dual && model.temperature === pending.temperature)))) { pending = null; feedback = ""; } };
    const render = () => {
      if (destroyed) return;
      const hass = options.getHass();
      if (!hass?.states) { options.onUnavailable?.(); root.innerHTML = '<section class="frameos-custom-home"><p class="fch-next-empty">Home Assistant is reconnecting…</p></section>'; return; }
      const now = clock(); const climate = climateModel(hass.states[CLIMATE], hass.config?.unit_system?.temperature);
      syncPending(climate); const writable = isWritable({ connected: hass.connected !== false, pending: Boolean(pending), state: climate.mode });
      if (!draft || draft.mode !== climate.mode) draft = climate.dual ? { mode:climate.mode, low:climate.low, high:climate.high } : { mode:climate.mode, temperature:climate.temperature };
      const weather = hass.states[WEATHER]; const weatherTemp = finite(weather?.attributes?.temperature); const family = eventData(hass.states[FAMILY]);
      const calendars = Object.entries(hass.states).filter(([id, item]) => id.startsWith("calendar.") && item?.attributes?.message).slice(0, 4).map(([, item]) => eventData(item)).filter(Boolean);
      const cameras = Object.values(hass.states).filter((item) => item?.entity_id?.startsWith("camera.") && !["unavailable", "unknown"].includes(item.state)).length;
      const modes = climate.modes.map((mode) => `<button class="fch-mode ${mode === climate.mode ? "active" : ""}" data-mode="${mode}" ${pending ? "disabled" : ""} aria-pressed="${mode === climate.mode}">${safe(mode.replace("_", " "))}</button>`).join("") || '<span>Climate controls unavailable</span>';
      const value = climate.dual ? `${draft.low ?? "—"}${safe(climate.unit)} – ${draft.high ?? "—"}${safe(climate.unit)}` : `${draft.temperature ?? "—"}${safe(climate.unit)}`;
      const adjust = climate.dual ? `<button class="fch-adjust" data-adjust="low:-1" ${writable ? "" : "disabled"} aria-label="Lower minimum temperature">− low</button><button class="fch-adjust" data-adjust="low:1" ${writable ? "" : "disabled"} aria-label="Raise minimum temperature">+ low</button><button class="fch-adjust" data-adjust="high:-1" ${writable ? "" : "disabled"} aria-label="Lower maximum temperature">− high</button><button class="fch-adjust" data-adjust="high:1" ${writable ? "" : "disabled"} aria-label="Raise maximum temperature">+ high</button>` : `<button class="fch-adjust" data-adjust="temperature:-1" ${writable ? "" : "disabled"} aria-label="Lower temperature">−</button><button class="fch-adjust" data-adjust="temperature:1" ${writable ? "" : "disabled"} aria-label="Raise temperature">+</button>`;
      const events = calendars.length ? calendars.map((event) => `<div class="fch-event"><time>${safe(event.start ? new Intl.DateTimeFormat(undefined,{hour:"numeric",minute:"2-digit"}).format(new Date(event.start)) : "Soon")}</time><span>${safe(event.title)}</span></div>`).join("") : '<div class="fch-event"><span>No upcoming calendar events</span></div>';
      root.innerHTML = `<main class="frameos-custom-home ${now.night ? "night" : ""}"><nav class="fch-rail" aria-label="FrameOS"><button data-nav="home">Today</button><button data-nav="calendar">Calendar</button><button data-nav="cameras">Cameras</button></nav><header class="fch-top"><div><div class="fch-time">${safe(now.time)}</div><div class="fch-date">${safe(now.date)}</div></div><div class="fch-weather"><span class="fch-weather-icon">${weatherIcon(String(weather?.state || ""))}</span><span class="fch-weather-temp">${weatherTemp === null ? "—" : weatherTemp}${safe(hass.config?.unit_system?.temperature || "°")}</span><span class="fch-weather-state">${safe(weather?.state || "Weather unavailable")}</span></div><div class="fch-status"><strong>${cameras} camera${cameras === 1 ? "" : "s"} available</strong><span>${safe(climate.mode === "unknown" ? "Climate unavailable" : `Entryway ${climate.mode}`)}</span></div></header><section class="fch-grid"><article class="fch-card"><h2>Up next</h2><div class="fch-next">${family ? `<div><div class="fch-eyebrow">Family calendar</div><div class="fch-next-title">${safe(family.title)}</div><div class="fch-next-meta">${safe(family.start ? new Intl.DateTimeFormat(undefined,{weekday:"short",hour:"numeric",minute:"2-digit"}).format(new Date(family.start)) : "Time unavailable")}</div></div><div class="fch-next-time">Next</div>` : '<div class="fch-next-empty">No upcoming family event</div>'}</div></article><article class="fch-card"><h2>Entryway climate</h2><div class="fch-climate"><div class="fch-climate-reading"><span class="fch-climate-temp">${value}</span><span class="fch-state">${safe(climate.mode)}</span></div><div class="fch-mode-row">${modes}</div><div class="fch-draft">${climate.mode === "off" ? "Choose Heat, Cool, or Auto to adjust" : `Draft: ${value}`}</div><div class="fch-adjust-row">${adjust}</div><div class="fch-actions"><button class="fch-action apply" data-apply ${writable ? "" : "disabled"}>Apply</button><button class="fch-action" data-cancel ${writable ? "" : "disabled"}>Cancel</button></div><p class="fch-feedback ${pending ? "pending" : feedback ? "error" : ""}" aria-live="polite">${safe(pending ? "Updating climate…" : feedback)}</p></div></article><section class="fch-bottom"><article class="fch-card"><h2>Calendar</h2><div class="fch-list">${events}</div></article><article class="fch-card"><h2>Home</h2><div class="fch-links"><button class="fch-link" data-nav="cameras">Cameras<small>${cameras} available now</small></button><button class="fch-link" data-nav="calendar">Calendar<small>View the family schedule</small></button></div></article></section></section></main>`;
      root.querySelectorAll("[data-nav]").forEach((button) => button.addEventListener("click", () => navigate(button.dataset.nav)));
      root.querySelectorAll("[data-mode]").forEach((button) => button.addEventListener("click", async () => { if (pending || destroyed) return; const mode = button.dataset.mode; pending = { kind:"mode", value:mode }; feedback = ""; render(); try { await hass.callService("climate", "set_hvac_mode", { entity_id:CLIMATE, ...JSON.parse(modePayload(mode)) }); } catch (_error) { pending = null; feedback = "Could not update climate mode."; render(); } }));
      root.querySelectorAll("[data-adjust]").forEach((button) => button.addEventListener("click", () => { if (!writable) return; const [field, deltaText] = button.dataset.adjust.split(":"); const next = clampTemperature((draft[field] ?? 0) + Number(deltaText) * climate.limits.step, climate.limits); if (next !== null) { draft = { ...draft, [field]:next }; render(); } }));
      root.querySelector("[data-cancel]")?.addEventListener("click", () => { draft = climate.dual ? { mode:climate.mode, low:climate.low, high:climate.high } : { mode:climate.mode, temperature:climate.temperature }; feedback = ""; render(); });
      root.querySelector("[data-apply]")?.addEventListener("click", async () => { if (!writable || destroyed) return; const payloadText = setpointPayload(draft); if (!payloadText) { feedback = "Choose a valid temperature range."; render(); return; } const payload = JSON.parse(payloadText); pending = climate.dual ? { kind:"temperature", low:payload.target_temp_low, high:payload.target_temp_high } : { kind:"temperature", temperature:payload.temperature }; feedback = ""; render(); try { await hass.callService("climate", "set_temperature", { entity_id:CLIMATE, ...payload }); } catch (_error) { pending = null; feedback = "Could not update temperature."; render(); } });
    };
    render(); timer = window.setInterval(render, 1000);
    return { refresh: render, destroy() { destroyed = true; if (timer !== null) window.clearInterval(timer); root.replaceChildren(); } };
  };
  window.FrameOSCustomHome = Object.freeze({ mount, __test:Object.freeze({ clampTemperature, climateModel, modePayload, setpointPayload, isWritable }) });
})();
