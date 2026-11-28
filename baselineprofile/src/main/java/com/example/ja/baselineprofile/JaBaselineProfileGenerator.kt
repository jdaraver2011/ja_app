package com.example.ja.baselineprofile

import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.uiautomator.By
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.Until
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class JaBaselineProfileGenerator {
    @get:Rule
    val baselineProfileRule = BaselineProfileRule()

    @Test
    fun primaryAppJourneys() {
        baselineProfileRule.collect(packageName = "com.example.ja") {
            pressHome()
            startActivityAndWait()

            fun visit(label: String, timeoutMillis: Long = 2_000) {
                val item = device.wait(Until.findObject(By.text(label)), timeoutMillis)
                if (item != null) {
                    item.click()
                    device.waitForIdle()
                }
            }

            // Cover the main catalogue modes without selecting a title, which
            // would make collection depend on network data and account state.
            listOf("Genres", "Other", "Favorites", "Continue").forEach { visit(it) }
            visit("TV")
            listOf("Genres", "Networks", "Favorites", "Continue").forEach { visit(it) }

            // Search entry and IPTV entry are common startup journeys. IPTV
            // entry exercises the Compose screen and GeckoView prewarm without
            // starting an external stream or two simultaneous players.
            visit("Search")
            visit("IPTV", 10_000)
            device.wait(Until.hasObject(By.text("JA IPTV")), 10_000)
            device.waitForIdle()
            device.pressBack()

            // Visit settings sections without changing any preferences or
            // account state. This includes the player and blocker setup paths.
            visit("Settings")
            listOf("Account", "Ad Blocking", "Player", "Tabs", "Sources").forEach { section ->
                visit(section)
                visit("← Settings")
            }
        }
    }
}
