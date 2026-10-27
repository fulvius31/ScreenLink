package com.sangiorgisrl.screenlink

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The artifact-level half of the packaging rule.
 *
 * [AppIdentityTest] proves the *sources* are clean; the README claim is about the *APK*.
 * Only a device can check that: it loads classes out of the installed package, so a
 * re-introduced upstream dependency - a leftover AAR, a stray jar, an accidental import -
 * fails here even though every source scan stays green.
 */
@RunWith(AndroidJUnit4::class)
@SmallTest
class PackagingIdentityInstrumentedTest {

    @Test
    fun theTerminalCodeShipsAsThisAppOwnClasses() {
        for (own in OWN_TERMINAL_CLASSES) {
            val loaded = Class.forName(own)
            assertTrue(
                "$loaded must be owned by this app's namespace",
                loaded.name.startsWith("com.sangiorgisrl.screenlink."),
            )
        }
    }

    @Test
    fun noOtherAppClassesAreInsideTheInstalledPackage() {
        for (foreign in FOREIGN_CLASSES) {
            val present = try {
                Class.forName(foreign)
                true
            } catch (_: ClassNotFoundException) {
                false
            }
            assertFalse(
                "$foreign is inside the installed APK: the terminal code must be this app's own",
                present,
            )
        }
    }

    @Test
    fun theInstalledPackageIsThisAppAndNotSomebodyElse() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assertEquals("com.sangiorgisrl.screenlink", context.packageName)
        val label = context.packageManager.getApplicationLabel(context.applicationInfo).toString()
        assertTrue("the launcher name must be this product's, got '$label'", label.contains("ScreenLink"))
    }

    private companion object {
        val OWN_TERMINAL_CLASSES = listOf(
            "com.sangiorgisrl.screenlink.terminal.emulator.TerminalEmulator",
            "com.sangiorgisrl.screenlink.terminal.emulator.KeyHandler",
            "com.sangiorgisrl.screenlink.terminal.view.TerminalView",
            "com.sangiorgisrl.screenlink.terminal.view.TerminalRenderer",
        )

        /** The upstream ids the vendored code used to live under. */
        val FOREIGN_CLASSES = listOf(
            "com.termux.terminal.TerminalEmulator",
            "com.termux.terminal.KeyHandler",
            "com.termux.terminal.WcWidth",
            "com.termux.view.TerminalView",
            "com.termux.view.TerminalRenderer",
        )
    }
}
