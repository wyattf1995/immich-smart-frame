#!/usr/bin/env python3
"""NAS-only proof that companion revisions, absent hidden policy, and present settings stay safe."""
from __future__ import annotations
import hashlib, json, shutil, subprocess, sys
from pathlib import Path
import xml.etree.ElementTree as ElementTree

if not __debug__: raise RuntimeError('optimized Python is not permitted')
if len(sys.argv) != 2: raise SystemExit(f'usage: {Path(sys.argv[0]).name} <repository-root>')
root=Path(sys.argv[1]).resolve(); frame=root/'frameos'; out=root/'reports'/'frameos-legacy'; out.mkdir(parents=True,exist_ok=True)
source=frame/'app/src/main/kotlin/com/wyattfleming/frameos/control/FrameCompanionEnvelope.kt'; test=frame/'app/src/test/kotlin/com/wyattfleming/frameos/control/FrameCompanionEnvelopeDecoderTest.kt'; report=frame/'app/build/test-results/testDebugUnitTest'; xml_name='TEST-com.wyattfleming.frameos.control.FrameCompanionEnvelopeDecoderTest.xml'
for path in (source,test):
    if not path.is_file() or path.is_symlink(): raise RuntimeError(f'missing regular file: {path}')
original=source.read_bytes(); test_hash=hashlib.sha256(test.read_bytes()).hexdigest(); command=['./gradlew','--no-daemon','--max-workers=2','-PframeosTarget=legacyArmv7',':app:testDebugUnitTest','--tests','com.wyattfleming.frameos.control.FrameCompanionEnvelopeDecoderTest']

def run(label, failures):
    if report.exists(): shutil.rmtree(report)
    log=out/f'companion-settings-envelope-{label}.log'
    with log.open('w') as stream: result=subprocess.run(command,cwd=frame,stdout=stream,stderr=subprocess.STDOUT)
    matches=list(report.rglob(xml_name)); assert len(matches)==1,matches
    xml=out/f'companion-settings-envelope-{label}.xml'; shutil.copy2(matches[0],xml)
    parsed=ElementTree.parse(xml).getroot(); cases=list(parsed.iter('testcase')); failed=list(parsed.iter('failure')); errors=list(parsed.iter('error'))
    assert len(cases)==5 and len(failed)==failures and not errors,(len(cases),len(failed),len(errors))
    return {'negative_exit' if failures else 'restored_green_exit':result.returncode,'log':log.name,'junit_xml':xml.name,'tests':len(cases),'failures':len(failed)}

mutations={
 'revision_match':('require(FrameCompanionSnapshotRevision.acceptsUnchanged(sentRevision, revision))','require(true)'),
 'hidden_absent':('if (!root.has("hiddenAssets")) return null','if (!root.has("hiddenAssets")) return emptySet()'),
 'present_settings':('require(!root.has("settings") || settings != null)','require(true)'),
}
checks={}
try:
    for name,(before,after) in mutations.items():
        text=original.decode(); assert text.count(before)==1 and after not in text
        source.write_text(text.replace(before,after),encoding='utf-8')
        negative=run(name+'-red',1); assert negative['negative_exit']!=0
        checks[name]={'negative':negative}
        source.write_bytes(original); assert source.read_bytes()==original
finally:
    source.write_bytes(original); assert source.read_bytes()==original
for name in mutations:
    green=run(name+'-green',0); assert green['restored_green_exit']==0
    checks[name]['restored']=green
assert hashlib.sha256(test.read_bytes()).hexdigest()==test_hash
(out/'companion-settings-envelope-source-proof.json').write_text(json.dumps({'passed':True,'checks':checks,'restored_source_sha256':hashlib.sha256(source.read_bytes()).hexdigest(),'unchanged_test_sha256':test_hash},indent=2)+'\n',encoding='utf-8')
print('Companion revision, hidden policy, and settings red / exact restore green verified')
