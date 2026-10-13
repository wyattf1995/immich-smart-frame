package com.wyattfleming.touchframe.control

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TouchRemoteSettingsPolicyTest {
    @Test fun `first valid settings revision applies`() {
        assertTrue(TouchRemoteSettingsPolicy.shouldApply(-1L, 0L))
        assertFalse(TouchRemoteSettingsPolicy.shouldApply(-1L, -1L))
    }

    @Test fun `repeated and stale settings cannot revert current preferences`() {
        assertFalse(TouchRemoteSettingsPolicy.shouldApply(8L, 8L))
        assertFalse(TouchRemoteSettingsPolicy.shouldApply(8L, 7L))
        assertTrue(TouchRemoteSettingsPolicy.shouldApply(8L, 9L))
    }

    @Test fun `revision comparison is safe at Long limit`() {
        assertTrue(TouchRemoteSettingsPolicy.shouldApply(Long.MAX_VALUE - 1, Long.MAX_VALUE))
        assertFalse(TouchRemoteSettingsPolicy.shouldApply(Long.MAX_VALUE, 0L))
    }
}
