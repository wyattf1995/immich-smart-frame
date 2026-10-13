from pathlib import Path
import hashlib,json,subprocess,xml.etree.ElementTree as ET
root=Path(__file__).resolve().parents[1]
command=['../gradlew','-p','.','--no-daemon','--max-workers=2',':app:testDebugUnitTest']
test_originals={p:p.read_bytes() for p in (root/'app/src/test').rglob('*.java')}
def run(label,test_class=None):
 args=command+(['--tests','com.wyattfleming.frametv.probe4k.'+test_class] if test_class else [])
 p=subprocess.run(args,cwd=root,stdout=subprocess.PIPE,stderr=subprocess.STDOUT,text=True)
 (root/'reports'/('proof-'+label+'.log')).write_text(p.stdout)
 return p.returncode
cases=[
 ('ProbeMode.java','ProbeModeTest',b'UHD(3840, 2160, "4K buffer");',b'UHD(1920, 1080, "4K buffer");'),
 ('YuvPlaneFiller.java','YuvPlaneFillerTest',b'if (rowStride < minimumRowStride) {',b'if (false) {'),
 ('VideoProbeSpec.java','VideoProbeSpecTest',b'if (width != WIDTH || height != HEIGHT) {',b'if (false) {'),
]
proofs=[]
for filename,test_class,needle,replacement in cases:
 source=root/'app/src/main/java/com/wyattfleming/frametv/probe4k'/filename
 original=source.read_bytes()
 assert original.count(needle)==1
 try:
  source.write_bytes(original.replace(needle,replacement))
  red=run(test_class+'-red',test_class)
  assert red!=0,'Source-only negative unexpectedly passed'
  xml=root/('app/build/test-results/testDebugUnitTest/TEST-com.wyattfleming.frametv.probe4k.'+test_class+'.xml')
  suite=ET.parse(xml).getroot()
  assert int(suite.get('failures','0'))>0,'Red must be assertion failure, not build failure'
  assert any('Assertion' in f.get('type','') for f in suite.iter('failure'))
 finally:
  source.write_bytes(original)
 assert source.read_bytes()==original
 assert all(p.read_bytes()==data for p,data in test_originals.items())
 green=run(test_class+'-green',test_class)
 assert green==0
 proofs.append({'source':filename,'redExit':red,'greenExit':green,'sourceSha256':hashlib.sha256(original).hexdigest(),'mutation':needle.decode()+' -> '+replacement.decode()})
# Preserve the complete suite results in the exported evidence, not just the last focused test.
assert (green:=run('all-green'))==0
(root/'reports/source-proof.json').write_text(json.dumps({'passed':True,'sourceRestoredExactly':True,'testsUnchanged':True,'redExit':proofs[0]['redExit'],'greenExit':green,'proofs':proofs},indent=2)+'\n')
print('All source-only regressions failed; exact sources restored; all unchanged tests passed.')
