/* FrameOS Home forecast: a bounded read-only controller for a climate-safe renderer. */
(() => {
  "use strict";
  const SUCCESS_TTL = 30 * 60 * 1000;
  const ERROR_TTL = 60 * 1000;
  const STALE_TTL = 60 * 60 * 1000;
  const REQUEST_TIMEOUT = 10 * 1000;
  const CONDITIONS = new Set(["clear-night", "cloudy", "exceptional", "fog", "hail", "lightning", "lightning-rainy", "partlycloudy", "pouring", "rainy", "snowy", "snowy-rainy", "sunny", "windy", "windy-variant"]);
  const finite = (value, min, max) => {
    const number = typeof value === "number" ? value : (typeof value === "string" && value.trim() ? Number(value) : NaN);
    return Number.isFinite(number) && number >= min && number <= max ? number : null;
  };
  const date = (value) => {
    if (typeof value !== "string" || !/^\d{4}-\d\d-\d\d$/.test(value)) return null;
    const parsed = new Date(`${value}T00:00:00Z`);
    return Number.isNaN(parsed.valueOf()) || parsed.toISOString().slice(0, 10) !== value ? null : value;
  };
  const today = (millis) => new Date(millis).toISOString().slice(0, 10);
  const sanitize = (raw, unit, now) => {
    if (!Array.isArray(raw) || (unit !== "°C" && unit !== "°F")) return null;
    const seen = new Set(), days = [];
    for (const item of raw) {
      if (!item || typeof item !== "object" || days.length === 3) continue;
      const datetime = date(item.datetime);
      const condition = typeof item.condition === "string" && CONDITIONS.has(item.condition) ? item.condition : null;
      if (!datetime || datetime < today(now) || seen.has(datetime) || !condition) continue;
      seen.add(datetime);
      days.push(Object.freeze({
        datetime,
        condition,
        temperature: finite(item.temperature, -150, 150),
        templow: finite(item.templow, -150, 150),
        precipitation_probability: finite(item.precipitation_probability, 0, 100),
      }));
    }
    return days.length ? Object.freeze({ days: Object.freeze(days), temperatureUnit: unit, updatedAt: now }) : null;
  };
  const create = (options = {}) => {
    if (typeof options.readForecast !== "function") throw new TypeError("FrameOSHomeForecast requires readForecast");
    const clock = typeof options.now === "function" ? options.now : Date.now;
    const unit = typeof options.temperatureUnit === "function" ? options.temperatureUnit : () => "";
    const timers = options.timers || window;
    if (typeof timers.setTimeout !== "function" || typeof timers.clearTimeout !== "function") throw new TypeError("FrameOSHomeForecast requires timeout timers");
    let active = false, destroyed = false, forecast = null, status = "idle", lastSuccess = null, lastError = null, inFlight = null, requestEpoch = 0, timeout = null;
    const now = () => { const value = Number(clock()); return Number.isFinite(value) ? value : Date.now(); };
    const notify = () => { if (!destroyed && typeof options.onUpdate === "function") options.onUpdate(); };
    const clearTimeout = () => { if (timeout !== null) timers.clearTimeout(timeout); timeout = null; };
    const visibleForecast = () => forecast && now() - forecast.updatedAt <= STALE_TTL ? forecast : null;
    const visibleStatus = () => {
      if (destroyed || !visibleForecast() && status !== "loading") return "unavailable";
      if (status === "loading") return "loading";
      return visibleForecast() ? (status === "ready" ? "ready" : "stale") : "unavailable";
    };
    const snapshot = () => Object.freeze({ forecast: visibleForecast(), forecastStatus: visibleStatus() });
    const finish = (epoch, error, result) => {
      if (destroyed || !active || epoch !== requestEpoch) return;
      clearTimeout(); inFlight = null;
      const parsed = error ? null : sanitize(result, unit(), now());
      if (parsed) { forecast = parsed; lastSuccess = now(); lastError = null; status = "ready"; }
      else { lastError = now(); status = forecast && visibleForecast() ? "stale" : "unavailable"; }
      notify();
    };
    const refreshIfNeeded = () => {
      if (destroyed || !active || inFlight) return inFlight || Promise.resolve(false);
      const current = now();
      if (lastSuccess !== null && current - lastSuccess < SUCCESS_TTL) return Promise.resolve(false);
      if (lastError !== null && current - lastError < ERROR_TTL) return Promise.resolve(false);
      const epoch = ++requestEpoch;
      const request = Promise.resolve().then(options.readForecast);
      inFlight = request;
      status = "loading"; notify();
      timeout = timers.setTimeout(() => finish(epoch, true), REQUEST_TIMEOUT);
      request.then((result) => finish(epoch, false, result), () => finish(epoch, true));
      return request;
    };
    const setActive = (next) => {
      if (destroyed || active === Boolean(next)) return;
      active = Boolean(next);
      if (!active) { requestEpoch += 1; inFlight = null; clearTimeout(); status = forecast && visibleForecast() ? "stale" : "idle"; }
      notify();
    };
    const destroy = () => { if (destroyed) return; destroyed = true; active = false; requestEpoch += 1; inFlight = null; clearTimeout(); forecast = null; status = "unavailable"; };
    return Object.freeze({ setActive, snapshot, refreshIfNeeded, destroy });
  };
  window.FrameOSHomeForecast = Object.freeze({ create, __test: Object.freeze({ sanitize, finite }) });
})();
