#!/usr/bin/env bash
# Runs inside the admitted Android NAS image. It never signs an APK and keeps
# every cache and temporary file under the transported workspace.
set -euo pipefail

readonly root_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
readonly frameos_dir="$root_dir/frameos"
readonly tvos_dir="$root_dir/tvos"
readonly work_dir="$root_dir/.nas-frame-sync-android"
readonly report_dir="$root_dir/reports/frame-sync-android"
readonly log_file="$report_dir/validation.log"
readonly raw_log_file="$work_dir/validation.raw.log"
readonly transport_manifest="$work_dir/transport-source-manifest.json"
readonly identity_file="$report_dir/source-identity.json"
readonly report_zip="$root_dir/reports/frame-sync-android-validation.zip"
readonly parser_source="$frameos_dir/app/src/main/kotlin/com/wyattfleming/frameos/photos/FrameSharedPlaybackMetadata.kt"
readonly parser_test="com.wyattfleming.frameos.photos.FrameSharedPlaybackMetadataTest"

mkdir -p "$work_dir/tmp" "$work_dir/gradle-home" "$report_dir"
export TMPDIR="$work_dir/tmp"
export GRADLE_USER_HOME="$work_dir/gradle-home"
export JAVA_TOOL_OPTIONS="${JAVA_TOOL_OPTIONS:-} -Djava.io.tmpdir=$TMPDIR"

[[ -x "$frameos_dir/gradlew" && -x "$tvos_dir/gradlew" && -f "$parser_source" ]] || {
  printf 'required Android projects or FrameOS shared-playback guard are missing\n' >&2
  exit 66
}
command -v node >/dev/null && command -v python3 >/dev/null && command -v sha256sum >/dev/null || {
  printf 'admitted Android image is missing node, python3, or sha256sum\n' >&2
  exit 69
}

run_step() {
  local name="$1"; shift
  printf '\n== %s ==\n' "$name" | tee -a "$raw_log_file"
  "$@" 2>&1 | tee -a "$raw_log_file"
}

restore_parser_source() {
  if [[ -f "$work_dir/FrameSharedPlaybackMetadata.kt.original" ]]; then
    cmp -s "$work_dir/FrameSharedPlaybackMetadata.kt.original" "$parser_source" || \
      cp "$work_dir/FrameSharedPlaybackMetadata.kt.original" "$parser_source"
  fi
}
trap restore_parser_source EXIT

# agent-nas verified every source.tar member before extracting it, and binds the
# requested commit in the remote receipt. Android jobs intentionally receive no
# .git directory or mounted source identity; record the extracted transport
# payload rather than making an unverifiable Git claim inside this container.
python3 - "$root_dir" "$transport_manifest" <<'PY'
import hashlib, json, sys
from pathlib import Path
import re
root, output = map(Path, sys.argv[1:])
excluded = {'.git', '.nas-frame-sync-android', 'reports'}
files = {}
for path in sorted(root.rglob('*')):
    relative = path.relative_to(root)
    if relative.parts and relative.parts[0] in excluded:
        continue
    if path.is_file():
        files[relative.as_posix()] = hashlib.sha256(path.read_bytes()).hexdigest()
payload = {'schema': 1, 'files': files}
encoded = json.dumps(payload, sort_keys=True, separators=(',', ':')).encode()
payload['sha256'] = hashlib.sha256(encoded).hexdigest()
output.write_text(json.dumps(payload, indent=2, sort_keys=True)+'\n')
PY

run_step frameos-js node --test "$frameos_dir/app/src/test/js/photos-playback.test.js"
run_step frameos-guard-initial bash -c "cd '$frameos_dir' && ./gradlew --no-daemon --max-workers=2 :app:testDebugUnitTest --tests '$parser_test'"

cp "$parser_source" "$work_dir/FrameSharedPlaybackMetadata.kt.original"
before_sha="$(sha256sum "$parser_source" | awk '{print $1}')"
python3 - "$parser_source" <<'PY'
from pathlib import Path
import sys
p = Path(sys.argv[1])
source = p.read_text()
rewritten, replacements = re.subn(
    r'Regex\("\[a-z0-9\]\[a-z0-9_-\]\{0,(?:47|63)\}"\)',
    'Regex(".*")',
    source,
)
if replacements != 1:
    raise SystemExit('shared playback slug guard is not uniquely patchable')
p.write_text(rewritten)
PY
rm -rf "$frameos_dir/app/build/test-results/testDebugUnitTest"
if (cd "$frameos_dir" && ./gradlew --no-daemon --max-workers=2 :app:testDebugUnitTest --tests "$parser_test") >>"$raw_log_file" 2>&1; then
  printf 'negative guard run unexpectedly passed\n' >&2
  exit 70
fi
python3 - "$frameos_dir/app/build/test-results/testDebugUnitTest" "$parser_test" <<'PY'
import sys
import xml.etree.ElementTree as ET
from pathlib import Path
root, expected = Path(sys.argv[1]), sys.argv[2]
assertion_failures = 0
for report in root.glob('TEST-*.xml'):
    suite = ET.parse(report).getroot()
    if suite.attrib.get('name') == expected:
        assertion_failures += int(suite.attrib.get('failures', '0'))
if assertion_failures < 1:
    raise SystemExit('negative run did not record a FrameSharedPlaybackMetadataTest assertion failure')
PY
restore_parser_source
[[ "$(sha256sum "$parser_source" | awk '{print $1}')" == "$before_sha" ]] || { printf 'source-negative proof did not restore exact parser bytes\n' >&2; exit 71; }
printf 'source-negative parser guard failed as expected and restored exact bytes\n' | tee "$report_dir/source-negative-proof.txt"

run_step frameos-guard-green bash -c "cd '$frameos_dir' && ./gradlew --no-daemon --max-workers=2 :app:testDebugUnitTest --tests '$parser_test'"
run_step frameos-unit-lint-release bash -c "cd '$frameos_dir' && ./gradlew --no-daemon --max-workers=2 testDebugUnitTest lintDebug assembleRelease"
run_step tvos-unit bash -c "cd '$tvos_dir' && ./gradlew --no-daemon --max-workers=2 :app:testDebugUnitTest"
run_step tvos-nas-build bash "$tvos_dir/tools/nas-build.sh"

readonly frameos_apk="$frameos_dir/app/build/outputs/apk/release/app-release-unsigned.apk"
readonly frameos_mapping="$frameos_dir/app/build/outputs/mapping/release/mapping.txt"
readonly tvos_apk="$tvos_dir/app/build/outputs/apk/debug/app-debug-unsigned.apk"
[[ -f "$frameos_apk" && -f "$frameos_mapping" && -f "$tvos_apk" ]] || { printf 'expected unsigned APK artifact or FrameOS mapping missing\n' >&2; exit 72; }
python3 - "$root_dir" "$report_dir" "$transport_manifest" "$frameos_apk" "$frameos_mapping" "$tvos_apk" <<'PY'
import hashlib, json, sys
from pathlib import Path
root, report, manifest, *artifacts = map(Path, sys.argv[1:])
def digest(p): return hashlib.sha256(p.read_bytes()).hexdigest()
identity = {
  'transportSourceManifest': json.loads(manifest.read_text()),
  'artifacts': {str(p.relative_to(root)): {'sha256':digest(p), 'bytes':p.stat().st_size, 'signing':'unsigned'} for p in artifacts},
  'sourceGuard': {'file':'frameos/app/src/main/kotlin/com/wyattfleming/frameos/photos/FrameSharedPlaybackMetadata.kt', 'restoredSha256':digest(root/'frameos/app/src/main/kotlin/com/wyattfleming/frameos/photos/FrameSharedPlaybackMetadata.kt')},
}
(report/'source-identity.json').write_text(json.dumps(identity, indent=2)+'\n')
PY
python3 - "$raw_log_file" "$log_file" <<'PY'
import re, sys
source, destination = map(__import__('pathlib').Path, sys.argv[1:])
text = source.read_text(errors='replace')
text = re.sub(r'(?i)\b(authorization|password|token|secret)\b(\s*[:=]\s*)\S+', r'\1\2[REDACTED]', text)
destination.write_text(text)
PY
python3 - "$root_dir" "$report_dir" "$report_zip" <<'PY'
import zipfile, sys
from pathlib import Path
root, report, output = map(Path, sys.argv[1:])
with zipfile.ZipFile(output, 'w', zipfile.ZIP_DEFLATED) as archive:
    for base in (report, root/'frameos/app/build/reports', root/'frameos/app/build/test-results', root/'tvos/validation'):
        if base.exists():
            for p in sorted(base.rglob('*')):
                if p.is_file() and p.suffix in {'.html','.json','.txt','.xml'}:
                    archive.write(p, p.relative_to(root))
PY
printf 'Frame sync Android NAS validation passed\n' | tee -a "$log_file"
