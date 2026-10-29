package dev.gamspi.videogallery

import org.junit.Assert.assertEquals
import org.junit.Test

class SmokeTest {
    @Test
    fun buildConfigPackage() {
        assertEquals("dev.gamspi.videogallery", BuildConfig.APPLICATION_ID)
    }
}
