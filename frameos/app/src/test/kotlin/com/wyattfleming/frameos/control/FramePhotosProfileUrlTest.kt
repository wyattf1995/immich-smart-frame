package com.wyattfleming.frameos.control
import org.junit.Assert.assertEquals
import org.junit.Test
class FramePhotosProfileUrlTest { @Test fun `replaces only profile query`() { assertEquals("http://x/?a=1&curation_profile=family", FramePhotosProfileUrl.withProfile("http://x/?a=1&curation_profile=old", "family")) } }
