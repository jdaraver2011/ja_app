package com.example.ja

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Local library transactions and real Compose navigation. No account credentials or remote playback. */
@RunWith(AndroidJUnit4::class)
class JaMusicBrowseTest {
    @get:Rule val compose = createComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    @Test fun playlistEditsPersistWithoutChangingDeezerAccount() {
        val library = JaMusicLibrary(context)
        val id = library.create("Device regression")
        val one = JaMusicCatalog.Track("11", "One", "Artist", "Album", "", 120, "22", "33")
        val two = one.copy(id = "12", title = "Two")
        try {
            library.add(id, one); library.add(id, two); library.add(id, one)
            library.move(id, two.id, -1); library.rename(id, "Renamed")
            val restored = JaMusicLibrary(context).playlists().first { it.id == id }
            assertEquals("Renamed", restored.name)
            assertEquals(listOf("12", "11"), restored.tracks.map { it.id })
            assertEquals("22", restored.tracks[1].artistId)
            library.remove(id, two.id)
            assertEquals(listOf("11"), JaMusicLibrary(context).playlists().first { it.id == id }.tracks.map { it.id })
            library.toggleLike(one); assertTrue(JaMusicLibrary(context).liked().any { it.id == one.id })
            library.toggleLike(one); assertFalse(JaMusicLibrary(context).liked().any { it.id == one.id })
        } finally { library.delete(id) }
    }
    @Test fun fullScreenLibraryAndPlayerReturnToJa() {
        var exited = false
        compose.setContent { MaterialTheme { JaMusicLabScreen(onExit = { exited = true }) } }
        compose.onNodeWithText("JA Music").assertIsDisplayed()
        compose.onNodeWithText("Library", useUnmergedTree = false).performClick()
        compose.onNodeWithText("Your JA Library").assertIsDisplayed()
        val screenshots = java.io.File(context.getExternalFilesDir(null), "music-ui").apply { mkdirs() }
        java.io.File(screenshots, "library.png").outputStream().use { compose.onRoot().captureToImage().asAndroidBitmap().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        compose.onNodeWithText("Artists").performClick()
        compose.onNodeWithText("No saved artists yet.").assertIsDisplayed()
        compose.onNodeWithText("Albums").performClick()
        compose.onNodeWithText("No saved albums yet.").assertIsDisplayed()
        compose.onNodeWithText("Playlists").performScrollTo().performClick()
        compose.onNodeWithText("Create Playlist").assertIsDisplayed()
        compose.onNodeWithText("Tracks").performScrollTo().performClick()
        compose.onNodeWithText("Liked Songs · 0").performClick()
        compose.onNodeWithText("Liked Songs").assertIsDisplayed()
        compose.onNodeWithText("‹ Back").performClick()
        compose.onNodeWithText("Your JA Library").assertIsDisplayed()
        compose.onNodeWithText("Now Playing", useUnmergedTree = false).performScrollTo().performClick()
        compose.onNodeWithText("Choose a song").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Shuffle Off").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Lyrics").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Visualizer").performScrollTo().assertIsDisplayed()
        java.io.File(screenshots, "player.png").outputStream().use { compose.onRoot().captureToImage().asAndroidBitmap().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        compose.onNodeWithText("‹ JA").performClick()
        compose.runOnIdle { assertTrue(exited) }
    }
    @Test(timeout = 60000) fun androidWebViewReceivesPcmAndRendersFrames() {
        compose.setContent { MaterialTheme { JaMusicVisualizer(onBack = {}) } }
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        var view: android.webkit.WebView? = null
        instrumentation.runOnMainSync {
            fun find(v: android.view.View): android.webkit.WebView? {
                if (v is android.webkit.WebView) return v
                if (v is android.view.ViewGroup) for (i in 0 until v.childCount) find(v.getChildAt(i))?.let { return it }
                return null
            }
            val activity = androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry.getInstance()
                .getActivitiesInStage(androidx.test.runner.lifecycle.Stage.RESUMED).first()
            view = find(activity.window.decorView)
        }
        assertNotNull(view)
        val answer = java.util.concurrent.atomic.AtomicReference("")
        val until = android.os.SystemClock.elapsedRealtime() + 35000
        var passed = false
        while (android.os.SystemClock.elapsedRealtime() < until) {
            val buffer = java.nio.ByteBuffer.allocate(4096)
            repeat(1024) { buffer.put(0); buffer.put(64); buffer.put(0); buffer.put(64) }; buffer.flip()
            JaMusicVisualization.pcm.capture16(buffer, android.os.SystemClock.elapsedRealtime())
            instrumentation.runOnMainSync {
                view!!.evaluateJavascript("JSON.stringify({frames:Number(document.querySelector('canvas').dataset.frames||0),error:document.querySelector('canvas').dataset.error||'',peak:Math.max(...JSON.parse(JaWave.samples()))})") { answer.set(it) }
            }
            android.os.SystemClock.sleep(250)
            val raw = answer.get()
            if (raw.startsWith("\"")) {
                val obj = org.json.JSONObject(org.json.JSONArray("[" + raw + "]").getString(0))
                assertEquals("Android renderer error", "", obj.optString("error"))
                if (obj.optInt("frames") > 3 && obj.optInt("peak") > 128) { passed = true; break }
            }
        }
        assertTrue("Android WebView must render and receive PCM: " + answer.get(), passed)
    }

}
