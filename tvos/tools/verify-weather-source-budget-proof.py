#!/usr/bin/env python3
"""Prove forecast cardinality and worker-side model construction guards red/restore-green."""
from __future__ import annotations
import hashlib, json, shutil, subprocess, sys
from pathlib import Path
import xml.etree.ElementTree as ElementTree

if not __debug__: raise RuntimeError('optimized Python is not permitted')
if len(sys.argv) != 2: raise SystemExit(f'usage: {Path(sys.argv[0]).name} <tvos-root>')
root=Path(sys.argv[1]).resolve(); source=root/'app/src/main/java/com/wyattfleming/frametv/WeatherSource.java'; test=root/'app/src/test/java/com/wyattfleming/frametv/WeatherSourceTest.java'; validation=root/'validation'; validation.mkdir(exist_ok=True)
if not source.is_file() or source.is_symlink() or not test.is_file() or test.is_symlink(): raise RuntimeError('WeatherSource source/test must be regular files')
original=source.read_bytes(); source_hash=hashlib.sha256(original).hexdigest(); test_hash=hashlib.sha256(test.read_bytes()).hexdigest(); report=root/'app/build/test-results/testDebugUnitTest'; xml_name='TEST-com.wyattfleming.frametv.WeatherSourceTest.xml'; command=['./gradlew','--no-daemon','--max-workers=2',':app:testDebugUnitTest','--tests','com.wyattfleming.frametv.WeatherSourceTest']

def run(label, failures):
    if report.exists(): shutil.rmtree(report)
    log=validation/f'weather-source-budget-{label}.log'
    with log.open('w') as stream: result=subprocess.run(command,cwd=root,stdout=stream,stderr=subprocess.STDOUT)
    matches=list(report.rglob(xml_name)); assert len(matches)==1,matches
    xml=validation/f'weather-source-budget-{label}.xml'; shutil.copy2(matches[0],xml)
    doc=ElementTree.parse(xml).getroot(); cases=list(doc.iter('testcase')); failed=list(doc.iter('failure')); errors=list(doc.iter('error'))
    assert len(cases)==3 and len(failed)==failures and not errors,(len(cases),len(failed),len(errors))
    return {'negative_exit' if failures else 'restored_green_exit':result.returncode,'log':log.name,'junit_xml':xml.name,'tests':len(cases),'failures':len(failed)}

checks={
 'hourly_cap':('private static final int MAX_HOURLY_ENTRIES = 168;','private static final int MAX_HOURLY_ENTRIES = 169;'),
 # Scope this mutation to the live network fetch, not the similarly named cached parser.
 'worker_decode':('''            try {
                response = requestForecast(location);
                parsed = parseForecast(response);
                preferences.saveForecast(sourceKey, response, System.currentTimeMillis());
            } catch (Exception ignored) { }''','''            try {
                response = requestForecast(location);
                parsed = null;
                preferences.saveForecast(sourceKey, response, System.currentTimeMillis());
            } catch (Exception ignored) { }'''),
}
result={}
try:
    for name,(before,after) in checks.items():
        text=original.decode(); assert text.count(before)==1 and after not in text
        source.write_text(text.replace(before,after),encoding='utf-8')
        negative=run(name+'-red',1); assert negative['negative_exit']!=0
        result[name]={'negative':negative}
        source.write_bytes(original); assert source.read_bytes()==original
finally:
    source.write_bytes(original); assert source.read_bytes()==original
for name in checks:
    green=run(name+'-green',0); assert green['restored_green_exit']==0
    result[name]['restored']=green
source_restored_exactly = source.read_bytes() == original
test_unchanged = hashlib.sha256(test.read_bytes()).hexdigest() == test_hash
assert source_restored_exactly
assert test_unchanged
(validation/'weather-source-budget-proof.json').write_text(json.dumps({'passed':True,'checks':result,'source_restored_exactly':source_restored_exactly,'restored_source_sha256':hashlib.sha256(source.read_bytes()).hexdigest(),'unchanged_test_sha256':test_hash,'test_unchanged':test_unchanged},indent=2)+'\n',encoding='utf-8')
print('Weather source budget and worker decode red / exact restore green verified')
