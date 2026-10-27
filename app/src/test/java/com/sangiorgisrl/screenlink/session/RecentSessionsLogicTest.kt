package com.sangiorgisrl.screenlink.session

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Pure-JVM tests for [RecentSessions.mergeNewestFirst] (ordering, de-dup, cap).
 * No Android / Robolectric / JUnit 4 involvement - keeps the unit-test classpath
 * free of the EPL-licensed junit:junit artifact.
 */
class RecentSessionsLogicTest {

    private fun s(name: String?, at: Long) =
        RecentSessions.RecentSession(1L, "attach", name, "host · $name", at)

    @Test
    fun `most recent is first and equal sessions keep the latest timestamp`() {
        var list = listOf(s("work", 1), s("dev", 2))
        list = RecentSessions.mergeNewestFirst(list, s("work", 3), 6)
        assertEquals(listOf("work", "dev"), list.map { it.sessionName })
        assertEquals(listOf(3L, 2L), list.map { it.at })
    }

    @Test
    fun `different sessions accumulate newest first`() {
        var list: List<RecentSessions.RecentSession> = emptyList()
        list = RecentSessions.mergeNewestFirst(list, s("a", 1), 6)
        list = RecentSessions.mergeNewestFirst(list, s("b", 2), 6)
        list = RecentSessions.mergeNewestFirst(list, s("c", 3), 6)
        assertEquals(listOf("c", "b", "a"), list.map { it.sessionName })
    }

    @Test
    fun `list is capped at max and keeps the newest`() {
        var list: List<RecentSessions.RecentSession> = emptyList()
        for (i in 1..10) {
            list = RecentSessions.mergeNewestFirst(list, s("s$i", i.toLong()), 6)
        }
        assertEquals(6, list.size)
        assertEquals(listOf("s10", "s9", "s8", "s7", "s6", "s5"), list.map { it.sessionName })
    }

    @Test
    fun `empty current list just contains the entry`() {
        val list = RecentSessions.mergeNewestFirst(emptyList(), s("only", 1), 6)
        assertEquals(listOf("only"), list.map { it.sessionName })
    }
}
