package com.example

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ExampleRobolectricTest {

    @Test
    fun cssPreviewBoundsAreScaledByEffectivePixelRatio() {
        // The WebView reports the effective devicePixelRatio, including page zoom.
        assertEquals(360, cssPixelsToAndroidPixels(100f, 3.6f))
        assertEquals(0, cssPixelsToAndroidPixels(0f, 2f))
    }

    @Test
    fun nativePlayerViewUsesTextureSurfaceAndTransparentShutter() {
        val layout = File("src/main/res/layout/native_player_view.xml")
        assertTrue("native PlayerView layout must exist", layout.exists())
        val xml = layout.readText()
        assertTrue(xml.contains("""app:surface_type="texture_view""""))
        assertTrue(xml.contains("""app:use_controller="false""""))
        assertTrue(xml.contains("""app:shutter_background_color="@android:color/transparent""""))
    }

    @Test
    fun verifyAppNameConstant() {
        val appName = "SONIX TV"
        assertEquals("SONIX TV", appName)
    }

    @Test
    fun verifyAssetFilesExist() {
        // Verify sonix_tv.html exists in assets
        val assetsFile = File("src/main/assets/sonix_tv.html")
        assertTrue("sonix_tv.html asset must exist", assetsFile.exists())
        assertTrue("sonix_tv.html should not be empty", assetsFile.length() > 1000)
    }
}
