package com.wyattfleming.frameos.navigation

/** Android's launcher/default-Home selection remains an OEM/device policy. */
object FrameStartupPolicy {
    fun initialState(): FrameState = FrameState(mode = FrameMode.PHOTOS)
}
