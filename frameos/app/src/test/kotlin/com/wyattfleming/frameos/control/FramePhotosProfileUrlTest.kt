package com.wyattfleming.frameos.control
import org.junit.Assert.assertEquals
import org.junit.Test
class FramePhotosProfileUrlTest { @Test fun `replaces only profile query`() { assertEquals("http://x/?a=1&curation_profile=family", FramePhotosProfileUrl.withProfile("http://x/?a=1&curation_profile=old", "family")) }
 @Test fun `preserves encoded path and query`() { assertEquals("https://x/a%2Fb?token=a%2Fb&curation_profile=family", FramePhotosProfileUrl.withProfile("https://x/a%2Fb?token=a%2Fb&curation_profile=old", "family")) } }
