(() => {
  // Only forecast values leave this page. Its authenticated connection remains inside HA.
  const key = '__frameTvWeatherSnapshotV1';
  if (window[key]) return JSON.stringify(window[key]);
  const hass = document.querySelector('home-assistant')?.hass;
  if (!hass?.states || !hass.callApi) return JSON.stringify({status: 'waiting'});
  const entities = Object.keys(hass.states).filter(id => id.startsWith('weather.') && !['unknown', 'unavailable'].includes(hass.states[id].state));
  const entity = entities.includes('weather.home') ? 'weather.home' : entities.includes('weather.forecast_home') ? 'weather.forecast_home' : entities.length === 1 ? entities[0] : null;
  if (!entity) return JSON.stringify({status: 'unavailable'});
  const state = hass.states[entity];
  const unit = state.attributes.temperature_unit;
  if (!['°F', '°C'].includes(unit)) return JSON.stringify({status: 'unavailable'});
  window[key] = {status: 'loading'};
  const number = x => typeof x === 'number' && Number.isFinite(x) ? x : null;
  const temp = x => number(x) === null ? null : unit === '°C' ? x * 9 / 5 + 32 : x;
  const code = x => ({sunny: 0, 'clear-night': 0, partlycloudy: 2, cloudy: 3, fog: 45, rainy: 61, pouring: 65, 'snowy-rainy': 67, snowy: 73, lightning: 95, 'lightning-rainy': 95, hail: 96})[x] ?? null;
  // Home Assistant's own condition words keep distinctions the codes above merge (windy, partly cloudy at night).
  const condition = x => typeof x === 'string' && /^[a-z-]{1,24}$/.test(x) ? x : null;
  const label = x => typeof x === 'string' && x.length > 0 && x.length <= 8 ? x : null;
  const epoch = x => Number.isFinite(Date.parse(x)) ? Math.floor(Date.parse(x) / 1000) : null;
  const request = type => hass.callApi('POST', 'services/weather/get_forecasts?return_response', {entity_id: entity, type})
      .then(result => result?.service_response?.[entity]?.forecast || []);
  Promise.all([request('hourly'), request('daily')]).then(([hours, days]) => {
    hours = hours.slice(0, 120).filter(x => epoch(x.datetime) !== null);
    days = days.slice(0, 7).filter(x => epoch(x.datetime) !== null);
    if (!hours.length || !days.length) throw new Error('Forecast unavailable');
    const attributes = state.attributes;
    const sun = hass.states['sun.sun']?.attributes || {};
    const forecast = {
      timezone: hass.config?.time_zone || 'UTC',
      current: {
        temperature_2m: temp(attributes.temperature), weather_code: code(state.state), condition: condition(state.state),
        apparent_temperature: temp(attributes.apparent_temperature), relative_humidity_2m: number(attributes.humidity),
        wind_speed_10m: number(attributes.wind_speed), wind_direction_10m: number(attributes.wind_bearing),
        pressure_msl: number(attributes.pressure), uv_index: number(attributes.uv_index)
      },
      current_units: {
        wind_speed_10m: label(attributes.wind_speed_unit), pressure_msl: label(attributes.pressure_unit),
        precipitation: label(attributes.precipitation_unit)
      },
      hourly: {
        time: hours.map(x => epoch(x.datetime)), temperature_2m: hours.map(x => temp(x.temperature)),
        weather_code: hours.map(x => code(x.condition)), precipitation_probability: hours.map(x => number(x.precipitation_probability)),
        is_day: hours.map(x => x.condition === 'clear-night' ? 0 : x.condition === 'sunny' ? 1 : null),
        condition: hours.map(x => condition(x.condition)), precipitation: hours.map(x => number(x.precipitation))
      },
      daily: {
        time: days.map(x => epoch(x.datetime)), temperature_2m_max: days.map(x => temp(x.temperature)),
        temperature_2m_min: days.map(x => temp(x.templow)), weather_code: days.map(x => code(x.condition)),
        condition: days.map(x => condition(x.condition)), precipitation_sum: days.map(x => number(x.precipitation)),
        precipitation_probability_max: days.map(x => number(x.precipitation_probability))
      },
      sun: {next_rising: epoch(sun.next_rising), next_setting: epoch(sun.next_setting)}
    };
    window[key] = {status: 'ready', forecast};
  }).catch(() => { window[key] = {status: 'unavailable'}; });
  return JSON.stringify(window[key]);
})()
