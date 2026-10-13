#!/usr/bin/env python3
"""NAS-only source proof that the Frame settings action opens Android Settings, not Wi-Fi."""
from __future__ import annotations
import hashlib, json, shutil, subprocess, sys
from pathlib import Path
import xml.etree.ElementTree as ElementTree

if not __debug__: raise RuntimeError('optimized Python is not permitted')
if len(sys.argv) != 2: raise SystemExit(f'usage: {Path(sys.argv[0]).name} <repository-root>')
root=Path(sys.argv[1]).resolve(); frame=root/'frameos'; out=root/'reports'/'frameos-legacy'; out.mkdir(parents=True,exist_ok=True)
source=frame/'app/src/main/kotlin/com/wyattfleming/frameos/MainActivity.kt'; test=frame/'app/src/test/kotlin/com/wyattfleming/frameos/MainActivitySystemSettingsContractTest.kt'; report=frame/'app/build/test-results/testDebugUnitTest'; xml_name='TEST-com.wyattfleming.frameos.MainActivitySystemSettingsContractTest.xml'
for path in (source,test):
    if not path.is_file() or path.is_symlink(): raise RuntimeError(f'missing regular file: {path}')
original=source.read_bytes(); test_hash=hashlib.sha256(test.read_bytes()).hexdigest(); before='Intent(android.provider.Settings.ACTION_SETTINGS)'; after='Intent(android.provider.Settings.ACTION_WIFI_SETTINGS)'; command=['./gradlew','--no-daemon','--max-workers=2','-PframeosTarget=legacyArmv7',':app:testDebugUnitTest','--tests','com.wyattfleming.frameos.MainActivitySystemSettingsContractTest']
def run(label,failures):
    if report.exists(): shutil.rmtree(report)
    log=out/f'system-settings-{label}.log'
    with log.open('w') as stream: result=subprocess.run(command,cwd=frame,stdout=stream,stderr=subprocess.STDOUT)
    matches=list(report.rglob(xml_name)); assert len(matches)==1,matches
    xml=out/f'system-settings-{label}.xml'; shutil.copy2(matches[0],xml)
    doc=ElementTree.parse(xml).getroot(); cases=list(doc.iter('testcase')); failed=list(doc.iter('failure')); errors=list(doc.iter('error'))
    assert len(cases)==1 and len(failed)==failures and not errors,(len(cases),len(failed),len(errors))
    return result.returncode,log.name,xml.name
try:
    text=original.decode(); assert text.count(before)==1 and after not in text
    source.write_text(text.replace(before,after),encoding='utf-8')
    negative_exit,negative_log,negative_xml=run('red',1); assert negative_exit!=0
finally:
    source.write_bytes(original); assert source.read_bytes()==original
restored_exit,restored_log,restored_xml=run('green',0); assert restored_exit==0 and hashlib.sha256(test.read_bytes()).hexdigest()==test_hash
(out/'system-settings-source-proof.json').write_text(json.dumps({'passed':True,'negative_exit':negative_exit,'negative_tests':1,'negative_assertion_failures':1,'restored_green_exit':restored_exit,'restored_tests':1,'restored_failures':0,'restored_source_sha256':hashlib.sha256(source.read_bytes()).hexdigest(),'unchanged_test_sha256':test_hash,'negative_log':negative_log,'negative_junit_xml':negative_xml,'restored_log':restored_log,'restored_junit_xml':restored_xml},indent=2)+'\n',encoding='utf-8')
print('Android Settings destination red / exact restore green verified')
