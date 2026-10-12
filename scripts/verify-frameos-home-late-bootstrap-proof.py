#!/usr/bin/env python3
"""Prove the late Home bootstrap liveness handoff with a source-only mutation."""
import hashlib
import json
from pathlib import Path
import subprocess
import sys

root = Path(sys.argv[1]).resolve()
source = root / 'examples/frameos/frameos-touchscreen-panel.html'
test = root / 'scripts/tests/test-frameos-home-late-bootstrap.mjs'
out = root / 'reports/frameos-legacy'
out.mkdir(parents=True, exist_ok=True)
original = source.read_bytes()
test_hash = hashlib.sha256(test.read_bytes()).hexdigest()
needle = b'''            else {
              customHomeProbe = null;
              scheduleLateHomeDiscovery(bootstrap, getHass);
            }'''
replacement = b'''            else {
              customHomeProbe = null;
            }'''
assert original.count(needle) == 1

def run(label):
    result = subprocess.run(['node', 'scripts/tests/test-frameos-home-late-bootstrap.mjs'], cwd=root, text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, check=False)
    log = out / f'{label}.log'
    log.write_text(result.stdout, encoding='utf-8')
    return result.returncode, log.name, result.stdout

try:
    source.write_bytes(original.replace(needle, replacement))
    red_exit, red_log, red_output = run('home-late-bootstrap-source-red')
    assert red_exit != 0
    assert 'only one 1 Hz late discovery timer is armed after the fast window' in red_output
finally:
    source.write_bytes(original)
    assert source.read_bytes() == original

green_exit, green_log, _ = run('home-late-bootstrap-restored-green')
assert green_exit == 0
assert hashlib.sha256(test.read_bytes()).hexdigest() == test_hash
receipt = {
    'passed': True,
    'recipe': 'preserve the 12-second native fallback and schedule bounded 1 Hz late Home discovery',
    'negative_exit': red_exit,
    'negative_assertion_failures': 1,
    'negative_expected_assertion': 'only one 1 Hz late discovery timer is armed after the fast window',
    'negative_log': red_log,
    'restored_green_exit': green_exit,
    'restored_log': green_log,
    'restored_source_sha256': hashlib.sha256(source.read_bytes()).hexdigest(),
    'unchanged_test_sha256': test_hash,
}
(out / 'home-late-bootstrap-source-proof.json').write_text(json.dumps(receipt, indent=2) + '\n', encoding='utf-8')
print('Home late bootstrap source-only red / exact restore green verified')
