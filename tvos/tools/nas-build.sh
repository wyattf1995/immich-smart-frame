#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
mkdir -p validation
exec > >(tee validation/build.log) 2>&1
trap 'if [ -f validation/source-proof.log ]; then tail -n 90 validation/source-proof.log; fi' ERR
printf 'Frame TV isolated NAS validation\n'
java -version
python3 tools/verify-source-proof.py
python3 tools/verify-grid-proof.py
python3 tools/verify-weather-proof.py
./gradlew --no-daemon --max-workers=2 :app:assembleDebug :app:lintDebug
python3 - <<'PY'
from pathlib import Path
import hashlib,json,os,zipfile
root=Path('.')
apk=root/'app/build/outputs/apk/debug/app-debug-unsigned.apk'
result={
 'apkSha256':hashlib.sha256(apk.read_bytes()).hexdigest(),
 'apkBytes':apk.stat().st_size,
 'expectedApplicationId':'com.wyattfleming.frametv',
 'expectedMinSdk':28,
 'signing':'unsigned; local signing required',
 'sourceFiles':{}
}
for p in sorted((root/'app/src').rglob('*')):
 if p.is_file():result['sourceFiles'][str(p)]=hashlib.sha256(p.read_bytes()).hexdigest()
Path('validation/build-identity.json').write_text(json.dumps(result,indent=2)+'\n')
with zipfile.ZipFile('validation/reports.zip','w',zipfile.ZIP_DEFLATED) as z:
 for base in [Path('app/build/test-results'),Path('app/build/reports'),Path('validation')]:
  for p in sorted(base.rglob('*')):
   if p.is_file() and p.suffix in {'.xml','.html','.txt','.json','.log'} and p.name!='build.log':z.write(p,p.as_posix())
print(json.dumps({k:v for k,v in result.items() if k!='sourceFiles'}))
PY
