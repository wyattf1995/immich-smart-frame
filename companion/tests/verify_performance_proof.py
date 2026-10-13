#!/usr/bin/env python3
"""Temporary source-only mutations against synthetic-data guards, exact restore."""
import hashlib
import json
from pathlib import Path
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[2]
SOURCE = ROOT/'companion/server.py'
TEST = ROOT/'companion/tests/test_performance_contract.py'
OUT = ROOT/'reports/companion-performance-proof'
OUT.mkdir(parents=True, exist_ok=True)
original = SOURCE.read_bytes()
test_hash = hashlib.sha256(TEST.read_bytes()).hexdigest()
results = []

def run(name, stage, test):
    result = subprocess.run([sys.executable, '-m', 'unittest', 'companion.tests.test_performance_contract.PerformanceContract.'+test], cwd=ROOT, capture_output=True, text=True, timeout=45)
    (OUT/(name+'-'+stage+'.log')).write_text(result.stdout+result.stderr)
    return result

def prove(name, test, mutate):
    before = run(name,'green-before',test)
    assert before.returncode == 0, (name, 'initial test failed')
    try:
        changed = mutate(original.decode())
        assert changed != original.decode(), 'no source mutation'
        SOURCE.write_text(changed)
        red = run(name,'red',test)
        assert red.returncode != 0 and test in red.stderr and 'FAILED (' in red.stderr, (name,'guard did not fail')
    finally:
        SOURCE.write_bytes(original)
    assert SOURCE.read_bytes() == original
    after = run(name,'green-after',test)
    assert after.returncode == 0, (name,'restored test failed')
    assert hashlib.sha256(TEST.read_bytes()).hexdigest() == test_hash
    results.append({'name':name,'test':test,'greenBefore':before.returncode,'red':red.returncode,'greenAfter':after.returncode,'sourceRestored':True,'testUnchanged':True})
    (OUT/'receipt.json').write_text(json.dumps({'status':'in-progress','proofs':results},indent=2)+'\n')

def replace_once(old,new):
    def mutate(source):
        assert source.count(old) == 1, 'source needle drift: '+old
        return source.replace(old,new)
    return mutate

prove('photo-policy-revision','test_settings_and_each_preference_mutation_invalidate',replace_once("row['preferences_revision']",'0'))
prove('scoped-poll-expiry','test_poll_never_calls_global_maintenance_and_uses_device_expiry_index',replace_once('self._expire_device(db, device, now)','self._expire(db)'))
prove('reader-concurrency','test_readers_do_not_acquire_writer_lock',replace_once("connection.execute('BEGIN IMMEDIATE' if write else 'BEGIN')","connection.execute('BEGIN IMMEDIATE')"))
baseline = subprocess.check_output(['git','show','ba0a374:companion/server.py'],cwd=ROOT,text=True)
def restore_old_export(source):
    start = baseline.index('    def export_preferences(self):')
    end = baseline.index('    def state(self):',start)
    left = source.index('    def export_preferences(self):')
    right = source.index('    def state(self):',left)
    return source[:left]+baseline[start:end]+source[right:]
prove('streaming-export','test_export_peak_memory_is_bounded_and_failure_is_atomic',restore_old_export)
(OUT/'receipt.json').write_text(json.dumps({'status':'passed','sourceSha256':hashlib.sha256(original).hexdigest(),'testSha256':test_hash,'proofs':results},indent=2)+'\n')
print(json.dumps({'status':'passed','proofs':len(results),'receipt':str(OUT/'receipt.json')}))
