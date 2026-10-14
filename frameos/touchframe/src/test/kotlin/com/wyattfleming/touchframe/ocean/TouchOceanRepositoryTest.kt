package com.wyattfleming.touchframe.ocean

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.IOException
import java.net.URI
import java.nio.file.Files

class TouchOceanRepositoryTest {
    private val dir: File = Files.createTempDirectory("touch-ocean").toFile().apply { deleteOnExit() }
    private val cache = File(dir, "ocean/conditions.json")

    @Test
    fun `a live answer is shown and saved for the next outage`() {
        val urls = mutableListOf<URI>()
        val repository = TouchOceanRepository(cache, transport = { uri, timeout -> urls += uri; assertEquals(15_000, timeout); TouchOceanFixtures.body() })
        val load = repository.load(TouchOceanRepository.DEFAULT_SPOT, 15_000)
        assertEquals(TouchOceanConnection.LIVE, load.connection)
        assertEquals("Main Beach", load.conditions?.spotName)
        assertEquals(listOf(URI("https://ebbward.com/api/v1/ocean?spot=main-beach")), urls)
        assertTrue(cache.isFile)
        assertFalse(File(cache.parentFile, cache.name + ".tmp").exists())
    }

    @Test
    fun `an outage shows the saved conditions as offline`() {
        TouchOceanRepository(cache, transport = { _, _ -> TouchOceanFixtures.body() }).load("main-beach", 1_000)
        val load = TouchOceanRepository(cache, transport = { _, _ -> throw IOException("offline") }).load("main-beach", 1_000)
        assertEquals(TouchOceanConnection.OFFLINE, load.connection)
        assertEquals("Main Beach", load.conditions?.spotName)
    }

    @Test
    fun `an outage with nothing saved is offline and empty`() {
        val load = TouchOceanRepository(cache, transport = { _, _ -> throw IOException("offline") }).load("main-beach", 1_000)
        assertEquals(TouchOceanConnection.OFFLINE, load.connection)
        assertNull(load.conditions)
    }

    @Test
    fun `an answer for another beach or an unreadable answer never replaces the saved one`() {
        TouchOceanRepository(cache, transport = { _, _ -> TouchOceanFixtures.body() }).load("main-beach", 1_000)
        val saved = cache.readText()
        val other = TouchOceanRepository(cache, transport = { _, _ -> TouchOceanFixtures.body(spotId = "heisler-park") }).load("main-beach", 1_000)
        assertEquals(TouchOceanConnection.OFFLINE, other.connection)
        TouchOceanRepository(cache, transport = { _, _ -> "<html>" }).load("main-beach", 1_000)
        assertEquals(saved, cache.readText())
    }

    @Test(expected = IllegalArgumentException::class)
    fun `beach ids are restricted to catalog slugs`() {
        TouchOceanRepository(cache, transport = { _, _ -> "" }).load("main-beach&x=1", 1_000)
    }
}
