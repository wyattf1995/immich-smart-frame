package com.wyattfleming.frameos.web

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.mozilla.geckoview.GeckoSession

class FrameContentPermissionPolicyTest {
    @Test
    fun `allows silent weather loops without enabling unexpected audio`() {
        assertEquals(
            GeckoSession.PermissionDelegate.ContentPermission.VALUE_ALLOW,
            FrameContentPermissionPolicy.responseFor(
                GeckoSession.PermissionDelegate.PERMISSION_AUTOPLAY_INAUDIBLE,
            ),
        )
        assertNull(
            FrameContentPermissionPolicy.responseFor(
                GeckoSession.PermissionDelegate.PERMISSION_AUTOPLAY_AUDIBLE,
            ),
        )
    }

    @Test
    fun `leaves unrelated content permissions to Gecko`() {
        assertNull(
            FrameContentPermissionPolicy.responseFor(
                GeckoSession.PermissionDelegate.PERMISSION_PERSISTENT_STORAGE,
            ),
        )
    }
}
