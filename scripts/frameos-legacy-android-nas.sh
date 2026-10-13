#!/usr/bin/env bash
# Validates the opt-in PF1007 build. No signing, deployment or device access.
set -euo pipefail
root="$(cd "$(dirname "$0")/.." && pwd)"
export ORG_GRADLE_PROJECT_frameosTarget=legacyArmv7
export GRADLE_OPTS='-Dorg.gradle.workers.max=2 -Dorg.gradle.parallel=false -Dkotlin.daemon.jvm.options=-Xmx2g'

mkdir -p "$root/reports/frameos-legacy"
node "$root/scripts/tests/test-frameos-touchscreen-panel.mjs" \
  | tee "$root/reports/frameos-legacy/touchscreen-panel-tests.log"

node "$root/scripts/tests/test-frameos-custom-home.mjs" \
  | tee "$root/reports/frameos-legacy/custom-home-tests.log"
node "$root/scripts/tests/test-frameos-custom-home-integration.mjs" \
  | tee "$root/reports/frameos-legacy/custom-home-integration-tests.log"

node "$root/scripts/tests/test-frameos-home-forecast.mjs" | tee "$root/reports/frameos-legacy/home-forecast-tests.log"
node "$root/scripts/tests/test-frameos-home-forecast-integration.mjs" | tee "$root/reports/frameos-legacy/home-forecast-integration-tests.log"
python3 "$root/scripts/verify-frameos-home-forecast-proof.py" "$root"
python3 "$root/scripts/verify-frameos-home-forecast-integration-proof.py" "$root"
python3 "$root/scripts/verify-frameos-custom-home-content-proof.py" "$root"
node "$root/scripts/tests/test-frameos-home-late-bootstrap.mjs" \
  | tee "$root/reports/frameos-legacy/home-late-bootstrap-tests.log"
python3 "$root/scripts/verify-frameos-home-late-bootstrap-proof.py" "$root"
python3 "$root/scripts/verify-frameos-touchscreen-spa-proof.py" "$root"

node "$root/scripts/tests/test-frameos-art-gallery.mjs" | tee "$root/reports/frameos-legacy/art-gallery-tests.log"
python3 "$root/scripts/verify-frameos-art-gallery-proof.py" "$root" | tee "$root/reports/frameos-legacy/art-gallery-proof.log"
python3 "$root/scripts/verify-frameos-art-rotation-proof.py" "$root"
python3 "$root/scripts/verify-frameos-art-fullscreen-proof.py" "$root" | tee "$root/reports/frameos-legacy/art-fullscreen-proof.log"
python3 "$root/scripts/verify-frameos-art-mode-source-proof.py" "$root" | tee "$root/reports/frameos-legacy/art-source-proof.log"

# Preserve the existing pinned SDK bootstrap and the complete regression suite.
bash "$root/scripts/frame-sync-android-nas.sh"
export ANDROID_SDK_ROOT="$root/.nas-frame-sync-android/sdk"
export ANDROID_HOME="$ANDROID_SDK_ROOT"
export GRADLE_USER_HOME="$root/.nas-frame-sync-android/gradle-home"
export TMPDIR="$root/.nas-frame-sync-android/tmp"
export JAVA_TOOL_OPTIONS="-Djava.io.tmpdir=$TMPDIR"
python3 "$root/scripts/verify-frame-photo-bridge-packaging-proof.py" "$root"
python3 "$root/scripts/verify-frameos-art-routing-proof.py" "$root"
python3 "$root/scripts/verify-frameos-art-idle-proof.py" "$root"
python3 "$root/scripts/verify-frameos-page-load-budget-proof.py" "$root"
python3 "$root/scripts/verify-frameos-legacy-tls-proof.py" "$root"
python3 "$root/scripts/verify-frameos-photo-readiness-proof.py" "$root"
python3 "$root/scripts/verify-frameos-touchscreen-photos-default-proof.py" "$root"
python3 "$root/scripts/verify-touchscreen-proof.py" "$root"
python3 "$root/scripts/verify-frameos-gesture-reliability-proof.py" "$root"
python3 "$root/scripts/verify-frameos-bridge-callback-proof.py" "$root"
python3 "$root/scripts/verify-frameos-touchscreen-route-proof.py" "$root"
python3 "$root/scripts/verify-frameos-legacy-api-proof.py" "$root"
python3 "$root/scripts/verify-frameos-weather-touch-proof.py" "$root"
python3 "$root/scripts/verify-frameos-touch-dashboard-proof.py" "$root"
python3 "$root/scripts/verify-frameos-exclusive-attachment-proof.py" "$root"
python3 "$root/scripts/verify-frameos-touch-dashboard-integration-proof.py" "$root"
python3 "$root/scripts/verify-frameos-fullscreen-navigation-proof.py" "$root"
python3 "$root/scripts/verify-frameos-touchscreen-cache-proof.py" "$root"
python3 "$root/scripts/verify-frameos-embedded-oauth-proof.py" "$root"
python3 "$root/scripts/verify-frameos-oauth-https-redirect-proof.py" "$root"
python3 "$root/scripts/verify-frameos-embedded-oauth-integration-proof.py" "$root"
python3 "$root/scripts/verify-frameos-companion-settings-envelope-proof.py" "$root"
python3 "$root/scripts/verify-frameos-companion-reserve-status-proof.py" "$root"
python3 "$root/scripts/verify-frameos-system-settings-proof.py" "$root"
python3 "$root/scripts/verify-frameos-settings-return-overlay-proof.py" "$root"
mkdir -p "$root/reports/frameos-legacy"
"$root/frameos/gradlew" -p "$root/frameos" --no-daemon --max-workers=2 \
  :app:lintRelease :app:dependencies --configuration releaseRuntimeClasspath \
  2>&1 | tee "$root/reports/frameos-legacy/dependencies-and-release-lint.log"

python3 - "$root" <<'PY'
import hashlib, json, pathlib, subprocess, sys, zipfile
root = pathlib.Path(sys.argv[1])
out = root / 'reports/frameos-legacy'
apk = root / 'frameos/app/build/outputs/apk/release/app-release-unsigned.apk'
mapping = root / 'frameos/app/build/outputs/mapping/release/mapping.txt'
aapt = root / '.nas-frame-sync-android/sdk/build-tools/36.0.0/aapt'
badging = subprocess.check_output([str(aapt), 'dump', 'badging', str(apk)], text=True)
(out/'apk-badging.txt').write_text(badging)
assert "package: name='com.wyattfleming.frameos'" in badging
assert "sdkVersion:'23'" in badging, badging
assert "native-code: 'armeabi-v7a'" in badging, badging
with zipfile.ZipFile(apk) as z:
    libs = [n for n in z.namelist() if n.startswith('lib/') and n.endswith('.so')]
    assert libs and all(n.startswith('lib/armeabi-v7a/') for n in libs), libs
    assert 'lib/armeabi-v7a/libxul.so' in libs
    assert not any(n.startswith('META-INF/') and n.endswith(('.RSA','.DSA','.EC')) for n in z.namelist())
    assert z.testzip() is None
signer = aapt.with_name('apksigner')
signature = subprocess.run([str(signer), 'verify', '--verbose', '--min-sdk-version', '23', str(apk)], capture_output=True, text=True)
(out/'unsigned-apk-verification.txt').write_text(signature.stdout + signature.stderr)
assert signature.returncode == 1 and 'DOES NOT VERIFY' in signature.stderr + signature.stdout, signature
dependencies = (out/'dependencies-and-release-lint.log').read_text()
assert 'geckoview-armeabi-v7a:144.0.20251027123126' in dependencies
assert 'geckoview-arm64-v8a' not in dependencies
identity = {
    'target': 'legacyArmv7', 'minSdk': 23, 'abi': 'armeabi-v7a',
    'geckoview': '144.0.20251027123126', 'device_compatibility': 'not_yet_tested',
    'artifacts': {str(p.relative_to(root)): {
        'bytes': p.stat().st_size, 'sha256': hashlib.sha256(p.read_bytes()).hexdigest()
    } for p in [apk, mapping]}, 'native_libraries': libs,
}
(out/'artifact-identity.json').write_text(json.dumps(identity, indent=2)+'\n')
with zipfile.ZipFile(root/'reports/frameos-legacy-validation.zip', 'w', zipfile.ZIP_DEFLATED) as archive:
    for folder in [out, root/'reports/touchscreen-prep', root/'frameos/app/build/reports']:
        for p in sorted(folder.rglob('*')):
            if p.is_file(): archive.write(p, p.relative_to(root))
print(json.dumps(identity, indent=2))
PY
