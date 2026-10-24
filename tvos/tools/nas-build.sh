#!/usr/bin/env bash
# Frame TV NAS validation. Frame TV builds inside the FrameOS Gradle project as :frametv (it shows :frame-ui's shared
# Weather and Ocean screens), so the transport carries frameos/, tvos/ and LICENSE. Runs only in an admitted Android
# worker: API 37 is bootstrapped into a disposable workspace SDK from pinned, checksummed archives, exactly as the
# FrameOS frame builds do; the admitted SDK stays read-only.
set -euo pipefail
cd "$(dirname "$0")/.."
readonly tvos_dir="$(pwd)"
readonly frameos_dir="$(cd ../frameos && pwd)"
readonly work_dir="$tvos_dir/.nas-frametv"
readonly sdk_dir="$work_dir/sdk"
readonly base_sdk_dir="${ANDROID_SDK_ROOT:?admitted Android SDK root is required}"
readonly sdk_report="$tvos_dir/validation/sdk-bootstrap.json"
mkdir -p validation "$work_dir/tmp" "$work_dir/gradle-home"
exec > >(tee validation/build.log) 2>&1
trap 'if [ -f validation/source-proof.log ]; then tail -n 90 validation/source-proof.log; fi' ERR

# The Android runtime filesystem is read-only outside this admitted workspace.
export ANDROID_SDK_HOME="$work_dir/android-preferences"
export ANDROID_USER_HOME="$ANDROID_SDK_HOME/.android"
mkdir -p "$ANDROID_USER_HOME"
export TMPDIR="$work_dir/tmp"
export GRADLE_USER_HOME="$work_dir/gradle-home"
export JAVA_TOOL_OPTIONS="${JAVA_TOOL_OPTIONS:-} -Djava.io.tmpdir=$TMPDIR"

[[ -x "$frameos_dir/gradlew" && -f "$frameos_dir/frame-ui/build.gradle.kts" && -f "$tvos_dir/app/build.gradle.kts" ]] || {
  printf 'FrameOS Gradle project or Frame TV module is missing from the transport\n' >&2; exit 66;
}

bootstrap_api37_sdk() {
  local platform_url='https://dl.google.com/android/repository/platform-37.0_r02.zip'
  local platform_sha='840b23e827f96e64aea4c89a1194aac3dc5f6bad37edb231c5c795d890330e8d'
  local tools_url='https://dl.google.com/android/repository/build-tools_r37_linux.zip'
  local tools_sha='01af179347cbcd9c208b7f8171f7b21f6dd1d2f85bcd15e88caa51d5d7b86060'
  [[ -r "$base_sdk_dir/licenses/android-sdk-license" && -d "$base_sdk_dir/platforms/android-36" && -d "$base_sdk_dir/build-tools/36.0.0" ]] || {
    printf 'admitted Android SDK lacks reviewed API36 bootstrap inputs\n' >&2; exit 69;
  }
  mkdir -p "$sdk_dir/platforms" "$sdk_dir/build-tools"
  cp -a "$base_sdk_dir/licenses" "$sdk_dir/licenses"
  cp -a "$base_sdk_dir/platforms/android-36" "$sdk_dir/platforms/android-36"
  cp -a "$base_sdk_dir/build-tools/36.0.0" "$sdk_dir/build-tools/36.0.0"
  cp -a "$base_sdk_dir/platform-tools" "$sdk_dir/platform-tools"
  curl --fail --location --silent --show-error --output "$work_dir/tmp/platform.zip" "$platform_url"
  printf '%s  %s\n' "$platform_sha" "$work_dir/tmp/platform.zip" | sha256sum --check --strict
  curl --fail --location --silent --show-error --output "$work_dir/tmp/tools.zip" "$tools_url"
  printf '%s  %s\n' "$tools_sha" "$work_dir/tmp/tools.zip" | sha256sum --check --strict
  unzip -q "$work_dir/tmp/platform.zip" -d "$sdk_dir/platforms"
  unzip -q "$work_dir/tmp/tools.zip" -d "$sdk_dir/build-tools"
  mv "$sdk_dir/build-tools/android-37.0" "$sdk_dir/build-tools/37.0.0"
  [[ -f "$sdk_dir/platforms/android-37.0/android.jar" && -f "$sdk_dir/build-tools/37.0.0/aapt2" ]] || {
    printf 'pinned API37 bootstrap layout is incomplete\n' >&2; exit 69;
  }
  python3 - "$sdk_dir" "$sdk_report" "$platform_url" "$platform_sha" "$tools_url" "$tools_sha" <<'PY'
import hashlib,json,sys
from pathlib import Path
sdk,out=map(Path,sys.argv[1:3]); urls=sys.argv[3:]
def h(p): return hashlib.sha256(p.read_bytes()).hexdigest()
def props(p): return dict(x.split('=',1) for x in p.read_text().splitlines() if '=' in x)
platform=props(sdk/'platforms/android-37.0/source.properties'); tools=props(sdk/'build-tools/37.0.0/source.properties')
if platform.get('AndroidVersion.ApiLevel')!='37.0' or platform.get('Pkg.Revision')!='2': raise SystemExit('wrong API37 platform')
if tools.get('Pkg.Revision')!='37.0.0': raise SystemExit('wrong build-tools')
out.write_text(json.dumps({'schema':1,'archives':{'platform':{'url':urls[0],'sha256':urls[1]},'buildTools':{'url':urls[2],'sha256':urls[3]}},'androidJar':h(sdk/'platforms/android-37.0/android.jar'),'aapt2':h(sdk/'build-tools/37.0.0/aapt2')},indent=2,sort_keys=True)+'\n')
PY
}

printf 'Frame TV NAS validation (FrameOS project, :frametv)\n'
java -version
bootstrap_api37_sdk
export ANDROID_SDK_ROOT="$sdk_dir" ANDROID_HOME="$sdk_dir"

python3 tools/verify-source-proof.py
python3 tools/verify-grid-proof.py
python3 tools/verify-weather-proof.py
python3 tools/verify-weather-source-budget-proof.py .
python3 tools/verify-weather-source-json-runtime-proof.py .
python3 - <<'PY'
import json
from pathlib import Path
for name in ('source-proof.json', 'grid-proof.json', 'weather-proof.json', 'weather-source-budget-proof.json', 'weather-source-json-runtime-proof.json'):
    data = json.loads((Path('validation') / name).read_text())
    if data.get('passed') is not True or data.get('source_restored_exactly') is not True:
        raise SystemExit('proof receipt did not pass and restore source: ' + name)
print('all source-proof receipts passed and restored exact source')
PY
if command -v node >/dev/null; then node --test tools/ha-weather-snapshot.test.cjs; else printf 'node is not in this runtime; run tools/ha-weather-snapshot.test.cjs on the Mac\n'; fi
# Source-negative guards leave focused XML behind, so rerun every suite for complete retained results. The shared
# screens' own tests live in :touchframe; Frame TV's change to :frame-ui must keep them green too.
"$frameos_dir/gradlew" -p "$frameos_dir" --no-daemon --max-workers=2 --rerun-tasks \
  :frametv:testDebugUnitTest :frametv:assembleDebug :frametv:lintDebug :frame-ui:lintDebug :touchframe:testDebugUnitTest
python3 - <<'PY'
from pathlib import Path
import hashlib,json,shutil,zipfile
import xml.etree.ElementTree as et
transport=Path('..').resolve()
root=Path('.').resolve()
frameos=transport/'frameos'
name=lambda p: p.resolve().relative_to(transport).as_posix()
apks=sorted((root/'app/build/outputs/apk/debug').glob('*.apk'))
if len(apks)!=1: raise SystemExit(f'expected one debug APK, found {apks}')
apk=root/'validation/frame-tv-unsigned.apk'
shutil.copyfile(apks[0],apk)
counts={}
for module,results in (('frametv',root/'app/build/test-results/testDebugUnitTest'),('touchframe',frameos/'touchframe/build/test-results/testDebugUnitTest')):
 total={'tests':0,'failures':0,'errors':0,'skipped':0}
 for report in results.glob('TEST-*.xml'):
  attributes=et.parse(report).getroot().attrib
  for key in total: total[key]+=int(attributes.get(key,'0'))
 if total['tests']==0 or total['failures'] or total['errors']: raise SystemExit(f'{module} tests not green: {total}')
 counts[module]=total
result={
 'apkSha256':hashlib.sha256(apk.read_bytes()).hexdigest(),
 'apkBytes':apk.stat().st_size,
 'builtApk':name(apks[0]),
 'expectedApplicationId':'com.wyattfleming.frametv',
 'expectedMinSdk':28,
 'signing':'unsigned; local signing required',
 'testCounts':counts,
 'sourceFiles':{}
}
for base in (root/'app/src',frameos/'frame-ui/src',root/'app/build/generated/frameos'):
 for p in sorted(base.rglob('*')):
  if p.is_file():result['sourceFiles'][name(p)]=hashlib.sha256(p.read_bytes()).hexdigest()
(root/'validation/build-identity.json').write_text(json.dumps(result,indent=2)+'\n')
with zipfile.ZipFile(root/'validation/reports.zip','w',zipfile.ZIP_DEFLATED) as z:
 for base in (root/'app/build/test-results',root/'app/build/reports',root/'validation',frameos/'touchframe/build/test-results',frameos/'frame-ui/build/reports'):
  for p in sorted(base.rglob('*')):
   if p.is_file() and p.suffix in {'.xml','.html','.txt','.json','.log'} and p.name!='build.log':z.write(p,name(p))
print(json.dumps({k:v for k,v in result.items() if k!='sourceFiles'}))
PY
