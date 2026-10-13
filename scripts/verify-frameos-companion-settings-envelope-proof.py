#!/usr/bin/env python3
"""NAS-only proof that malformed present companion settings cannot retain a revision."""
import hashlib, json, shutil, subprocess, sys
from pathlib import Path
import xml.etree.ElementTree as ElementTree

if not __debug__: raise RuntimeError("optimized Python is not permitted")
if len(sys.argv) != 2: raise SystemExit(f"usage: {Path(sys.argv[0]).name} <repository-root>")
root=Path(sys.argv[1]).resolve(); frame=root/'frameos'; out=root/'reports'/'frameos-legacy'; out.mkdir(parents=True,exist_ok=True)
source=frame/'app/src/main/kotlin/com/wyattfleming/frameos/control/FrameCompanionEnvelope.kt'
test=frame/'app/src/test/kotlin/com/wyattfleming/frameos/control/FrameCompanionEnvelopeDecoderTest.kt'
report=frame/'app/build/test-results/testDebugUnitTest'; xml_name='TEST-com.wyattfleming.frameos.control.FrameCompanionEnvelopeDecoderTest.xml'
for path in (source,test):
    if not path.is_file() or path.is_symlink(): raise RuntimeError(f'missing regular file: {path}')
original=source.read_bytes(); source_hash=hashlib.sha256(original).hexdigest(); test_hash=hashlib.sha256(test.read_bytes()).hexdigest()
before='require(!root.has("settings") || settings != null)'; after='require(true)'
command=['./gradlew','--no-daemon','--max-workers=2','-PframeosTarget=legacyArmv7',':app:testDebugUnitTest','--tests','com.wyattfleming.frameos.control.FrameCompanionEnvelopeDecoderTest']
def run(label, expected):
    if report.exists(): shutil.rmtree(report)
    log=out/f'{label}.log'
    with log.open('w') as stream: result=subprocess.run(command,cwd=frame,stdout=stream,stderr=subprocess.STDOUT)
    matches=list(report.rglob(xml_name)); assert len(matches)==1,matches
    xml=out/f'{label}.xml'; shutil.copy2(matches[0],xml)
    parsed=ElementTree.parse(xml).getroot(); cases=list(parsed.iter('testcase')); failures=list(parsed.iter('failure')); errors=list(parsed.iter('error'))
    assert len(cases)==5 and len(failures)==expected and not errors,(len(cases),len(failures),len(errors))
    return result.returncode,log.name,xml.name
try:
    text=original.decode(); assert text.count(before)==1 and after not in text
    source.write_text(text.replace(before,after),encoding='utf-8')
    negative_exit,negative_log,negative_xml=run('companion-settings-envelope-red',1); assert negative_exit!=0
finally:
    source.write_bytes(original); assert source.read_bytes()==original
restored_exit,restored_log,restored_xml=run('companion-settings-envelope-green',0)
assert restored_exit==0 and hashlib.sha256(test.read_bytes()).hexdigest()==test_hash
(out/'companion-settings-envelope-source-proof.json').write_text(json.dumps({'passed':True,'negative_exit':negative_exit,'negative_tests':5,'negative_assertion_failures':1,'restored_green_exit':restored_exit,'restored_tests':5,'restored_failures':0,'restored_source_sha256':hashlib.sha256(source.read_bytes()).hexdigest(),'unchanged_test_sha256':test_hash,'negative_log':negative_log,'negative_junit_xml':negative_xml,'restored_log':restored_log,'restored_junit_xml':restored_xml},indent=2)+'\n',encoding='utf-8')
print('Companion settings envelope red / exact restore green verified')
