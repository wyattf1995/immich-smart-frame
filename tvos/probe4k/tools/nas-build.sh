#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
mkdir -p .nas/tmp .nas/gradle reports
export TMPDIR="$PWD/.nas/tmp"
export GRADLE_USER_HOME="$PWD/.nas/gradle"
export JAVA_TOOL_OPTIONS="-XX:ActiveProcessorCount=2 -Djava.io.tmpdir=$TMPDIR"
export ANDROID_HOME="${ANDROID_SDK_ROOT:?admitted Android SDK required}"
exec > >(tee reports/build.log) 2>&1
java -version
../gradlew -p . --no-daemon --max-workers=2 :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
python3 tools/source-proof.py
python3 - <<'INNER'
from pathlib import Path
import hashlib,json,xml.etree.ElementTree as ET,zipfile
root=Path('.')
apk=root/'app/build/outputs/apk/debug/app-debug-unsigned.apk'
assert apk.is_file()
suites=[ET.parse(p).getroot() for p in (root/'app/build/test-results/testDebugUnitTest').glob('TEST-*.xml')]
assert suites and all(int(s.get('failures','0'))==0 and int(s.get('errors','0'))==0 and int(s.get('skipped','0'))==0 for s in suites)
lint=ET.parse(root/'app/build/reports/lint-results-debug.xml').getroot()
assert not any(i.get('severity') in ('Error','Fatal') for i in lint)
identity={'apkSha256':hashlib.sha256(apk.read_bytes()).hexdigest(),'apkBytes':apk.stat().st_size,'unitTests':sum(int(s.get('tests','0')) for s in suites),'lintWarnings':len(lint.findall('issue')),'sourceFiles':{str(p):hashlib.sha256(p.read_bytes()).hexdigest() for p in sorted((root/'app/src').rglob('*')) if p.is_file()},'deviceStatus':'not tested by NAS'}
(root/'reports/build-identity.json').write_text(json.dumps(identity,indent=2)+'\n')
with zipfile.ZipFile(root/'reports/validation.zip','w',zipfile.ZIP_DEFLATED) as z:
 for base in [root/'reports',root/'app/build/reports',root/'app/build/test-results']:
  for p in sorted(base.rglob('*')):
   if p.is_file() and p.suffix in {'.json','.log','.txt','.xml','.html'}:z.write(p,p.as_posix())
print(json.dumps({k:v for k,v in identity.items() if k!='sourceFiles'}))
INNER
