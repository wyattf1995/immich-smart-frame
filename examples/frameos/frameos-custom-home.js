/* FrameOS custom Home: receives Home Assistant's in-memory hass object from its host. */
(() => {
  "use strict";
  const CLIMATE = "climate.entryway_entryway";
  const WEATHER = "weather.home";
  const FAMILY = "calendar.family";
  const CAMERAS = Object.freeze(["camera.backyard_backyard_camera", "camera.dining_room_dining_room_camera", "camera.garage_garage_camera", "camera.living_room_living_room_camera"]);
  const safe = (value) => String(value ?? "").replace(/[&<>"]/g, (char) => ({ "&":"&amp;", "<":"&lt;", ">":"&gt;", '"':"&quot;" })[char]);
  const finite = (value) => {
    if (typeof value === "number") return Number.isFinite(value) ? value : null;
    if (typeof value !== "string" || value.trim() === "") return null;
    const number = Number(value); return Number.isFinite(number) ? number : null;
  };
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
    return { mode, modes, dual, unit: unit || "°", limits, currentTemperature: finite(attributes.current_temperature), temperature: clampTemperature(attributes.temperature, limits), low: clampTemperature(attributes.target_temp_low, limits), high: clampTemperature(attributes.target_temp_high, limits), temperatureEnabled: mode !== "off" && ["heat", "cool", "heat_cool"].includes(mode) };
  };
  const modePayload = (mode) => JSON.stringify({ hvac_mode: mode });
  const setpointPayload = ({ mode, temperature, low, high }) => {
    if (mode === "heat_cool") return finite(low) !== null && finite(high) !== null && Number(low) <= Number(high) ? JSON.stringify({ target_temp_low:Number(low), target_temp_high:Number(high) }) : null;
    return finite(temperature) === null ? null : JSON.stringify({ temperature:Number(temperature) });
  };
  const isWritable = ({ connected, pending, state }) => Boolean(connected && !pending && ["heat", "cool", "heat_cool"].includes(state));
  const connected = (hass) => Boolean(hass?.states && hass.connected !== false && hass.connection?.connected !== false);
  const validSetpoint = (value, limits) => {
    const number = finite(value), min = finite(limits.min), max = finite(limits.max);
    return number !== null && min !== null && max !== null && number >= min && number <= max;
  };
  const clock = (date = new Date()) => ({ time: new Intl.DateTimeFormat(undefined, { hour:"numeric", minute:"2-digit" }).formatToParts(date).filter(p=>p.type !== "dayPeriod").map(p=>p.value).join("").trim(), period: new Intl.DateTimeFormat(undefined, {hour:"numeric"}).formatToParts(date).find(p=>p.type === "dayPeriod")?.value || "", date: new Intl.DateTimeFormat(undefined, { weekday:"long", month:"short", day:"numeric" }).format(date), night: date.getHours() < 7 || date.getHours() >= 19 });
  const weatherIcon = (state) => /rain|pour|storm/.test(state) ? "☂" : /cloud/.test(state) ? "☁" : /snow/.test(state) ? "❄" : "☀";
  const eventData = (entity) => {
    const a = entity?.attributes || {}; const title = typeof a.message === "string" ? a.message.trim() : ""; const start = a.start_time || a.start || null;
    return title ? { title, start } : null;
  };
  const eventTime = (start, options = {}) => {
    if (!start) return "Time unavailable";
    if (/^\d{4}-\d\d-\d\d$/.test(String(start))) return "All day";
    const date = new Date(start); if (Number.isNaN(date.valueOf())) return "Time unavailable";
    return new Intl.DateTimeFormat(undefined, options).format(date);
  };
  const mount = (root, options = {}) => {
    if (!root?.replaceChildren || typeof options.getHass !== "function") throw new TypeError("FrameOSCustomHome requires a root and getHass");
    let destroyed = false, timer = null, pendingDeadline = null, draft = null, dirty = false, pending = null, feedback = "", lastSignature = null, draftBaseline = null;
    const navigate = (view) => { if (!destroyed && typeof options.navigate === "function") options.navigate(view); };
    const clearPending = () => { if (pendingDeadline !== null) window.clearTimeout(pendingDeadline); pendingDeadline = null; pending = null; };
    const setPending = (next) => {
      clearPending(); pending = next;
      pendingDeadline = window.setTimeout(() => { if (destroyed || !pending) return; clearPending(); feedback = "Climate update did not confirm. Try again after it reconnects."; render(true); }, 20_000);
    };
    const targetKey = (model) => JSON.stringify([model.mode, model.temperature, model.low, model.high]);
    const syncPending = (model) => { if (!pending) return; if ((pending.kind === "mode" && model.mode === pending.value) || (pending.kind === "temperature" && ((model.dual && model.low === pending.low && model.high === pending.high) || (!model.dual && model.temperature === pending.temperature)))) { clearPending(); feedback = ""; dirty = false; } };
    const render = (force = false) => {
      if (destroyed) return;
      const hass = options.getHass();
      if (!hass?.states) { options.onUnavailable?.(); if (lastSignature !== "unavailable") root.innerHTML = '<section class="frameos-custom-home"><p class="fch-next-empty">Home Assistant is reconnecting…</p></section>'; lastSignature = "unavailable"; return; }
      const now = clock(); const climate = climateModel(hass.states[CLIMATE], hass.config?.unit_system?.temperature);
      syncPending(climate); const writable = isWritable({ connected: connected(hass), pending: Boolean(pending), state: climate.mode });
      if (dirty && draftBaseline !== targetKey(climate)) { dirty = false; feedback = "Climate changed elsewhere; review the current setting."; }
      if (!draft || !dirty || draft.mode !== climate.mode) { draft = climate.dual ? { mode:climate.mode, low:climate.low, high:climate.high } : { mode:climate.mode, temperature:climate.temperature }; dirty = false; draftBaseline = targetKey(climate); }
      const weather = hass.states[WEATHER]; const weatherTemp = finite(weather?.attributes?.temperature); const family = eventData(hass.states[FAMILY]);
      const calendars = Object.entries(hass.states).filter(([id, item]) => id.startsWith("calendar.") && item?.attributes?.message).slice(0, 4).map(([, item]) => eventData(item)).filter(Boolean);
      const cameras = CAMERAS.filter((id) => hass.states[id] && !["unavailable", "unknown"].includes(hass.states[id].state)).length;
      const modes = climate.modes.map((mode) => `<button class="fch-mode ${mode === climate.mode ? "active" : ""}" data-mode="${mode}" ${pending || !connected(hass) || ["unknown", "unavailable"].includes(climate.mode) || mode === climate.mode ? "disabled" : ""} aria-pressed="${mode === climate.mode}">${safe(mode === "heat_cool" ? "Auto" : mode)}</button>`).join("") || '<span>Climate controls unavailable</span>';
      const draftValue = climate.dual ? `${draft.low ?? "—"}${safe(climate.unit)} – ${draft.high ?? "—"}${safe(climate.unit)}` : `${draft.temperature ?? "—"}${safe(climate.unit)}`;
      const currentValue = climate.currentTemperature === null ? "—" : `${climate.currentTemperature}${safe(climate.unit)}`;
      const adjust = climate.dual ? `<button class="fch-adjust" data-adjust="low:-1" ${writable ? "" : "disabled"} aria-label="Lower minimum temperature">− low</button><button class="fch-adjust" data-adjust="low:1" ${writable ? "" : "disabled"} aria-label="Raise minimum temperature">+ low</button><button class="fch-adjust" data-adjust="high:-1" ${writable ? "" : "disabled"} aria-label="Lower maximum temperature">− high</button><button class="fch-adjust" data-adjust="high:1" ${writable ? "" : "disabled"} aria-label="Raise maximum temperature">+ high</button>` : `<button class="fch-adjust" data-adjust="temperature:-1" ${writable ? "" : "disabled"} aria-label="Lower temperature">−</button><button class="fch-adjust" data-adjust="temperature:1" ${writable ? "" : "disabled"} aria-label="Raise temperature">+</button>`;
      const events = calendars.length ? calendars.map((event) => `<div class="fch-event"><time>${safe(eventTime(event.start,{hour:"numeric",minute:"2-digit"}))}</time><span>${safe(event.title)}</span></div>`).join("") : '<div class="fch-event"><span>No upcoming calendar events</span></div>';
      const signature = JSON.stringify({ now:now.time, period:now.period, date:now.date, connected:connected(hass), climate, draft, dirty, pending, feedback, weather:weather?.state, weatherTemp, family, calendars, cameras });
      if (!force && signature === lastSignature) return;
      lastSignature = signature;
      const active = root.ownerDocument?.activeElement;
      const focused = active && root.contains?.(active) ? Object.entries(active.dataset || {}).find(([key]) => ["mode", "adjust", "apply", "cancel", "nav"].includes(key)) : null;
      root.innerHTML = `<main class="frameos-custom-home ${now.night ? "night" : ""}"><nav class="fch-rail" aria-label="FrameOS"><button data-nav="home">Home</button><button data-nav="calendar">Calendar</button><button data-nav="cameras">Cameras</button></nav><header class="fch-top"><div><div class="fch-time">${safe(now.time)}<small>${safe(now.period)}</small></div><div class="fch-date">${safe(now.date)}</div></div><div class="fch-weather"><span class="fch-weather-icon">${weatherIcon(String(weather?.state || ""))}</span><span class="fch-weather-temp">${weatherTemp === null ? "—" : weatherTemp}${safe(hass.config?.unit_system?.temperature || "°")}</span><span class="fch-weather-state">${safe(weather?.state || "Weather unavailable")}</span></div><div class="fch-status"><strong>${cameras} camera${cameras === 1 ? "" : "s"} available</strong><span>${safe(climate.mode === "unknown" ? "Climate unavailable" : `Entryway ${climate.mode}`)}</span></div></header><section class="fch-grid"><article class="fch-card fch-next-card"><h2>Up next</h2><div class="fch-next">${family ? `<div><div class="fch-eyebrow">Family calendar</div><div class="fch-next-title">${safe(family.title)}</div><div class="fch-next-meta">${safe(eventTime(family.start,{weekday:"short",hour:"numeric",minute:"2-digit"}))}</div></div>` : '<div class="fch-next-empty">No upcoming family event</div>'}</div></article><article class="fch-card fch-climate-card"><h2>Entryway climate</h2><div class="fch-climate"><div class="fch-climate-reading"><span class="fch-climate-temp">${currentValue}</span><span class="fch-state">${safe(climate.mode)}</span></div><div class="fch-mode-row">${modes}</div><div class="fch-draft">${climate.mode === "off" ? "Choose Heat, Cool, or Auto to adjust" : `Set to: ${draftValue}`}</div><div class="fch-adjust-row">${adjust}</div><div class="fch-actions"><button class="fch-action apply" data-apply ${writable && dirty ? "" : "disabled"}>Apply</button><button class="fch-action" data-cancel ${writable && dirty ? "" : "disabled"}>Cancel</button></div><p class="fch-feedback ${pending ? "pending" : feedback ? "error" : ""}" aria-live="polite">${safe(pending ? "Updating climate…" : feedback)}</p></div></article><section class="fch-bottom"><article class="fch-card fch-calendar-card"><h2>Calendar</h2><div class="fch-list">${events}</div></article><article class="fch-card fch-links-card"><h2>Explore</h2><div class="fch-links"><button class="fch-link" data-nav="cameras">Cameras<small>${cameras} available now</small></button><button class="fch-link" data-nav="calendar">Calendar<small>View the family schedule</small></button></div></article></section></section></main>`;
      if (focused) root.querySelector(`[data-${focused[0]}="${focused[1]}"]`)?.focus?.({preventScroll:true});
      root.querySelectorAll("[data-nav]").forEach((button) => button.addEventListener("click", () => navigate(button.dataset.nav)));
      root.querySelectorAll("[data-mode]").forEach((button) => button.addEventListener("click", async () => {
        const live = options.getHass(), liveClimate = climateModel(live?.states?.[CLIMATE], live?.config?.unit_system?.temperature), mode = button.dataset.mode;
        if (destroyed || pending || !connected(live) || !liveClimate.modes.includes(mode) || ["unknown", "unavailable"].includes(liveClimate.mode)) { feedback = "Climate is unavailable."; render(true); return; }
        if (mode === liveClimate.mode) return;
        setPending({ kind:"mode", value:mode }); feedback = ""; render(true);
        try { await live.callService("climate", "set_hvac_mode", { entity_id:CLIMATE, ...JSON.parse(modePayload(mode)) }); }
        catch (_error) { if (!destroyed) { clearPending(); feedback = "Could not update climate mode."; render(true); } }
      }));
      root.querySelectorAll("[data-adjust]").forEach((button) => button.addEventListener("click", () => {
        const live = options.getHass(), liveClimate = climateModel(live?.states?.[CLIMATE], live?.config?.unit_system?.temperature);
        if (destroyed || !connected(live) || pending || !liveClimate.temperatureEnabled || draft?.mode !== liveClimate.mode) { feedback = "Climate changed; review the current setting."; dirty = false; render(true); return; }
        const [field, deltaText] = button.dataset.adjust.split(":"), current = finite(draft[field]);
        if (current === null) { feedback = "Temperature is unavailable."; render(true); return; }
        if (draftBaseline !== targetKey(liveClimate)) { dirty = false; feedback = "Climate changed elsewhere; review the current setting."; render(true); return; }
        const next = clampTemperature(current + Number(deltaText) * liveClimate.limits.step, liveClimate.limits);
        if (next !== null) { draft = { ...draft, [field]:next }; dirty = true; render(true); }
      }));
      root.querySelector("[data-cancel]")?.addEventListener("click", () => { const live = options.getHass(), liveClimate = climateModel(live?.states?.[CLIMATE], live?.config?.unit_system?.temperature); draft = liveClimate.dual ? { mode:liveClimate.mode, low:liveClimate.low, high:liveClimate.high } : { mode:liveClimate.mode, temperature:liveClimate.temperature }; dirty = false; feedback = ""; render(true); });
      root.querySelector("[data-apply]")?.addEventListener("click", async () => {
        const live = options.getHass(), liveClimate = climateModel(live?.states?.[CLIMATE], live?.config?.unit_system?.temperature);
        if (destroyed || pending || !connected(live) || !liveClimate.temperatureEnabled || draft?.mode !== liveClimate.mode) { feedback = "Climate changed; review the current setting."; dirty = false; render(true); return; }
        if (!dirty) return;
        if (draftBaseline !== targetKey(liveClimate)) { dirty = false; feedback = "Climate changed elsewhere; review the current setting."; render(true); return; }
        const payloadText = setpointPayload(draft), payload = payloadText && JSON.parse(payloadText);
        const valid = payload && (liveClimate.dual ? validSetpoint(payload.target_temp_low, liveClimate.limits) && validSetpoint(payload.target_temp_high, liveClimate.limits) && payload.target_temp_low <= payload.target_temp_high : validSetpoint(payload.temperature, liveClimate.limits));
        if (!valid) { feedback = "Choose a valid temperature range."; render(true); return; }
        setPending(liveClimate.dual ? { kind:"temperature", low:payload.target_temp_low, high:payload.target_temp_high } : { kind:"temperature", temperature:payload.temperature }); feedback = ""; render(true);
        try { await live.callService("climate", "set_temperature", { entity_id:CLIMATE, ...payload }); }
        catch (_error) { if (!destroyed) { clearPending(); feedback = "Could not update temperature."; render(true); } }
      });
    };
    render(); timer = window.setInterval(render, 1000);
    return { refresh: render, destroy() { destroyed = true; if (timer !== null) window.clearInterval(timer); clearPending(); root.replaceChildren(); } };
  };
  window.FrameOSCustomHome = Object.freeze({ mount, __test:Object.freeze({ clampTemperature, climateModel, modePayload, setpointPayload, isWritable, finite, eventTime, connected }) });
})();
