#!/usr/bin/env python3
"""Prove the shared API23/ARMv7 desugaring lint exclusion stays line-scoped."""
from __future__ import annotations
import hashlib
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / 'frameos/frame-shared/build.gradle.kts'
DEPENDENCY = '    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs_nio:2.0.1")'
SUPPRESSION = '    //noinspection GradleDependency\n' + DEPENDENCY
RATIONALE = (
    '    // Keep the shared API23/ARMv7 Java 11 NIO compatibility pin until a separately\n'
    '    // validated dependency migration; lint must not silently force an upgrade.\n'
)

def check(text: str) -> None:
    assert text.count(DEPENDENCY) == 1, 'the reviewed desugaring 2.0.1 pin must remain singular'
    assert text.count(SUPPRESSION) == 1, 'only the pinned dependency may suppress GradleDependency'
    assert text.count(RATIONALE) == 1, 'the API23/ARMv7 migration rationale must remain adjacent'
    assert 'disable += "GradleDependency"' not in text, 'project-wide GradleDependency suppression is forbidden'
    assert 'disable("GradleDependency")' not in text, 'project-wide GradleDependency suppression is forbidden'

original = SOURCE.read_bytes()
original_hash = hashlib.sha256(original).hexdigest()
try:
    check(original.decode('utf-8'))
    changed = original.decode('utf-8').replace('//noinspection GradleDependency', '//noinspection Other', 1)
    assert changed != original.decode('utf-8')
    SOURCE.write_text(changed, encoding='utf-8')
    try:
        check(SOURCE.read_text(encoding='utf-8'))
    except AssertionError as exc:
        assert 'only the pinned dependency' in str(exc)
    else:
        raise AssertionError('removing the line-scoped exclusion must fail the guard')
finally:
    SOURCE.write_bytes(original)
assert SOURCE.read_bytes() == original
print('frame-shared desugaring pin guard: red mutation and exact restore green verified')
