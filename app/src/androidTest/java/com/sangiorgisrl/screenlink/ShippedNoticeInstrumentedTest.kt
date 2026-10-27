package com.sangiorgisrl.screenlink

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The end-to-end half of the attribution guard: read the credits back out of the *installed
 * package*, which is the only thing that proves they actually shipped.
 *
 * [ShippedNoticeTest] pins the Gradle wiring on the JVM; this reads the same files through
 * AssetManager, i.e. out of the APK on the device. Apache-2.0 section 4 is about what the
 * recipient gets, so this is the assertion that matters.
 */
@RunWith(AndroidJUnit4::class)
@SmallTest
class ShippedNoticeInstrumentedTest {

    @Test
    fun theVendoredAttributionsAreReadableFromTheInstalledPackage() {
        val assets = InstrumentationRegistry.getInstrumentation().targetContext.assets

        // FileNotFoundException here means the APK shipped no attribution at all.
        val notice = assets.open("NOTICE.txt").use { it.readBytes().toString(Charsets.UTF_8) }
        val licence = assets.open("LICENSE-Apache-2.0.txt").use { it.readBytes().toString(Charsets.UTF_8) }

        for (needle in listOf(
            "Termux",
            "Apache License",
            "v0.118.0",
            "com.sangiorgisrl.screenlink.terminal.emulator",
            "com.sangiorgisrl.screenlink.terminal.view",
        )) {
            assertTrue("the shipped NOTICE.txt must mention $needle", notice.contains(needle))
        }
        assertTrue("the shipped licence must be the Apache-2.0 text", licence.contains("Apache License, Version 2.0"))
    }

    @Test
    fun shippingTheCreditDoesNotMakeTheAppAdvertiseAnotherApp() {
        // The whole point of the packaging rule: the attribution is a legal notice, while the
        // app's own identity stays this product's.
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val label = context.packageManager.getApplicationLabel(context.applicationInfo).toString()
        assertTrue("the installed app must present itself as ScreenLink, not as somebody else", label.contains("ScreenLink"))
        assertTrue(
            "the installed package must be this app's own id",
            context.packageName == "com.sangiorgisrl.screenlink",
        )
    }
}
