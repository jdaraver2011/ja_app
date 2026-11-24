package com.example.ja.baselineprofile

import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import androidx.test.uiautomator.UiDevice
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class JaBaselineProfileGenerator {
    @get:Rule
    val baselineProfileRule = BaselineProfileRule()

    @Before
    fun prepareEmulator() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val device = UiDevice.getInstance(instrumentation)
        device.executeShellCommand("am start -W -n com.example.ja/.MainActivity")
        val entry = checkNotNull(device.wait(
            Until.findObject(By.pkg("com.example.ja").desc("JA app logo").clickable(true)), 15_000
        ) ?: device.wait(Until.findObject(By.text("Movies")), 15_000)) {
            "JA did not reach its account screen or home navigation"
        }
        if (entry.text == "Movies") return
        repeat(7) { entry.click() }
        val code = checkNotNull(device.wait(
            Until.findObject(By.clazz("android.widget.EditText")), 10_000
        )) { "JA test access field did not appear" }
        // Use JA's existing local QA access on the disposable emulator.
        code.text = "192168"
        checkNotNull(device.wait(Until.findObject(By.text("Unlock")), 10_000)).click()
        check(device.wait(Until.hasObject(By.text("Movies")), 20_000)) { "JA test access did not open the home screen" }
    }

    @Test
    fun coldStart() {
        baselineProfileRule.collect(packageName = "com.example.ja", includeInStartupProfile = true) {
            pressHome()
            startActivityAndWait()
            check(device.wait(Until.hasObject(By.text("Movies")), 15_000)) { "JA home navigation did not appear" }
        }
    }

    @Test
    fun openTvAndIptvTabs() {
        baselineProfileRule.collect(packageName = "com.example.ja") {
            pressHome()
            startActivityAndWait()
            fun clickTab(tab: String) {
                val control = checkNotNull(device.wait(Until.findObject(By.text(tab)), 15_000)) {
                    "JA tab did not appear: $tab"
                }
                control.click()
            }
            clickTab("TV")
            check(device.wait(Until.hasObject(By.text("Popular")), 15_000)) { "TV navigation did not appear" }
            clickTab("IPTV")
            check(device.wait(Until.hasObject(By.text("JA IPTV")), 15_000)) { "IPTV did not open" }
        }
    }
}
