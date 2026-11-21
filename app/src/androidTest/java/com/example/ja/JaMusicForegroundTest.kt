package com.example.ja

import android.app.ActivityManager
import android.app.NotificationManager
import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.Player
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.InterruptedIOException
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Real Android deadline/notification regression. No Deezer account or full-track claim. */
@RunWith(AndroidJUnit4::class)
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class JaMusicForegroundTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private fun service(): JaMusicLabService? = JaMusicLabService::class.java.getDeclaredField("instance")
        .apply { isAccessible = true }.get(null) as? JaMusicLabService
    private fun player(service: JaMusicLabService): ExoPlayer = JaMusicLabService::class.java.getDeclaredField("player")
        .apply { isAccessible = true }.get(service) as ExoPlayer
    private fun await(timeout: Long, predicate: () -> Boolean) {
        val until = SystemClock.elapsedRealtime() + timeout
        while (SystemClock.elapsedRealtime() < until) {
            var ready = false
            instrumentation.runOnMainSync { ready = predicate() }
            if (ready) return
            SystemClock.sleep(100)
        }
        throw AssertionError("Music service condition timed out after ${timeout}ms")
    }
    @Suppress("DEPRECATION")
    private fun foreground(): Boolean = context.getSystemService(ActivityManager::class.java).getRunningServices(100)
        .any { it.service.className == JaMusicLabService::class.java.name && it.foreground }

    @Test(timeout = 180000)
    fun localPlaybackSurvivesSlowBufferingWithoutAnyBrowserController() {
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        try {
            ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(
                "pm grant ${context.packageName} android.permission.POST_NOTIFICATIONS")).use { it.readBytes() }
            instrumentation.runOnMainSync { JaMusicLabService.restore(context) }
            await(15000) { service() != null }
            instrumentation.runOnMainSync {
                val service = service()!!
                // No MediaBrowser/MediaController bind: this is the missing registration in 2.5.1a.
                assertEquals(1, service.sessions.size)
                assertTrue(service.isSessionAdded(service.sessions.single()))
                JaMusicLabService.playTracks(context, listOf(JaMusicCatalog.Track("1", "Foreground test", "Synthetic", "", "", 70)))
            }
            await(10000) { service()?.let { player(it).mediaItemCount > 0 } == true }
            instrumentation.runOnMainSync {
                val player = player(service()!!)
                val source = ProgressiveMediaSource.Factory(DataSource.Factory { DelayedWaveSource() })
                    .createMediaSource(MediaItem.Builder().setMediaId("foreground-regression")
                        .setUri("memory://music-service-regression.wav").setMimeType(MimeTypes.AUDIO_WAV).build())
                player.setMediaSource(source); player.prepare(); player.play()
            }
            await(5000) { service()?.let { player(it).playbackState == Player.STATE_BUFFERING } == true && foreground() }
            val started = SystemClock.elapsedRealtime()
            // Exceed the exact ~30s timeout observed on the user's device, while still buffering.
            while (SystemClock.elapsedRealtime() - started < 32000) {
                SystemClock.sleep(1000)
                instrumentation.runOnMainSync {
                    assertNotNull(service())
                    assertTrue("Music lost foreground protection while buffering", foreground())
                }
            }
            await(20000) { service()?.let { player(it).isPlaying && player(it).currentPosition >= 1500 } == true }
            instrumentation.runOnMainSync {
                assertTrue(foreground())
                val notices = context.getSystemService(NotificationManager::class.java).activeNotifications
                assertEquals("Bootstrap must be replaced, not duplicated", 1, notices.count { it.id == 251 })
            }
        } finally {
            instrumentation.runOnMainSync { JaMusicLabService.stop(context) }
            scenario.close()
        }
    }

    /** A real WAV data source that takes 35 seconds to open, then provides 70 seconds of silence. */
    private class DelayedWaveSource : DataSource {
        private val pcmBytes = 8000 * 2 * 70
        private val audio = ByteBuffer.allocate(44 + pcmBytes).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray()); putInt(36 + pcmBytes); put("WAVEfmt ".toByteArray())
            putInt(16); putShort(1.toShort()); putShort(1.toShort()); putInt(8000); putInt(16000)
            putShort(2.toShort()); putShort(16.toShort()); put("data".toByteArray()); putInt(pcmBytes)
        }.array()
        private var offset = 0
        override fun addTransferListener(listener: TransferListener) {}
        override fun getUri(): Uri = Uri.parse("memory://music-service-regression.wav")
        override fun open(spec: DataSpec): Long {
            try { Thread.sleep(35000) } catch (_: InterruptedException) { throw InterruptedIOException("Synthetic load cancelled") }
            offset = spec.position.toInt()
            return (audio.size - offset).toLong()
        }
        override fun read(buffer: ByteArray, start: Int, length: Int): Int {
            if (length == 0) return 0
            if (offset >= audio.size) return C.RESULT_END_OF_INPUT
            val count = minOf(length, audio.size - offset)
            audio.copyInto(buffer, start, offset, offset + count); offset += count
            return count
        }
        override fun close() {}
    }
}
