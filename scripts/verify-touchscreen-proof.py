#!/usr/bin/env python3
"""Run the touch guards with one compiling source-only negative control on NAS."""
import hashlib
import json
from pathlib import Path
import shutil
import subprocess
import sys
import xml.etree.ElementTree as ET

root = Path(sys.argv[1]).resolve()
frame = root / 'frameos'
out = root / 'reports/touchscreen-prep'
out.mkdir(parents=True, exist_ok=True)
source = frame / 'app/src/main/kotlin/com/wyattfleming/frameos/navigation/TouchModeNavigationRouter.kt'
tests = [frame / ('app/src/test/kotlin/com/wyattfleming/frameos/navigation/' + name)
         for name in ['TouchEdgeSwipeClassifierTest.kt', 'TouchModeNavigationRouterTest.kt']]
original = source.read_bytes()
sha = lambda p: hashlib.sha256(p.read_bytes()).hexdigest()
test_hashes = {str(p.relative_to(root)): sha(p) for p in tests}
command = ['./gradlew', '--no-daemon', '--max-workers=2', ':app:testDebugUnitTest',
           '--tests', 'com.wyattfleming.frameos.navigation.TouchEdgeSwipeClassifierTest',
           '--tests', 'com.wyattfleming.frameos.navigation.TouchModeNavigationRouterTest']
report_dir = frame / 'app/build/test-results/testDebugUnitTest'

def run(label, negative=False):
    if report_dir.exists():
        shutil.rmtree(report_dir)
    log = out / (label + '.log')
    with log.open('w') as stream:
        result = subprocess.run(command, cwd=frame, stdout=stream, stderr=subprocess.STDOUT)
    suites = [ET.parse(p).getroot() for p in report_dir.glob('TEST-*.xml')]
    failures = [case.attrib.get('name') for suite in suites for case in suite.findall('testcase')
                if case.find('failure') is not None]
    count = sum(int(suite.get('tests', '0')) for suite in suites)
    if negative:
        assert result.returncode != 0 and 'only touchscreen edge swipes are intercepted' in failures, (label, result.returncode, failures, log.read_text()[-6000:])
    else:
        assert result.returncode == 0 and count >= 9 and not failures, (label, result.returncode, failures, log.read_text()[-6000:])
    shutil.copytree(report_dir, out / (label + '-xml'))
    print(label + ': verified', flush=True)
    return {'exit': result.returncode, 'tests': count, 'failedCases': failures}

try:
    initial = run('touch-initial')
    needle = 'if (!sample.isTouchscreen)'
    text = original.decode()
    assert text.count(needle) == 1
    # Reverse only the new input-source gate; unchanged tests must reject the
    # resulting loss of touch navigation and accidental handling of other input.
    source.write_text(text.replace(needle, 'if (sample.isTouchscreen)'))
    try:
        red = run('touch-red', negative=True)
    finally:
        source.write_bytes(original)
    assert source.read_bytes() == original
    green = run('touch-green')
    assert test_hashes == {str(p.relative_to(root)): sha(p) for p in tests}
    (out / 'touch-source-proof.json').write_text(json.dumps({
        'passed': True, 'initial': initial, 'red': red, 'green': green,
        'restoredSourceSha256': sha(source), 'unchangedTests': test_hashes,
        'scope': 'JVM classifier and router; Android dispatch and physical hardware remain unverified',
    }, indent=2) + '\n')
finally:
    source.write_bytes(original)
