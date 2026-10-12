#!/usr/bin/env python3
"""Source-only guard for API23-scoped legacy TLS fallback."""
from __future__ import annotations
import hashlib
from pathlib import Path

root = Path(__file__).resolve().parents[1]
source_path = root / "frameos/app/src/main/kotlin/com/wyattfleming/frameos/weather/LegacyIsrgRootTls.kt"
test_path = root / "frameos/app/src/test/kotlin/com/wyattfleming/frameos/weather/LegacyIsrgRootTlsTest.kt"
source = source_path.read_text()
test = test_path.read_text()

GATE = "sdkInt == Build.VERSION_CODES.M"
FALLBACK = "FallbackTrustManager(platform, supplemental)"

def check(candidate: str) -> None:
    assert GATE in candidate, "supplemental trust must remain API23-only"
    assert FALLBACK in candidate, "legacy TLS must retain platform-first fallback trust"
    assert "setDefaultSSLSocketFactory" not in candidate
    assert "setDefaultHostnameVerifier" not in candidate
    assert "platform.checkClientTrusted(chain, authType)" in candidate

check(source)
failures = 0
for label, mutation in {
    "removed-api23-gate": source.replace(GATE, "false", 1),
    "expanded-api23-gate": source.replace(GATE, "sdkInt < Build.VERSION_CODES.O", 1),
    "removed-fallback": source.replace(FALLBACK, "platform", 1),
}.items():
    try:
        check(mutation)
    except AssertionError:
        failures += 1
    else:
        raise AssertionError(f"negative proof unexpectedly passed: {label}")
assert failures == 3
assert "listOf(24, 25, 26)" in test
assert "hostnameVerifierAssignments" in test
print("legacy TLS source proof passed")
print("source_sha256=" + hashlib.sha256(source.encode()).hexdigest())
print("test_sha256=" + hashlib.sha256(test.encode()).hexdigest())
print("negative_assertion_failures=3")
