"""Prove the unchanged detail guard rejects an overly compressed generated fixture."""
from pathlib import Path
import json,subprocess
root=Path(__file__).resolve().parents[1]
source=root/'tools/generate-compatible-video-fixture.py';test=root/'tools/verify-compatible-video-fixture.py'
original=source.read_bytes();original_test=test.read_bytes();needle=b"'-crf','0'"
assert original.count(needle)==1

def run(script,label):
 p=subprocess.run(['python3','tools/'+script],cwd=root,stdout=subprocess.PIPE,stderr=subprocess.STDOUT,text=True,timeout=180)
 (root/('reports/'+label+'.log')).write_text(p.stdout)
 return p
try:
 source.write_bytes(original.replace(needle,b"'-crf','51'"))
 assert run(source.name,'compatible-proof-red-encode').returncode==0
 red=run(test.name,'compatible-proof-red')
 assert red.returncode!=0 and 'AssertionError' in red.stdout and 'Compression obscures fine detail' in red.stdout
finally:
 source.write_bytes(original)
assert source.read_bytes()==original and test.read_bytes()==original_test
assert run(source.name,'compatible-proof-green-encode').returncode==0
assert run(test.name,'compatible-proof-green').returncode==0
(root/'reports/compatible-source-proof.json').write_text(json.dumps({'passed':True,'sourceRestoredExactly':True,
 'testsUnchanged':True,'redExit':red.returncode,'greenExit':0,'mutation':'Only source CRF0 changed to51; unchanged luma-error guard must reject excessive compression.'},indent=2)+'\n')
print('Over-compression source regression rejected; exact source restored and unchanged guard passed.')
