from pathlib import Path
import hashlib,json,subprocess,xml.etree.ElementTree as ET
root=Path(__file__).resolve().parents[1]
source=root/'app/src/main/java/com/wyattfleming/frametv/probe4k/ProbeMode.java'
test=root/'app/src/test/java/com/wyattfleming/frametv/probe4k/ProbeModeTest.java'
original=source.read_bytes();test_original=test.read_bytes()
needle=b'UHD(3840, 2160, "4K buffer");'
assert original.count(needle)==1
command=['../gradlew','-p','.','--no-daemon','--max-workers=2',':app:testDebugUnitTest','--tests','com.wyattfleming.frametv.probe4k.ProbeModeTest']
def run(label):
 p=subprocess.run(command,cwd=root,stdout=subprocess.PIPE,stderr=subprocess.STDOUT,text=True)
 (root/'reports'/('proof-'+label+'.log')).write_text(p.stdout)
 return p.returncode
red=None;green=None
try:
 source.write_bytes(original.replace(needle,b'UHD(1920, 1080, "4K buffer");'))
 red=run('red');assert red!=0,'Source-only negative unexpectedly passed'
 xml=root/'app/build/test-results/testDebugUnitTest/TEST-com.wyattfleming.frametv.probe4k.ProbeModeTest.xml'
 suite=ET.parse(xml).getroot()
 assert int(suite.get('failures','0'))>0,'Red must be assertion failure, not build failure'
 assert any('Assertion' in f.get('type','') for f in suite.iter('failure'))
finally:
 source.write_bytes(original)
assert source.read_bytes()==original and test.read_bytes()==test_original
assert (green:=run('green'))==0
(root/'reports/source-proof.json').write_text(json.dumps({'passed':True,'sourceRestoredExactly':True,'testsUnchanged':True,'redExit':red,'greenExit':green,'sourceSha256':hashlib.sha256(original).hexdigest(),'mutation':'Only UHD requested dimensions replaced3840x2160 with1920x1080; no tests changed.'},indent=2)+'\n')
print('Source-only4K dimension regression failed, exact source restored, unchanged tests passed.')
