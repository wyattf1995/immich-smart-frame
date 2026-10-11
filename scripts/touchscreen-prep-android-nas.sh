#!/usr/bin/env bash
# Compiles preparation artifacts; never installs or signs them or touches a frame.
set -euo pipefail
root="$(cd "$(dirname "$0")/.." && pwd)"
base_sdk="${ANDROID_SDK_ROOT:?admitted Android SDK is required}"
report="$root/reports/touchscreen-prep"
mkdir -p "$report"
export GRADLE_OPTS='-Dorg.gradle.workers.max=2 -Dorg.gradle.parallel=false -Dkotlin.daemon.jvm.options=-Xmx2g'

# Reuse the already-verified SDK bootstrap, full FrameOS tests/lint/build, and
# companion TV regression checks. This script carries the reviewed SDK hashes.
bash "$root/scripts/frame-sync-android-nas.sh"
export ANDROID_SDK_ROOT="$root/.nas-frame-sync-android/sdk"
export ANDROID_HOME="$ANDROID_SDK_ROOT"
export GRADLE_USER_HOME="$root/.nas-frame-sync-android/gradle-home"
export TMPDIR="$root/.nas-frame-sync-android/tmp"
export JAVA_TOOL_OPTIONS="-Djava.io.tmpdir=$TMPDIR"
python3 "$root/scripts/verify-touchscreen-proof.py" "$root"

# The standalone API23 probe deliberately uses the image's unchanged API36 SDK.
export ANDROID_SDK_ROOT="$base_sdk"
export ANDROID_HOME="$base_sdk"
"$root/frameos/gradlew" -p "$root/frameos/touchprobe" --no-daemon --max-workers=2 \
  :app:lintRelease :app:assembleRelease 2>&1 | tee "$report/probe-build.log"

python3 - "$root" "$base_sdk" <<'PY'
import hashlib, json, pathlib, subprocess, sys, zipfile
root=pathlib.Path(sys.argv[1]); sdk=pathlib.Path(sys.argv[2]); out=root/'reports/touchscreen-prep'
artifacts={
    'frameos':root/'frameos/app/build/outputs/apk/release/app-release-unsigned.apk',
    'mapping':root/'frameos/app/build/outputs/mapping/release/mapping.txt',
    'probe':root/'frameos/touchprobe/app/build/outputs/apk/release/app-release-unsigned.apk',
}
identity={}
for name,path in artifacts.items():
    assert path.is_file(), path
    identity[name]={'path':str(path.relative_to(root)),'bytes':path.stat().st_size,'sha256':hashlib.sha256(path.read_bytes()).hexdigest()}
    if path.suffix=='.apk':
        badging=subprocess.check_output([str(sdk/'build-tools/36.0.0/aapt'),'dump','badging',str(path)],text=True)
        (out/(name+'-badging.txt')).write_text(badging)
        assert "sdkVersion:'23'" in badging if name=='probe' else "sdkVersion:'29'" in badging
        if name=='probe':
            assert "package: name='com.wyattfleming.frameos.touchprobe'" in badging
            assert 'uses-permission:' not in badging, badging
            with zipfile.ZipFile(path) as apk:
                assert not any(p.startswith('lib/') for p in apk.namelist())
        identity[name]['signing']='unsigned'
(out/'artifacts.json').write_text(json.dumps(identity,indent=2)+'\n')
with zipfile.ZipFile(root/'reports/touchscreen-prep-validation.zip','w',zipfile.ZIP_DEFLATED) as archive:
    for folder in [out,root/'frameos/app/build/test-results',root/'frameos/touchprobe/app/build/reports']:
        for path in sorted(folder.rglob('*')):
            if path.is_file(): archive.write(path,path.relative_to(root))
print(json.dumps(identity,indent=2))
PY
printf 'Touchscreen preparation artifacts built; physical-device compatibility remains unverified.\n'
