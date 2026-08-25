package com.wyattfleming.frameos.web

import org.mozilla.geckoview.GeckoSession

object FrameContentPermissionPolicy {
    fun responseFor(permission: Int): Int? =
        GeckoSession.PermissionDelegate.ContentPermission.VALUE_ALLOW.takeIf {
            permission == GeckoSession.PermissionDelegate.PERMISSION_AUTOPLAY_INAUDIBLE
        }
}
