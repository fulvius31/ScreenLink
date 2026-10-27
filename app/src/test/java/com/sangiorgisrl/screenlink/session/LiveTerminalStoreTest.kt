package com.sangiorgisrl.screenlink.session

import com.sangiorgisrl.screenlink.R
import com.sangiorgisrl.screenlink.i18n.UiText
import com.sangiorgisrl.screenlink.ssh.ScreenCommands
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/** Multi-tab (multi-session) store transitions, without building real SSH sessions. */
class LiveTerminalStoreTest {

    @BeforeEach
    fun resetStore() { LiveTerminalStore.reset() }

    private fun req(sessionName: String?, mode: String = "shell") = LiveTerminalStore.TabRequest(
        id = LiveTerminalStore.newId(),
        connectionId = 1L,
        mode = mode,
        sessionName = sessionName,
    )

    @Test
    fun `starts empty with no active tab`() {
        assertTrue(LiveTerminalStore.tabs.value.isEmpty())
        assertEquals(null, LiveTerminalStore.activeId.value)
    }

    @Test
    fun `adding a tab makes it active and connecting`() {
        val tab = LiveTerminalStore.addTab(req(null))
        assertEquals(1, LiveTerminalStore.tabs.value.size)
        assertEquals(tab.id, LiveTerminalStore.activeId.value)
        assertTrue(LiveTerminalStore.tabOf(tab.id)!!.state is LiveTerminalStore.SessionState.Connecting)
    }

    @Test
    fun `multiple tabs coexist`() {
        val t1 = LiveTerminalStore.addTab(req(null))
        val t2 = LiveTerminalStore.addTab(req("work", "attach"))
        assertEquals(2, LiveTerminalStore.tabs.value.size)
        assertEquals(t2.id, LiveTerminalStore.activeId.value)
        // The attach request carries the Screen command for a normal terminal.
        assertTrue(t2.request.initialCommand != null)
    }

    @Test
    fun `update and rename a tab`() {
        val tab = LiveTerminalStore.addTab(req("work", "attach"))
        LiveTerminalStore.updateTab(tab.id, LiveTerminalStore.SessionState.Error(UiText.Literal("boom")))
        assertTrue(LiveTerminalStore.tabOf(tab.id)!!.state is LiveTerminalStore.SessionState.Error)
        LiveTerminalStore.renameTab(tab.id, UiText.Literal("prod · work"))
        assertEquals(UiText.Literal("prod · work"), LiveTerminalStore.tabOf(tab.id)!!.label)
    }

    // A tab opened in one language is still labelled correctly after switching,
    // because its label is a resource id rather than resolved text.
    @Test
    fun `default labels are resources`() {
        val shell = LiveTerminalStore.addTab(req(null, "shell"))
        assertEquals(UiText.Resource(R.string.home_mode_shell), shell.label)
        val screen = LiveTerminalStore.addTab(req(null, "screen"))
        assertEquals(UiText.Resource(R.string.tab_screen), screen.label)
        // A user-chosen screen session name is data, so it stays verbatim.
        val named = LiveTerminalStore.addTab(req("mi-sesion", "screen"))
        assertEquals(UiText.Literal(ScreenCommands.friendlyScreenName("mi-sesion")), named.label)
    }

    @Test
    fun `updating a removed tab is a no-op`() {
        val tab = LiveTerminalStore.addTab(req(null))
        LiveTerminalStore.removeTab(tab.id)
        LiveTerminalStore.updateTab(tab.id, LiveTerminalStore.SessionState.Finished(UiText.Literal("x")))
        assertTrue(LiveTerminalStore.tabs.value.isEmpty())
    }

    @Test
    fun `closing the active tab activates a neighbour`() {
        val t1 = LiveTerminalStore.addTab(req(null))
        val t2 = LiveTerminalStore.addTab(req("s2"))
        assertEquals(t2.id, LiveTerminalStore.activeId.value)
        LiveTerminalStore.removeTab(t2.id)
        assertEquals(t1.id, LiveTerminalStore.activeId.value)
    }

    @Test
    fun `closing the last tab empties the store`() {
        val t1 = LiveTerminalStore.addTab(req(null))
        LiveTerminalStore.removeTab(t1.id)
        assertTrue(LiveTerminalStore.tabs.value.isEmpty())
        assertEquals(null, LiveTerminalStore.activeId.value)
    }

    @Test
    fun `setActive ignores unknown ids`() {
        LiveTerminalStore.addTab(req(null))
        LiveTerminalStore.setActive("nope")
        assertNotEquals("nope", LiveTerminalStore.activeId.value)
    }
}
