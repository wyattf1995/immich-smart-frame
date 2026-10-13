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
  assert.deepEqual(Object.keys(result.forecast).sort(), ['current', 'daily', 'hourly', 'timezone']);
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
