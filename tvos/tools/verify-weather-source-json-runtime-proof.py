#!/usr/bin/env python3
"""Prove JVM WeatherSource tests require the real org.json test runtime."""
from __future__ import annotations

import hashlib
import json
from pathlib import Path
import shutil
import subprocess
import sys
import xml.etree.ElementTree as ElementTree

if not __debug__:
    raise RuntimeError('optimized Python is not permitted')
if len(sys.argv) != 2:
    raise SystemExit(f'usage: {Path(sys.argv[0]).name} <tvos-root>')

root = Path(sys.argv[1]).resolve()
gradle = root / 'app/build.gradle.kts'
test = root / 'app/src/test/java/com/wyattfleming/frametv/WeatherSourceTest.java'
report = root / 'app/build/test-results/testDebugUnitTest'
validation = root / 'validation'
validation.mkdir(exist_ok=True)
xml_name = 'TEST-com.wyattfleming.frametv.WeatherSourceTest.xml'
test_class = 'com.wyattfleming.frametv.WeatherSourceTest'
command = ['../frameos/gradlew', '-p', '../frameos', '--no-daemon', '--max-workers=2', '--rerun-tasks', ':frametv:testDebugUnitTest', '--tests', test_class]
dependency = b'    testImplementation("org.json:json:20260814")\n'
expected_failures = {
    'rejectsForecastArraysThatExceedNativePresentationBudget',
    'rejectsMismatchedRequiredHourlyArrays',
}
expected_cases = expected_failures | {'constructsNetworkForecastBeforeReturningToMainHandler'}
mocked_json = 'Method getJSONObject in org.json.JSONObject not mocked.'

if not gradle.is_file() or gradle.is_symlink() or not test.is_file() or test.is_symlink() or not (root.parent / 'frameos/gradlew').is_file():
    raise RuntimeError('TV Gradle input or WeatherSourceTest must be a regular file')
original = gradle.read_bytes()
gradle_hash = hashlib.sha256(original).hexdigest()
test_hash = hashlib.sha256(test.read_bytes()).hexdigest()
if original.count(dependency) != 1:
    raise RuntimeError('expected real JSON test runtime dependency was not found exactly once')


def run(label: str) -> tuple[int, ElementTree.Element]:
    if report.exists():
        shutil.rmtree(report)
    log = validation / f'weather-source-json-runtime-{label}.log'
    with log.open('w') as stream:
        result = subprocess.run(command, cwd=root, stdout=stream, stderr=subprocess.STDOUT)
    matches = list(report.rglob(xml_name))
    if len(matches) != 1:
        raise RuntimeError(f'expected one WeatherSource XML after {label}: {matches}')
    receipt_xml = validation / f'weather-source-json-runtime-{label}.xml'
    shutil.copy2(matches[0], receipt_xml)
    return result.returncode, ElementTree.parse(receipt_xml).getroot()


def inspect(doc: ElementTree.Element, *, failures: int, require_mocked_json: bool) -> dict[str, object]:
    cases = list(doc.iter('testcase'))
    observed = {case.attrib.get('name') for case in cases}
    failure_nodes = list(doc.iter('failure'))
    error_nodes = list(doc.iter('error'))
    if int(doc.attrib.get('tests', '-1')) != 3 or int(doc.attrib.get('failures', '-1')) != failures or int(doc.attrib.get('errors', '-1')) != 0 or int(doc.attrib.get('skipped', '-1')) != 0:
        raise RuntimeError(f'unexpected WeatherSource results: {doc.attrib}')
    if observed != expected_cases:
        raise RuntimeError(f'unexpected WeatherSource cases: {observed}')
    failed_cases = {case.attrib.get('name') for case in cases if case.find('failure') is not None}
    if failures and failed_cases != expected_failures:
        raise RuntimeError(f'unexpected failed WeatherSource cases: {failed_cases}')
    if not failures and failed_cases:
        raise RuntimeError(f'WeatherSource green run still failed: {failed_cases}')
    failure_text = '\n'.join((node.text or '') for node in failure_nodes)
    if require_mocked_json and (len(failure_nodes) != 2 or mocked_json not in failure_text):
        raise RuntimeError('dependency-negative run did not record the mocked JSONObject cause')
    return {'tests': len(cases), 'failures': len(failure_nodes), 'errors': len(error_nodes), 'failed_cases': sorted(failed_cases)}


outcome: dict[str, object] = {'source': str(gradle.relative_to(root)), 'test': str(test.relative_to(root)), 'source_sha256': gradle_hash, 'unchanged_test_sha256': test_hash}
try:
    initial_exit, initial = run('initial-green')
    if initial_exit != 0:
        raise RuntimeError('initial WeatherSource tests were not green')
    outcome['initial_green'] = {'exit': initial_exit, **inspect(initial, failures=0, require_mocked_json=False)}

    gradle.write_bytes(original.replace(dependency, b''))
    negative_exit, negative = run('dependency-red')
    if negative_exit == 0:
        raise RuntimeError('removing the real JSON test runtime unexpectedly passed')
    outcome['dependency_red'] = {'exit': negative_exit, **inspect(negative, failures=2, require_mocked_json=True)}
finally:
    gradle.write_bytes(original)
    outcome['source_restored_exactly'] = gradle.read_bytes() == original
    outcome['test_unchanged'] = hashlib.sha256(test.read_bytes()).hexdigest() == test_hash
    green_exit, green = run('restored-green')
    outcome['restored_green'] = {'exit': green_exit, **inspect(green, failures=0, require_mocked_json=False)}
    outcome['passed'] = (
        outcome.get('initial_green', {}).get('exit') == 0
        and outcome.get('dependency_red', {}).get('exit', 0) != 0
        and outcome['restored_green']['exit'] == 0
        and outcome['source_restored_exactly']
        and outcome['test_unchanged']
    )

(validation / 'weather-source-json-runtime-proof.json').write_text(json.dumps(outcome, indent=2, sort_keys=True) + '\n')
if not outcome['passed']:
    raise SystemExit(1)
print('WeatherSource real JSON test runtime red / exact restore green verified')
