package com.wyattfleming.frameos.security

class FrameExternalControlPolicy(private val debuggable: Boolean) {
    fun acceptsProvisioning(alreadyConfigured: Boolean): Boolean = debuggable && !alreadyConfigured

    fun acceptsCommands(): Boolean = debuggable
}
