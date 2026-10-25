package com.wyattfleming.touchframe.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TouchStartupPolicyTest {
    @Test fun `system Home action opens Photos`() {
        assertEquals(
            TouchDestination.PHOTOS,
            TouchStartupPolicy.destinationFor(
                "android.intent.action.MAIN",
                setOf("android.intent.category.HOME", "android.intent.category.DEFAULT"),
            ),
        )
    }

    @Test fun `ordinary launcher action preserves the current destination`() {
        assertNull(
            TouchStartupPolicy.destinationFor(
                "android.intent.action.MAIN",
                setOf("android.intent.category.LAUNCHER"),
            ),
        )
    }

    @Test fun `Home category without main action is not a startup request`() {
        assertNull(
            TouchStartupPolicy.destinationFor(
                "android.intent.action.VIEW",
                setOf("android.intent.category.HOME"),
            ),
        )
    }
}
