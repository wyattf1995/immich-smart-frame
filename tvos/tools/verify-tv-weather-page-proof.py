#!/usr/bin/env python3
"""Prove the 0.5.0 Weather page and Ocean card tests catch each guarded behaviour: one source-only mutation at a time
must compile, fail its named TvWeatherDetailsTest cases by assertion, and restore byte for byte; then all pass.

  verify-tv-weather-page-proof.py <tvos-root>
"""
from __future__ import annotations

import hashlib
import json
import shutil
import subprocess
import sys
import xml.etree.ElementTree as ElementTree
from pathlib import Path

if not __debug__:
    raise RuntimeError('optimized Python is not permitted')
if len(sys.argv) != 2:
    raise SystemExit(__doc__)
root = Path(sys.argv[1]).resolve()
validation = root / 'validation'
validation.mkdir(exist_ok=True)
report = root / 'app/build/test-results/testDebugUnitTest'
test_class = 'com.wyattfleming.frametv.TvWeatherDetailsTest'
xml_name = f'TEST-{test_class}.xml'
command = ['../frameos/gradlew', '-p', '../frameos', '--no-daemon', '--max-workers=2', ':frametv:testDebugUnitTest', '--tests', test_class]
details = 'app/src/main/kotlin/com/wyattfleming/frametv/TvWeatherDetails.kt'
cases = [
    ('pressure-in-inches', details, '?.let { if (hectopascals(pressureUnit)) it * INHG_PER_HPA else it }', '?.let { it }',
     {'openMeteoForecastFillsThePageFacts', 'sharedWeatherPageShowsTheTvForecast'}),
    ('wind-unit-mph', details, '?.let { if (it == "mp/h") "mph" else it }', '?.let { unit -> unit }',
     {'openMeteoForecastFillsThePageFacts', 'sharedWeatherPageShowsTheTvForecast'}),
    ('home-assistant-words-win', details,
     '            word?.let { WeatherCondition.fromHomeAssistant(it) }?.takeIf { it != WeatherCondition.UNKNOWN }?.let { return it }\n',
     '            // proof mutation: Home Assistant condition words ignored\n', {'homeAssistantWordsWinOverCodesAndKeepItsUnits'}),
    ('clear-night-moon', details, '0 -> if (isDay == false) WeatherCondition.CLEAR_NIGHT else WeatherCondition.SUNNY', '0 -> WeatherCondition.SUNNY',
     {'weatherCodesNameTheSkyAndNightClearsToTheMoon', 'openMeteoForecastFillsThePageFacts'}),
    ('next-sun-event-ahead', details, 'return all.firstOrNull { it > nowMillis } ?: all.lastOrNull()', 'return all.firstOrNull()',
     {'openMeteoForecastFillsThePageFacts'}),
    ('optional-series-aligned', details, 'root.optJSONArray(name)?.takeIf { it.length() == timeline.length() }', 'root.optJSONArray(name)?.takeIf { timeline.length() >= 0 }',
     {'misalignedOptionalSeriesIsIgnoredNotFatal'}),
    ('no-temperature-no-page', details, '?: hourly.firstOrNull()?.temperature ?: return null', '?: hourly.firstOrNull()?.temperature ?: 0.0',
     {'noTemperatureAnywhereMeansNoPage'}),
    ('forecast-attaches-page', 'app/src/main/java/com/wyattfleming/frametv/WeatherSource.java',
     'TvWeatherDetails.from(root, System.currentTimeMillis()));', 'null /* proof mutation: no page model */);',
     {'forecastSourceAttachesThePageToTheHomePanelForecast'}),
    ('ocean-skips-setup', 'app/src/main/java/com/wyattfleming/frametv/Feature.java', 'OCEAN("Ocean", "Main Beach", false),', 'OCEAN("Ocean", "Main Beach"),',
     {'onlyTheOceanCardSkipsDashboardSetup'}),
]


def run(label: str) -> tuple[int, set[str], int]:
    if report.exists():
        shutil.rmtree(report)
    log = validation / f'tv-weather-page-{label}.log'
    with log.open('w') as stream:
        result = subprocess.run(command, cwd=root, stdout=stream, stderr=subprocess.STDOUT)
    matches = list(report.rglob(xml_name))
    if len(matches) != 1:
        raise RuntimeError(f'{label}: no {xml_name}; a proof mutation must compile and fail by assertion (see {log.name})')
    shutil.copy2(matches[0], validation / f'tv-weather-page-{label}.xml')
    doc = ElementTree.parse(matches[0]).getroot()
    failed = {case.attrib['name'] for case in doc.iter('testcase') if case.find('failure') is not None or case.find('error') is not None}
    return result.returncode, failed, int(doc.attrib.get('tests', '0'))


originals = {path: (root / path).read_bytes() for path in {case[1] for case in cases}}
outcome: dict[str, object] = {'sources': {path: hashlib.sha256(data).hexdigest() for path, data in originals.items()}, 'proofs': []}
try:
    for name, path, needle, replacement, expected in cases:
        text = originals[path].decode('utf-8')
        if text.count(needle) != 1 or not replacement or replacement in text:
            raise RuntimeError(f'{name}: the needle must occur once and the replacement must be new')
        (root / path).write_text(text.replace(needle, replacement), encoding='utf-8')
        exit_code, failed, tests = run(name + '-red')
        (root / path).write_bytes(originals[path])
        restored = (root / path).read_bytes() == originals[path]
        if exit_code == 0 or not expected <= failed or not restored:
            raise RuntimeError(f'{name}: exit {exit_code}, failed {sorted(failed)}, expected {sorted(expected)}, restored {restored}')
        outcome['proofs'].append({'name': name, 'file': path, 'redExit': exit_code, 'failedCases': sorted(failed), 'tests': tests, 'restoredExactly': restored})
finally:
    for path, data in originals.items():
        (root / path).write_bytes(data)
    outcome['source_restored_exactly'] = all((root / path).read_bytes() == data for path, data in originals.items())
green_exit, green_failed, green_tests = run('restored-green')
outcome['restored_green'] = {'exit': green_exit, 'failed': sorted(green_failed), 'tests': green_tests}
outcome['passed'] = (len(outcome['proofs']) == len(cases) and green_exit == 0 and not green_failed and outcome['source_restored_exactly'])
(validation / 'tv-weather-page-proof.json').write_text(json.dumps(outcome, indent=2, sort_keys=True) + '\n')
if not outcome['passed']:
    raise SystemExit(1)
print(f'Weather page and Ocean card: {len(cases)} source-only mutations red by assertion, exact restore green')
