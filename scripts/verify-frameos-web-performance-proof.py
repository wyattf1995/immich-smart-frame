#!/usr/bin/env python3
import hashlib,json,subprocess,sys
from pathlib import Path
r=Path(sys.argv[1]).resolve(); a=r/'examples/frameos/frameos-touchscreen-panel.html';b=r/'frameos/app/src/main/assets/frame-photo-bridge/photos.js';t=r/'scripts/tests/test-frameos-web-performance-contract.mjs';o=r/'reports';o.mkdir(exist_ok=True); aa=a.read_bytes();bb=b.read_bytes();th=hashlib.sha256(t.read_bytes()).hexdigest();needle=b'homeProjectionCache?.states === live.states';assert aa.count(needle)==1
try:
 a.write_bytes(aa.replace(needle,b'false'))
 p=subprocess.run(['node',str(t)],cwd=r,text=True,capture_output=True);(o/'frameos-web-performance-red.log').write_text(p.stdout+p.stderr);assert p.returncode!=0
finally:a.write_bytes(aa);assert b.read_bytes()==bb
p=subprocess.run(['node',str(t)],cwd=r,text=True,capture_output=True);(o/'frameos-web-performance-green.log').write_text(p.stdout+p.stderr);assert p.returncode==0
(o/'frameos-web-performance-proof.json').write_text(json.dumps({'passed':True,'source_sha256':hashlib.sha256(a.read_bytes()).hexdigest(),'bridge_sha256':hashlib.sha256(b.read_bytes()).hexdigest(),'test_sha256':th},indent=2)+'\n')
