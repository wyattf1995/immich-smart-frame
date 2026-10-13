#!/usr/bin/env python3
"""Prove an external paused shared settle refreshes native photo metadata."""
import hashlib, json, re, subprocess, sys
import xml.etree.ElementTree as ET
from pathlib import Path
root=Path(sys.argv[1]).resolve();frame=root/'frameos';out=root/'reports/frame-sync-android';out.mkdir(parents=True,exist_ok=True)
source=frame/'app/src/main/kotlin/com/wyattfleming/frameos/photos/FramePhotoBridge.kt'
test=frame/'app/src/test/kotlin/com/wyattfleming/frameos/photos/FrameOfflineReserveTest.kt'
original=source.read_bytes();test_hash=hashlib.sha256(test.read_bytes()).hexdigest()
command=['./gradlew','--no-daemon','--max-workers=2',':app:testDebugUnitTest','--tests','com.wyattfleming.frameos.photos.FrameOfflineReserveTest']
def run(label,negative=False):
    with (out/(label+'.log')).open('w') as log:r=subprocess.run(command,cwd=frame,stdout=log,stderr=subprocess.STDOUT)
    suites=[ET.parse(p).getroot() for p in (frame/'app/build/test-results/testDebugUnitTest').glob('TEST-*.xml')]
    failures=[c.get('name') for s in suites for c in s.findall('testcase') if c.find('failure') is not None]
    if negative:assert r.returncode!=0 and 'trusted paused shared settle refreshes one snapshot for the new current asset' in failures,(label,r.returncode,failures)
    else:assert r.returncode==0 and suites and not failures,(label,r.returncode,failures)
    print(label+': verified',flush=True);return {'exit':r.returncode,'failedCases':failures}
try:
    initial=run('shared-settle-initial')
    text=original.decode()
    # The bounded regression source block is finalized with the implementation.
    start='        // BEGIN shared paused snapshot refresh\n'
    end='        // END shared paused snapshot refresh\n'
    assert text.count(start)==1 and text.count(end)==1
    left=text.index(start);right=text.index(end)+len(end)
    source.write_text(text[:left]+text[right:])
    try:red=run('shared-settle-red',True)
    finally:source.write_bytes(original)
    assert source.read_bytes()==original
    green=run('shared-settle-green')
    assert hashlib.sha256(test.read_bytes()).hexdigest()==test_hash
    (out/'shared-settle-proof.json').write_text(json.dumps({'passed':True,'initial':initial,'red':red,'green':green,'restoredSource':str(source.relative_to(root)),'restoredSha256':hashlib.sha256(original).hexdigest(),'unchangedTestSha256':test_hash},indent=2)+'\n')
finally:source.write_bytes(original)
