#!/usr/bin/env python3
"""Source-only mutation proofs for durable Companion export recovery."""
import hashlib
import json
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
SOURCE = ROOT / 'companion/server.py'
TEST_FILE = ROOT / 'companion/tests/test_server.py'
ORIGINAL = SOURCE.read_bytes()
TEST_HASH = hashlib.sha256(TEST_FILE.read_bytes()).hexdigest()
OUT = ROOT / 'reports/companion-export-proof'
OUT.mkdir(parents=True, exist_ok=True)

PROOFS = [
    ('desired-revision', 'companion.tests.test_server.CompanionTests.test_failed_export_keeps_sqlite_policy_dirty_then_retries',
     "            if self.preferences_export:\n                db.execute('UPDATE export_state SET desired_revision=desired_revision+1 WHERE name=?', (EXPORT_STATE,))\n        try:", "            if self.preferences_export:\n                db.execute('SELECT 1')\n        try:"),
    ('retry-handoff-lock', 'companion.tests.test_server.CompanionTests.test_retry_handoff_starts_new_worker_after_concurrent_failure',
     "                with self.export_retry_lock:\n                    if self.closed:\n", "                if True:\n                    if self.closed:\n"),
    ('nonblocking-publication-marker', 'companion.tests.test_performance_contract.PerformanceContract.test_readers_do_not_acquire_writer_lock',
     "connection.execute('PRAGMA busy_timeout=0')", "connection.execute('PRAGMA busy_timeout=10000')"),
    ('startup-dirty-revision', 'companion.tests.test_server.CompanionTests.test_failed_startup_export_recovers_missing_file_and_new_device',
     "            # Every configured startup refreshes the mounted snapshot. Mark it\n            # durable first, so a missing mount/file or added device also retries\n            # after a transient first export error even if prior revisions matched.\n            if self.preferences_export:\n                db.execute('UPDATE export_state SET desired_revision=desired_revision+1 WHERE name=?', (EXPORT_STATE,))", "            # startup desired revision disabled by fixture"),
    ('positive-error-backoff', 'companion.tests.test_server.CompanionTests.test_retry_error_after_zero_delay_returns_to_positive_backoff',
     "delay = min(EXPORT_RETRY_MAX_SECONDS, max(EXPORT_RETRY_INITIAL_SECONDS, delay * 2))", "delay = min(EXPORT_RETRY_MAX_SECONDS, delay * 2)"),
    ('shutdown-publication-fence', 'companion.tests.test_server.CompanionTests.test_close_fences_export_blocked_before_publication',
     '                with self.export_retry_lock:\n                    if self.closed: return False\n                    os.replace(temp,self.preferences_export)', '                os.replace(temp,self.preferences_export)'),
]

def run(name, stage, test):
    result = subprocess.run([sys.executable, '-m', 'unittest', test], cwd=ROOT, capture_output=True, text=True, timeout=45)
    (OUT / f'{name}-{stage}.log').write_text(result.stdout + result.stderr)
    return result

results = []
for name, test, needle, replacement in PROOFS:
    before = run(name, 'green-before', test)
    assert before.returncode == 0, f'{name}: initial guard not green'
    text = ORIGINAL.decode()
    assert text.count(needle) == 1, f'{name}: expected exactly one mutation target'
    try:
        SOURCE.write_text(text.replace(needle, replacement))
        red = run(name, 'red', test)
        assert red.returncode != 0 and test.rsplit('.', 1)[-1] in red.stderr and 'FAILED (' in red.stderr, f'{name}: guard did not fail'
    finally:
        SOURCE.write_bytes(ORIGINAL)
    after = run(name, 'green-after', test)
    assert after.returncode == 0, f'{name}: restored source not green'
    assert SOURCE.read_bytes() == ORIGINAL, f'{name}: source not restored'
    assert hashlib.sha256(TEST_FILE.read_bytes()).hexdigest() == TEST_HASH, f'{name}: test changed'
    results.append({'name': name, 'test': test, 'greenBefore': before.returncode, 'red': red.returncode, 'greenAfter': after.returncode, 'sourceRestored': True, 'testUnchanged': True})
receipt = {'status': 'passed', 'source': str(SOURCE), 'sourceSha256': hashlib.sha256(ORIGINAL).hexdigest(), 'testSha256': TEST_HASH, 'proofs': results}
(OUT / 'receipt.json').write_text(json.dumps(receipt, indent=2) + '\n')
print(json.dumps(receipt, indent=2))
