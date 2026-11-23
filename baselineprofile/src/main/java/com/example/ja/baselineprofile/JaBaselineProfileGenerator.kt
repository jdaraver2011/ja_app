package com.example.ja.baselineprofile

import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class JaBaselineProfileGenerator {
    @get:Rule
    val baselineProfileRule = BaselineProfileRule()

    @Test
    fun startupAndMainNavigation() {
        baselineProfileRule.collect(packageName = "com.example.ja") {
            pressHome()
            startActivityAndWait()

            val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
            val logo = device.wait(Until.findObject(By.desc("JA app logo")), 15_000)
                ?: error("JA sign-in gate did not appear")
            repeat(7) {
                logo.click()
                device.waitForIdle()
            }

            check(device.wait(Until.hasObject(By.text("Enter the test unlock code to continue without signing in.")), 5_000))
            val fields = device.findObjects(By.clazz("android.widget.EditText"))
            val unlockField = fields.lastOrNull() ?: error("Test unlock field did not appear")
            unlockField.click()
            unlockField.text = "192168"
            device.findObject(By.text("Unlock")).click()

            check(device.wait(Until.hasObject(By.text("Movies")), 15_000))
            device.waitForIdle()
            listOf("TV", "Search", "Favorites", "Continue", "Settings", "Movies").forEach { tab ->
                device.findObject(By.text(tab))?.click()
                device.waitForIdle()
            }
        }
    }
}
