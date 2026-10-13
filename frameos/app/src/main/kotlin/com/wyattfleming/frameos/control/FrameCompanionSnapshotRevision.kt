package com.wyattfleming.frameos.control

/** Opaque server revision token. It never contains configuration or credentials. */
object FrameCompanionSnapshotRevision {
    private val token = Regex("^[a-f0-9]{64}$")

    fun valid(value: String?): Boolean = value != null && token.matches(value)

    fun forRequest(value: String?): String? = value?.takeIf(::valid)

    /** An abbreviated response is safe only for the revision this request actually sent. */
    fun acceptsUnchanged(sent: String?, received: String?): Boolean =
        valid(sent) && sent == received && valid(received)
}
