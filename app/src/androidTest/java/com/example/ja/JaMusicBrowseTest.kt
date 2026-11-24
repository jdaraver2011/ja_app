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
    @Test(timeout = 120000)
    fun androidWebViewReceivesPcmAndRendersFrames() {
        compose.setContent {
            MaterialTheme { JaMusicVisualizer(playbackActive = { true }, onBack = {}) }
        }
        compose.waitForIdle()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        lateinit var webView: android.webkit.WebView
        lateinit var presentation: android.widget.ImageView
        instrumentation.runOnMainSync {
            fun views(view: android.view.View): Sequence<android.view.View> = sequence {
                yield(view)
                if (view is android.view.ViewGroup) {
                    for (i in 0 until view.childCount) yieldAll(views(view.getChildAt(i)))
                }
            }
            val activity = androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry.getInstance()
                .getActivitiesInStage(androidx.test.runner.lifecycle.Stage.RESUMED).first()
            val children = views(activity.window.decorView).toList()
            webView = children.filterIsInstance<android.webkit.WebView>().single()
            presentation = children.filterIsInstance<android.widget.ImageView>()
                .single { it.contentDescription == "Music visualizer" }
        }

        val probe = """
            (() => {
                const canvas = document.getElementById('visual');
                const bridge = window.JaWave;
                let signal = false;
                let probeError = '';
                try {
                    if (bridge) {
                        const stereo = JSON.parse(bridge.samples());
                        signal = ['mix', 'left', 'right'].every(key =>
                            Array.isArray(stereo[key]) && stereo[key].length === 1024 &&
                            stereo[key].some(value => Math.abs(value - 128) > 8));
                    }
                } catch (error) {
                    probeError = String(error);
                }
                return JSON.stringify({
                    page: document.readyState, canvas: !!canvas, bridge: !!bridge, signal, probeError,
                    error: canvas?.dataset.error || '', frames: Number(canvas?.dataset.frames || 0)
                });
            })()
        """.trimIndent()
        val answer = java.util.concurrent.atomic.AtomicReference<String?>(null)
        val deadline = android.os.SystemClock.elapsedRealtime() + 85000
        val pcm = java.nio.ByteBuffer.allocate(4096).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        repeat(1024) { index ->
            val sample = (kotlin.math.sin(index * 0.1) * 18000).toInt().toShort()
            pcm.putShort(sample).putShort(sample)
        }
        var firstFrame: android.graphics.Bitmap? = null
        var nativeStatus = "no visible bitmap"
        while (android.os.SystemClock.elapsedRealtime() < deadline) {
            pcm.rewind()
            JaMusicVisualization.pcm.capture16(pcm, android.os.SystemClock.elapsedRealtime())
            var framesPresented = false
            instrumentation.runOnMainSync {
                webView.evaluateJavascript(probe) { answer.set(it) }
                val bitmap = (presentation.drawable as? android.graphics.drawable.BitmapDrawable)?.bitmap
                if (presentation.isShown && bitmap != null) {
                    assertTrue("Unexpected native frame size: ${bitmap.width}x${bitmap.height}",
                        bitmap.width in 1..960 && bitmap.height in 1..540)
                    nativeStatus = "${bitmap.width}x${bitmap.height} bitmap"
                    val hasColor = (0 until bitmap.height step 8).any { y ->
                        (0 until bitmap.width step 8).any { x ->
                            bitmap.getPixel(x, y) and 0x00ffffff != 0
                        }
                    }
                    if (hasColor) {
                        if (firstFrame == null) firstFrame = bitmap
                        framesPresented = firstFrame !== bitmap && firstFrame?.sameAs(bitmap) == false
                    }
                }
            }
            val raw = answer.get()
            if (raw != null && raw.startsWith("\"")) {
                val status = org.json.JSONObject(org.json.JSONArray("[" + raw + "]").getString(0))
                assertEquals("Android renderer error: $status", "", status.optString("error"))
                if (framesPresented && status.optBoolean("signal") && status.optInt("frames") >= 2) return
            }
            android.os.SystemClock.sleep(200)
        }
        fail("Visualizer did not receive PCM and present changing native frames. WebView: ${answer.get()}; Android: $nativeStatus")
    }
}
