#!/usr/bin/env python3
"""NAS-only red/restore-green proof for the Settings return overlay lifecycle and API23 type."""
from __future__ import annotations
import hashlib, json, shutil, subprocess, sys
from pathlib import Path
import xml.etree.ElementTree as ElementTree

if not __debug__: raise RuntimeError("optimized Python is not permitted for proof validation")
if len(sys.argv) != 2: raise SystemExit(f"usage: {Path(sys.argv[0]).name} <repository-root>")
root=Path(sys.argv[1]).resolve(); frame=root/'frameos'; out=root/'reports'/'frameos-legacy'; out.mkdir(parents=True,exist_ok=True)
source=frame/'app/src/main/kotlin/com/wyattfleming/frameos/ui/FrameExternalSettingsReturnController.kt'
tests={
 'lifecycle': frame/'app/src/test/kotlin/com/wyattfleming/frameos/ui/FrameExternalSettingsReturnControllerTest.kt',
 'legacy_type': frame/'app/src/test/kotlin/com/wyattfleming/frameos/ui/FrameExternalSettingsOverlayWindowTypeTest.kt',
 'lint_isolation': frame/'app/src/test/kotlin/com/wyattfleming/frameos/ui/FrameExternalSettingsOverlayWindowTypeTest.kt',
 'text_resource': frame/'app/src/test/kotlin/com/wyattfleming/frameos/ui/FrameExternalSettingsOverlayWindowTypeTest.kt',
}
report=frame/'app/build/test-results/testDebugUnitTest'
classes={
 'lifecycle':'com.wyattfleming.frameos.ui.FrameExternalSettingsReturnControllerTest',
 'legacy_type':'com.wyattfleming.frameos.ui.FrameExternalSettingsOverlayWindowTypeTest',
 'lint_isolation':'com.wyattfleming.frameos.ui.FrameExternalSettingsOverlayWindowTypeTest',
 'text_resource':'com.wyattfleming.frameos.ui.FrameExternalSettingsOverlayWindowTypeTest',
}
xml_names={name:'TEST-'+value+'.xml' for name,value in classes.items()}
for path in (source,*tests.values()):
    if not path.is_file() or path.is_symlink(): raise RuntimeError(f'missing regular file: {path}')
original=source.read_bytes(); source_hash=hashlib.sha256(original).hexdigest(); test_hashes={name:hashlib.sha256(path.read_bytes()).hexdigest() for name,path in tests.items()}
base=['./gradlew','--no-daemon','--max-workers=2','--rerun-tasks','-PframeosTarget=legacyArmv7',':app:testDebugUnitTest']

def run(name,label,failures):
    if report.exists(): shutil.rmtree(report)
    log=out/f'settings-return-overlay-{label}.log'
    with log.open('w') as stream:
        result=subprocess.run(base+['--tests',classes[name]],cwd=frame,stdout=stream,stderr=subprocess.STDOUT)
    matches=list(report.rglob(xml_names[name])); assert len(matches)==1,matches
    xml=out/f'settings-return-overlay-{label}.xml'; shutil.copy2(matches[0],xml)
    root_xml=ElementTree.parse(xml).getroot(); cases=list(root_xml.iter('testcase')); failed=list(root_xml.iter('failure')); errors=list(root_xml.iter('error'))
    expected={'lifecycle':5,'legacy_type':4,'lint_isolation':4,'text_resource':4}[name]
    assert len(cases)==expected and len(failed)==failures and not errors,(name,len(cases),len(failed),len(errors))
    return {'negative_exit' if failures else 'restored_green_exit':result.returncode,'log':log.name,'junit_xml':xml.name,'tests':len(cases),'failures':len(failed)}

checks={}
mutations={
 'lifecycle':('if (!adapter.canDrawOverlays()) return Outcome.OVERLAY_PERMISSION_DENIED','if (!adapter.canDrawOverlays()) return Outcome.LAUNCHED'),
 'legacy_type':('if (apiLevel >= Build.VERSION_CODES.O) applicationOverlayForAndroid8OrLater() else PHONE','if (apiLevel >= Build.VERSION_CODES.M) applicationOverlayForAndroid8OrLater() else PHONE'),
 'lint_isolation':('@RequiresApi(Build.VERSION_CODES.O)','@Suppress("unused")'),
 'text_resource':('context.getString(R.string.return_to_frameos)','"Return to FrameOS"'),
}
try:
    for name,(before,after) in mutations.items():
        text=original.decode(); assert text.count(before)==1 and after not in text
        source.write_text(text.replace(before,after),encoding='utf-8')
        negative=run(name,name+'-red',1); assert negative['negative_exit']!=0
        checks[name]={'negative':negative}
        source.write_bytes(original); assert source.read_bytes()==original
finally:
    source.write_bytes(original); assert source.read_bytes()==original
for name in mutations:
    green=run(name,name+'-green',0); assert green['restored_green_exit']==0
    checks[name]['restored']=green
assert {name:hashlib.sha256(path.read_bytes()).hexdigest() for name,path in tests.items()}==test_hashes
(out/'settings-return-overlay-source-proof.json').write_text(json.dumps({
 'passed':True,'checks':checks,'restored_source_sha256':hashlib.sha256(source.read_bytes()).hexdigest(),
 'unchanged_test_sha256':test_hashes,
},indent=2)+'\n',encoding='utf-8')
print('Settings return overlay lifecycle and API23 type red / exact restore green verified')
