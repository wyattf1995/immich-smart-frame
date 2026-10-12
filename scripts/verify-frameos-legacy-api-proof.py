#!/usr/bin/env python3
"""Require the actual API23 lint failures, then restore exact source and pass."""
import hashlib
import json
from pathlib import Path
import subprocess
import sys

root = Path(sys.argv[1]).resolve()
frame = root / 'frameos'
out = root / 'reports/frameos-legacy'
out.mkdir(parents=True, exist_ok=True)
oauth = frame / 'app/src/main/kotlin/com/wyattfleming/frameos/auth/HomeAssistantOAuthEndpoint.kt'
style = frame / 'app/src/main/res/values/styles.xml'
style_v27 = frame / 'app/src/main/res/values-v27/styles.xml'
manifest = frame / 'app/src/main/AndroidManifest.xml'
sources = {p: p.read_bytes() for p in (oauth, style, style_v27, manifest)}
tests = {p: hashlib.sha256(p.read_bytes()).hexdigest()
         for p in sorted((frame/'app/src/test').rglob('*')) if p.is_file()}
report = frame / 'app/build/intermediates/lint_intermediate_text_report/debug/lintReportDebug/lint-results-debug.txt'
command = ['./gradlew', '--no-daemon', '--max-workers=2', '-PframeosTarget=legacyArmv7', ':app:lintDebug']

def replace_once(text, before, after):
    assert text.count(before) == 1, before
    return text.replace(before, after)

def lint(label):
    with (out/(label+'.log')).open('w') as stream:
        result = subprocess.run(command, cwd=frame, stdout=stream, stderr=subprocess.STDOUT)
    text = report.read_text()
    (out/(label+'-report.txt')).write_text(text)
    return result.returncode, text

try:
    # The caller has just completed the full legacy tests/lint/release build.
    baseline = report.read_text()
    assert ' Error:' not in baseline and '5 errors' not in baseline
    (out/'api23-initial-report.txt').write_text(baseline)
    text = oauth.read_text()
    text = replace_once(text, 'import kotlin.io.encoding.Base64\nimport kotlin.io.encoding.ExperimentalEncodingApi', 'import java.util.Base64')
    text = replace_once(text, '        @OptIn(ExperimentalEncodingApi::class)\n', '')
    text = replace_once(text, 'Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT).encode(bytes)', 'Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)')
    oauth.write_text(text)
    text = style.read_text()
    text = replace_once(text, '        <item name="android:windowLightStatusBar">', '        <item name="android:windowLightNavigationBar">false</item>\n        <item name="android:windowLightStatusBar">')
    style.write_text(text)
    text = replace_once(manifest.read_text(), '    <uses-permission android:name="android.permission.POST_NOTIFICATIONS" />\n', '')
    manifest.write_text(text)
    code, negative = lint('api23-source-red')
    assert code != 0
    for expected in ('Base64#getUrlEncoder', 'Base64.Encoder#encodeToString',
                     'Base64.Encoder#withoutPadding', 'android:windowLightNavigationBar requires API level 27',
                     '[NotificationPermission]'):
        assert expected in negative, (expected, negative)
    assert '5 errors' in negative, negative
finally:
    for path, content in sources.items():
        path.write_bytes(content)
        assert path.read_bytes() == content

try:
    # Restore only the failed base-style naming from attempt2. Explicit resource
    # inheritance must not invert Android's dotted-name implied parent relation.
    for path in (style, style_v27):
        text = path.read_text()
        assert 'Base.Theme.FrameOS' in text
        path.write_text(text.replace('Base.Theme.FrameOS', 'Theme.FrameOS.Base'))
    code, cycle = lint('style-cycle-source-red')
    assert code != 0 and '2 errors' in cycle and '[ResourceCycle]' in cycle, cycle
    assert 'Theme.FrameOS is the implied parent of Theme.FrameOS.Base' in cycle, cycle
finally:
    for path, content in sources.items():
        path.write_bytes(content)
        assert path.read_bytes() == content

code, restored = lint('api23-restored-green')
assert code == 0, restored
assert tests == {p: hashlib.sha256(p.read_bytes()).hexdigest() for p in tests}
(out/'api23-source-proof.json').write_text(json.dumps({
    'passed': True, 'negative_expected_errors': 5, 'style_cycle_expected_errors': 2, 'restored_lint_exit': code,
    'restored_sources': {str(p.relative_to(root)): hashlib.sha256(p.read_bytes()).hexdigest() for p in sources},
    'unchanged_tests': {str(p.relative_to(root)): digest for p, digest in tests.items()},
    'scope': 'API23 lint guards; encoding vectors separately verify format preservation, not API availability',
}, indent=2)+'\n')
print('API23 five-error and style-cycle two-error source-only red / exact restore green verified', flush=True)
