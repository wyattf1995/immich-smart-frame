const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const script = fs.readFileSync(path.join(__dirname, '../app/src/main/assets/ha-weather-snapshot.js'), 'utf8');

async function capture({unit = '°C', entities, hourly, daily} = {}) {
  const calls = [];
  const hass = {
    states: entities || {'weather.home': {state: 'clear-night', attributes: {temperature: 20, temperature_unit: unit}}},
    config: {time_zone: 'America/Los_Angeles'},
    callApi: async (method, route, body) => {
      calls.push({method, route, body});
      return {service_response: {[body.entity_id]: {forecast: body.type === 'hourly'
        ? hourly || [{datetime: '2026-09-14T03:00:00Z', temperature: 18, condition: 'clear-night', precipitation_probability: 5}]
        : daily || [{datetime: '2026-09-14T07:00:00Z', temperature: 25, templow: 16, condition: 'sunny'}]}}};
    }
  };
  Object.defineProperty(hass, 'auth', {get() {throw Error('Authentication must remain untouched');}});
  const context = vm.createContext({window: {}, document: {querySelector: name => name === 'home-assistant' ? {hass} : null}});
  vm.runInContext(script, context);
  await new Promise(resolve => setImmediate(resolve));
  return {result: JSON.parse(vm.runInContext(script, context)), calls};
}

test('reads only the two forecast services and converts Celsius with night semantics', async () => {
  const {result, calls} = await capture();
  assert.equal(result.status, 'ready');
  assert.equal(result.forecast.current.temperature_2m, 68);
  assert.equal(result.forecast.hourly.temperature_2m[0], 64.4);
  assert.equal(result.forecast.daily.temperature_2m_max[0], 77);
  assert.equal(result.forecast.hourly.is_day[0], 0);
  assert.equal(calls.length, 2);
  assert.deepEqual(calls.map(x => x.body.type), ['hourly', 'daily']);
  assert.ok(calls.every(x => x.method === 'POST' && x.route === 'services/weather/get_forecasts?return_response' && x.body.entity_id === 'weather.home'));
  assert.deepEqual(Object.keys(result.forecast).sort(), ['current', 'current_units', 'daily', 'hourly', 'sun', 'timezone']);
});

test('keeps Fahrenheit and treats missing or nonnumeric values as unknown', async () => {
  const {result} = await capture({unit: '°F', hourly: [{datetime: '2026-09-14T03:00:00Z', temperature: 'bad', condition: 'unavailable'}]});
  assert.equal(result.forecast.current.temperature_2m, 20);
  assert.equal(result.forecast.hourly.temperature_2m[0], null);
  assert.equal(result.forecast.hourly.weather_code[0], null);
  assert.equal(result.forecast.hourly.precipitation_probability[0], null);
});

test('does not choose an arbitrary weather entity or unknown units', async () => {
  const entity = {state: 'sunny', attributes: {temperature: 70, temperature_unit: '°F'}};
  const ambiguous = await capture({entities: {'weather.east': entity, 'weather.west': entity}});
  assert.equal(ambiguous.result.status, 'unavailable');
  assert.equal(ambiguous.calls.length, 0);
  const unknown = await capture({unit: 'kelvin'});
  assert.equal(unknown.result.status, 'unavailable');
  assert.equal(unknown.calls.length, 0);
});

test('carries the Weather page facts, rain amounts, conditions and sun times without reading authentication', async () => {
  const {result} = await capture({
    unit: '°F',
    entities: {
      'weather.forecast_home': {state: 'partlycloudy', attributes: {temperature: 71, temperature_unit: '°F', humidity: 62,
        wind_speed: 8.7, wind_speed_unit: 'mph', wind_bearing: 231, pressure: 29.96, pressure_unit: 'inHg',
        precipitation_unit: 'in', uv_index: 5.5}},
      'sun.sun': {state: 'above_horizon', attributes: {next_rising: '2026-10-25T14:03:08+00:00', next_setting: '2026-10-25T01:12:40+00:00'}}
    },
    hourly: [{datetime: '2026-10-24T22:00:00Z', temperature: 70, condition: 'windy', precipitation: 0.02, precipitation_probability: null}],
    daily: [{datetime: '2026-10-24T07:00:00Z', temperature: 74, templow: 58, condition: 'rainy', precipitation: 0.31}]
  });
  const forecast = result.forecast;
  assert.equal(forecast.current.condition, 'partlycloudy');
  assert.equal(forecast.current.relative_humidity_2m, 62);
  assert.equal(forecast.current.wind_speed_10m, 8.7);
  assert.equal(forecast.current.wind_direction_10m, 231);
  assert.equal(forecast.current.pressure_msl, 29.96);
  assert.equal(forecast.current.uv_index, 5.5);
  assert.equal(forecast.current.apparent_temperature, null);
  assert.deepEqual(forecast.current_units, {wind_speed_10m: 'mph', pressure_msl: 'inHg', precipitation: 'in'});
  assert.deepEqual(forecast.hourly.condition, ['windy']);
  assert.deepEqual(forecast.hourly.precipitation, [0.02]);
  assert.deepEqual(forecast.daily.condition, ['rainy']);
  assert.deepEqual(forecast.daily.precipitation_sum, [0.31]);
  assert.deepEqual(forecast.daily.precipitation_probability_max, [null]);
  assert.equal(forecast.sun.next_rising, 1792936988);
  assert.equal(forecast.sun.next_setting, 1792890760);
});

test('drops condition words and unit labels that are not plain short values', async () => {
  const {result} = await capture({
    unit: '°F',
    entities: {'weather.home': {state: 'sunny', attributes: {temperature: 70, temperature_unit: '°F', wind_speed_unit: 'miles-per-hour'}}},
    hourly: [{datetime: '2026-10-24T22:00:00Z', temperature: 70, condition: '<b>sunny</b>'}]
  });
  assert.equal(result.forecast.hourly.condition[0], null);
  assert.equal(result.forecast.current_units.wind_speed_10m, null);
  assert.deepEqual(result.forecast.sun, {next_rising: null, next_setting: null});
});
