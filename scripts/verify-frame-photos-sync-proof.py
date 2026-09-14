#!/usr/bin/env python3
"""Prove the rollout configuration guards with unchanged JVM tests on the NAS."""
import hashlib
import json
import subprocess
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

root = Path(sys.argv[1]).resolve()
frame = root / 'frameos'
output = root / 'reports/frame-sync-android'
output.mkdir(parents=True, exist_ok=True)
helper = frame / 'app/src/main/kotlin/com/wyattfleming/frameos/config/FramePhotosSyncGroupUrl.kt'
codec = frame / 'app/src/main/kotlin/com/wyattfleming/frameos/control/FrameControlCommand.kt'
tests = [frame / ('app/src/test/kotlin/com/wyattfleming/frameos/' + name) for name in
         ('config/FrameConfigurationPatchTest.kt', 'control/FrameControlCommandCodecTest.kt')]
digest = lambda p: hashlib.sha256(p.read_bytes()).hexdigest()
test_hashes = {str(p.relative_to(root)): digest(p) for p in tests}
originals = {p: p.read_bytes() for p in (helper, codec)}
command = ['./gradlew', '--no-daemon', '--max-workers=2', ':app:testDebugUnitTest',
           '--tests', 'com.wyattfleming.frameos.config.FrameConfigurationPatchTest',
           '--tests', 'com.wyattfleming.frameos.control.FrameControlCommandCodecTest']

def run(label, expected_failure=None):
    with (output / (label + '.log')).open('w') as log:
        result = subprocess.run(command, cwd=frame, stdout=log, stderr=subprocess.STDOUT)
    suites = [ET.parse(p).getroot() for p in (frame/'app/build/test-results/testDebugUnitTest').glob('TEST-*.xml')]
    failures = [case.attrib.get('name') for suite in suites for case in suite.findall('testcase')
                if case.find('failure') is not None]
    if expected_failure is None:
        assert result.returncode == 0 and suites and not failures, (label, result.returncode, failures)
    else:
        assert result.returncode != 0 and expected_failure in failures, (label, result.returncode, failures)
    print(label + ': verified', flush=True)
    return {'exit': result.returncode, 'failedCases': failures}

proofs = {}
try:
    initial = run('sync-config-initial')
    for name, path, substitutions, failed_case in (
        ('group-bound', helper, [('Regex("[a-z0-9][a-z0-9_-]{0,47}")', 'Regex("[a-z0-9][a-z0-9_-]{0,48}")')],
         'invalid sync group inputs reject without producing a configuration'),
        ('encoded-query-key', helper, [('val name = decode(rawName)', 'val name = rawName')],
         'invalid sync group inputs reject without producing a configuration'),
        ('null-extra', codec, [('        hasSyncGroup && syncGroup == null -> PhotosSyncGroupCommand.Invalid\n', ''),
                              ('PhotosSyncGroupCommand.Set(requireNotNull(syncGroup))', 'PhotosSyncGroupCommand.Set(syncGroup.orEmpty())')],
         'Photos sync group contract only admits one bounded receiver operation'),
    ):
        source = originals[path].decode()
        for before, after in substitutions:
            assert source.count(before) == 1, (name, before)
            source = source.replace(before, after)
        path.write_text(source)
        try:
            red = run('sync-config-' + name + '-red', failed_case)
        finally:
            path.write_bytes(originals[path])
        assert path.read_bytes() == originals[path]
        green = run('sync-config-' + name + '-green')
        proofs[name] = {'red': red, 'green': green, 'restoredSourceSha256': digest(path)}
    assert test_hashes == {str(p.relative_to(root)): digest(p) for p in tests}
    (output/'sync-configuration-proof.json').write_text(json.dumps({
        'passed': True, 'initial': initial, 'proofs': proofs, 'unchangedTests': test_hashes,
        'restoredSources': {str(p.relative_to(root)): digest(p) for p in originals},
    }, indent=2) + '\n')
finally:
    for path, original in originals.items():
        path.write_bytes(original)
