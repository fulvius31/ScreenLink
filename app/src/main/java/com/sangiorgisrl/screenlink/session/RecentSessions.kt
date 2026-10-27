package com.sangiorgisrl.screenlink.session

import android.content.Context
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

/**
 * Recently-used terminal sessions, pinned on the Home screen so they can be
 * restarted in one tap. Persisted in SharedPreferences (a small list - Room
 * would be overkill), most recent first, capped at [MAX_ITEMS].
 */
object RecentSessions {

    data class RecentSession(
        val connectionId: Long,
        val mode: String,            // "shell" | "attach" | "create"
        val sessionName: String?,
        val label: String,
        val at: Long,
    ) {
        fun sameSession(other: RecentSession): Boolean =
            other.connectionId == connectionId &&
                other.mode == mode &&
                other.sessionName == sessionName
    }

    const val MAX_ITEMS = 6
    private const val PREFS = "screenlink_recent_sessions"
    private const val KEY_ITEMS = "items"

    private val _items = MutableStateFlow<List<RecentSession>>(emptyList())
    val items: StateFlow<List<RecentSession>> = _items.asStateFlow()

    @Volatile private var loaded = false

    fun ensureLoaded(context: Context) {
        if (loaded) return
        synchronized(this) {
            if (loaded) return
            _items.value = read(context)
            loaded = true
        }
    }

    /** Record a use, de-duplicating by (connection, mode, session) and capping the list. */
    fun record(context: Context, entry: RecentSession) {
        ensureLoaded(context)
        _items.value = mergeNewestFirst(_items.value, entry, MAX_ITEMS)
        write(context)
    }

    /** Pure: insert [entry] at the front, drop any older copy of the same session and
     *  cap the list at [max]. Kept pure so the ordering/cap logic is unit-tested on the
     *  JVM without Android storage (keeps the unit-test classpath free of Robolectric). */
    internal fun mergeNewestFirst(
        current: List<RecentSession>,
        entry: RecentSession,
        max: Int,
    ): List<RecentSession> =
        (listOf(entry) + current.filterNot { it.sameSession(entry) }).take(max)

    fun remove(context: Context, connectionId: Long, mode: String, sessionName: String?) {
        ensureLoaded(context)
        _items.value = _items.value.filterNot {
            it.connectionId == connectionId && it.mode == mode && it.sessionName == sessionName
        }
        write(context)
    }

    fun clearAll(context: Context) {
        ensureLoaded(context)
        _items.value = emptyList()
        write(context)
    }

    /** Test hook: reset in-memory state (does not touch the shared-prefs backing file). */
    fun resetForTest() {
        synchronized(this) {
            loaded = false
            _items.value = emptyList()
        }
    }

    private fun read(context: Context): List<RecentSession> {
        val raw = prefs(context).getString(KEY_ITEMS, null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.getJSONObject(i)
                RecentSession(
                    connectionId = o.optLong("connectionId"),
                    mode = o.optString("mode", "shell"),
                    sessionName = o.optString("sessionName", "").ifBlank { null },
                    label = o.optString("label", "shell"),
                    at = o.optLong("at", 0L),
                )
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun write(context: Context, list: List<RecentSession> = _items.value) {
        val arr = JSONArray()
        list.forEach { r ->
            arr.put(
                JSONObject().apply {
                    put("connectionId", r.connectionId)
                    put("mode", r.mode)
                    put("sessionName", r.sessionName ?: "")
                    put("label", r.label)
                    put("at", r.at)
                }
            )
        }
        prefs(context).edit { putString(KEY_ITEMS, arr.toString()) }
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
