#!/usr/bin/env bash
# Runs only in an admitted NAS Android job. It builds TouchFrame, never signs or installs it.
set -euo pipefail

readonly root_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
readonly frameos_dir="$root_dir/frameos"
readonly work_dir="$root_dir/.nas-touchframe-android"
readonly report_dir="$root_dir/reports/touchframe-android"
readonly log_file="$report_dir/validation.log"
readonly raw_log="$work_dir/validation.raw.log"
readonly sdk_dir="$work_dir/sdk"
readonly base_sdk_dir="${ANDROID_SDK_ROOT:?admitted Android SDK root is required}"
readonly sdk_report="$report_dir/sdk-bootstrap.json"
readonly manifest="$work_dir/transport-source-manifest.json"
readonly post_manifest="$work_dir/transport-source-manifest-after.json"
readonly source_identity="$report_dir/source-identity.json"
readonly junit_summary="$report_dir/junit-summary.json"
readonly report_zip="$root_dir/reports/touchframe-android-validation.zip"
readonly touch_apk="$frameos_dir/touchframe/build/outputs/apk/release/touchframe-release-unsigned.apk"
readonly touch_mapping="$frameos_dir/touchframe/build/outputs/mapping/release/mapping.txt"

# The Android runtime filesystem is read-only outside this admitted workspace.
# Both Android preference aliases must resolve to the same writable directory.
export ANDROID_SDK_HOME="$work_dir/android-preferences"
export ANDROID_USER_HOME="$ANDROID_SDK_HOME/.android"
mkdir -p "$work_dir/tmp" "$work_dir/gradle-home" "$report_dir" "$ANDROID_USER_HOME"
export TMPDIR="$work_dir/tmp"
export GRADLE_USER_HOME="$work_dir/gradle-home"
export JAVA_TOOL_OPTIONS="${JAVA_TOOL_OPTIONS:-} -Djava.io.tmpdir=$TMPDIR"

[[ -x "$frameos_dir/gradlew" && -f "$frameos_dir/touchframe/build.gradle.kts" && -f "$root_dir/scripts/verify-touchframe-proof.py" ]] || {
  printf 'TouchFrame Gradle project or proof harness is missing\n' >&2; exit 66;
}
command -v python3 >/dev/null && command -v curl >/dev/null && command -v unzip >/dev/null && command -v sha256sum >/dev/null || {
  printf 'admitted Android image lacks python3, curl, unzip, or sha256sum\n' >&2; exit 69;
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

run_step() { local name="$1"; shift; printf '\n== %s ==\n' "$name" | tee -a "$raw_log"; "$@" 2>&1 | tee -a "$raw_log"; }
run_step mutation-target-preflight python3 "$root_dir/scripts/verify-touchframe-mutation-targets.py" "$root_dir"
bootstrap_api37_sdk
export ANDROID_SDK_ROOT="$sdk_dir" ANDROID_HOME="$sdk_dir"

write_source_inventory() {
  local destination="$1"
  python3 - "$root_dir" "$destination" <<'PY'
import hashlib,json,os,sys
from pathlib import Path
root,out=map(Path,sys.argv[1:])
files={}
excluded={'.git','.gradle','.kotlin','.nas-touchframe-android','reports','build'}
for directory, folders, names in os.walk(root):
    folders[:]=[name for name in folders if name not in excluded]
    for name in names:
        path=Path(directory)/name
        if path.is_symlink() or not path.is_file():
            raise SystemExit('non-regular source member')
        files[path.relative_to(root).as_posix()]=hashlib.sha256(path.read_bytes()).hexdigest()
if not files:
    raise SystemExit('empty source manifest')
payload={'schema':1,'files':files}
payload['sha256']=hashlib.sha256(json.dumps(payload,sort_keys=True,separators=(',',':')).encode()).hexdigest()
out.write_text(json.dumps(payload,indent=2,sort_keys=True)+'\n')
PY
}
write_source_inventory "$manifest"
# agent-nas verifies every source.tar member before extraction and binds the
# requested commit in its retained remote receipt. Android containers receive
# neither a Git checkout nor a mounted identity document, so this runner records
# the exact extracted payload without making an unverifiable commit claim.

# Fail fast on release compilation/lint before the source-mutation matrix.
# The final source-inventory comparison still verifies exact byte restoration.
run_step touchframe-lint-release bash -c "cd '$frameos_dir' && ./gradlew --no-daemon --max-workers=2 :touchframe:lintRelease"
# Catch ordinary shared and Touch regressions before the costly mutation matrix.
# Run them again after the focused proof because its targeted Gradle invocations
# replace JUnit XML inventory used by the final retained summary.
run_step touchframe-shared-tests-preflight bash -c "cd '$frameos_dir' && ./gradlew --no-daemon --max-workers=2 :frame-shared:testDebugUnitTest"
run_step touchframe-tests-preflight bash -c "cd '$frameos_dir' && ./gradlew --no-daemon --max-workers=2 :touchframe:testDebugUnitTest"
# A transport carrying proof-scope.json is a fast-lane device build: it runs only the design
# mutations whose source or guarding test changed since the named full-proof baseline and skips
# the other proof suites. The full matrix (no scope file) must still pass before any merge.
readonly proof_scope="$root_dir/proof-scope.json"
lane=full
if [[ -f "$proof_scope" ]]; then
  python3 - "$proof_scope" <<'PY'
import json,re,sys
from pathlib import Path
d=json.loads(Path(sys.argv[1]).read_text())
ok=(d.get('schema')==1 and d.get('mode')=='fast' and re.fullmatch(r'[0-9a-f]{40}',str(d.get('baseline',''))) and isinstance(d.get('changed'),list)
    and all(isinstance(p,str) and p and not p.startswith('/') and '..' not in p.split('/') for p in d['changed']))
if not ok: raise SystemExit('invalid proof-scope.json')
PY
  lane=fast
  printf '\n== fast-lane ==\nFAST LANE: proofs only for files changed since the full-proof baseline; the full proof matrix must pass before merge\n' | tee -a "$raw_log"
  run_step touchframe-design-source-proof env TOUCHFRAME_PROOF_SCOPE="$proof_scope" python3 "$root_dir/scripts/verify-touchframe-design-proof.py" "$root_dir"
else
  run_step touchframe-startup-source-proof python3 "$root_dir/scripts/verify-touch-startup-proof.py" "$root_dir"
  run_step touchframe-climate-source-proof python3 "$root_dir/scripts/verify-touch-climate-proof.py" "$root_dir"
  run_step touchframe-live-camera-source-proof python3 "$root_dir/scripts/verify-touch-live-camera-proof.py" "$root_dir" --run-jvm --report-dir "$root_dir/reports/touch-live-camera"
  run_step touchframe-source-proof python3 "$root_dir/scripts/verify-touchframe-proof.py" "$root_dir"
  run_step weather-header-source-proof python3 "$root_dir/scripts/verify-weather-header-layout-proof.py" "$root_dir"
  run_step touchframe-design-source-proof python3 "$root_dir/scripts/verify-touchframe-design-proof.py" "$root_dir"
fi
run_step touchframe-shared-tests bash -c "cd '$frameos_dir' && ./gradlew --no-daemon --max-workers=2 :frame-shared:testDebugUnitTest"
run_step touchframe-tests bash -c "cd '$frameos_dir' && ./gradlew --no-daemon --max-workers=2 :touchframe:testDebugUnitTest"
run_step touchframe-assemble-release bash -c "cd '$frameos_dir' && ./gradlew --no-daemon --max-workers=2 :touchframe:assembleRelease"

write_source_inventory "$post_manifest"
cmp -- "$manifest" "$post_manifest" || { printf 'extracted transport source inventory changed during TouchFrame validation\n' >&2; exit 73; }
python3 - "$frameos_dir" "$junit_summary" <<'PY'
import json,sys
from pathlib import Path
import xml.etree.ElementTree as ET
frame,out=map(Path,sys.argv[1:])
roots=[frame/'frame-shared/build/test-results/testDebugUnitTest',frame/'touchframe/build/test-results/testDebugUnitTest']
files=[]; totals={'tests':0,'failures':0,'errors':0,'skipped':0}
for base in roots:
    if not base.is_dir():
        raise SystemExit(f'missing JUnit results directory: {base}')
    for xml in sorted(base.rglob('TEST-*.xml')):
        node=ET.parse(xml).getroot()
        item={key:int(node.get(key,'0')) for key in totals}
        item['path']=xml.relative_to(frame).as_posix()
        files.append(item)
        for key in totals:
            totals[key]+=item[key]
if not files or totals['failures'] or totals['errors'] or totals['skipped']:
    raise SystemExit(f'unexpected JUnit summary: files={len(files)} totals={totals}')
out.write_text(json.dumps({'schema':1,'files':files,'totals':totals},indent=2,sort_keys=True)+'\n')
PY

[[ -f "$touch_apk" && -f "$touch_mapping" ]] || { printf 'missing TouchFrame unsigned APK or mapping\n' >&2; exit 72; }
python3 - "$root_dir" "$source_identity" "$manifest" "$post_manifest" "$sdk_report" "$junit_summary" "$touch_apk" "$touch_mapping" <<'PY'
import hashlib,json,sys
from pathlib import Path
root,identity,manifest,post_manifest,sdk,junit,*artifacts=map(Path,sys.argv[1:])
def item(p): return {'sha256':hashlib.sha256(p.read_bytes()).hexdigest(),'bytes':p.stat().st_size,'signing':'unsigned'}
artifact_items={p.relative_to(root).as_posix():item(p) for p in artifacts}
identity.write_text(json.dumps({'schema':1,'transportSourceManifest':json.loads(manifest.read_text()),'postValidationSourceManifest':json.loads(post_manifest.read_text()),'sdkBootstrap':json.loads(sdk.read_text()),'junitSummary':json.loads(junit.read_text()),'artifactSummary':{'count':len(artifact_items),'totalBytes':sum(item['bytes'] for item in artifact_items.values())},'artifacts':artifact_items},indent=2,sort_keys=True)+'\n')
PY
python3 - "$raw_log" "$log_file" <<'PY'
import re,sys
from pathlib import Path
source,dest=map(Path,sys.argv[1:]); text=source.read_text(errors='replace')
dest.write_text(re.sub(r'(?i)\b(authorization|password|token|secret)\b(\s*[:=]\s*)\S+',r'\1\2[REDACTED]',text))
PY
python3 - "$root_dir" "$report_zip" <<'PY'
import sys,zipfile
from pathlib import Path
root,out=map(Path,sys.argv[1:]); include=[root/'reports/frameos-weather-header',root/'reports/touchframe-design',root/'reports/touchframe-android',root/'reports/touch-climate',root/'reports/touch-live-camera',root/'frameos/touchframe/build/reports',root/'frameos/touchframe/build/test-results',root/'frameos/frame-shared/build/reports',root/'frameos/frame-shared/build/test-results']
with zipfile.ZipFile(out,'w',zipfile.ZIP_DEFLATED) as z:
 for base in include:
  if base.exists():
   for p in sorted(base.rglob('*')):
    if p.is_file() and p.suffix in {'.html','.json','.txt','.xml','.log'}: z.write(p,p.relative_to(root))
PY
if [[ "$lane" == fast ]]; then
  printf 'TouchFrame NAS validation passed (fast lane)\n' | tee -a "$log_file"
else
  printf 'TouchFrame NAS validation passed\n' | tee -a "$log_file"
fi
