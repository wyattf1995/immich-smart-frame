#!/usr/bin/env python3
"""Execute source-only controls for the fixed weather request and stale response fence."""
import hashlib,json,subprocess,sys
from pathlib import Path
if not __debug__: raise RuntimeError('Assertions required')
r=Path(sys.argv[1]).resolve();src=r/'examples/frameos/frameos-touchscreen-panel.html';test=r/'scripts/tests/test-frameos-home-forecast-integration.mjs';out=r/'reports/frameos-legacy';out.mkdir(parents=True,exist_ok=True)
original=src.read_bytes();digest=lambda p:hashlib.sha256(p.read_bytes()).hexdigest();tests=digest(test);results={}
mutations=[('fixed_weather_action','service: "get_forecasts",','service: "unexpected_action",','Only the fixed read-only daily weather action is exposed'),('stale_document','if (!currentLive()) throw new Error("Forecast unavailable");','if (false) throw new Error("Forecast unavailable");','Old document must never deliver forecast data')]
for label,before,after,assertion in mutations:
 try:
  s=original.decode();assert s.count(before)==1;src.write_text(s.replace(before,after))
  p=subprocess.run(['node',str(test)],cwd=r,text=True,capture_output=True);(out/(label+'-red.log')).write_text(p.stdout+p.stderr);assert p.returncode!=0 and assertion in p.stderr
  results[label]={'negative_exit':p.returncode,'negative_log':label+'-red.log'}
 finally:src.write_bytes(original)
 p=subprocess.run(['node',str(test)],cwd=r,text=True,capture_output=True);(out/(label+'-green.log')).write_text(p.stdout+p.stderr);assert p.returncode==0
 results[label].update(restored_green_exit=0,restored_log=label+'-green.log')
assert src.read_bytes()==original and digest(test)==tests
(out/'home-forecast-integration-source-proof.json').write_text(json.dumps({'passed':True,'restored_source_sha256':digest(src),'unchanged_test_sha256':tests,'checks':results},indent=2)+'\n')
print('Home forecast integration fixed-action/stale-response source red and restore green passed')
