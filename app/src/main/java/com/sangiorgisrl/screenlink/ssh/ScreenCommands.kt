package com.sangiorgisrl.screenlink.ssh

/**
 * Pure, testable GNU Screen command builders and parsers. Every string that ends
 * up in a remote `sh -c` is single-quote escaped here, so user/remote-controlled
 * names cannot inject shell commands.
 */
object ScreenCommands {

    /** Names creatable through the app: [A-Za-z0-9._-], 1..64 chars. */
    val NAME_REGEX = Regex("""[A-Za-z0-9._\-]{1,64}""")

    /** Socket ids from `screen -ls`: "<pid>.<safe-suffix>", e.g. "1234.work". */
    val SOCKET_REGEX = Regex("""^\d+\.[A-Za-z0-9._\-]+$""")

    fun listSessionsCommand(): String = "screen -ls"

    /** Create a detached session that runs an interactive-ish login shell. */
    fun createSessionCommand(name: String): String {
        // Names are single-quote escaped, so spaces/unicode are fine and injection is inert.
        return "screen -dmS ${ShellQuote.single(name)} bash -l"
    }

    /** Terminate a session by socket id. */
    fun killSessionCommand(socket: String): String {
        require(isValidSocket(socket)) { "Invalid screen socket" }
        return "screen -S ${ShellQuote.single(socket)} -X quit"
    }

    /** Detach a session (leave it running) by socket id. */
    fun detachSessionCommand(socket: String): String {
        require(isValidSocket(socket)) { "Invalid screen socket" }
        return "screen -S ${ShellQuote.single(socket)} -X detach"
    }

    /** Attach to an existing session (fails if it is gone). */
    fun attachCommand(name: String): String {
        return "screen -dr ${ShellQuote.single(name)}"
    }

    /** Attach, or create a new session if none exists (for a connection's default session). */
    fun attachOrCreateCommand(name: String): String {
        return "screen -dR ${ShellQuote.single(name)}"
    }

    /**
     * `screen -S <name> -X mousetrack off` - take GNU Screen's own mouse claim back.
     *
     * Measured against screen 4.09.01: the moment `mousetrack on` is set, SCREEN itself
     * switches the OUTER terminal into mouse reporting (`ESC[?1000h`, and `ESC[?1000l`
     * when it is turned off again). Every attached client then believes the *inner*
     * application asked for pointer events, which is exactly what made finger scrolling
     * do nothing in a plain shell: the wheel/drag went to screen (which answers it with
     * status-line noise instead of scrolling) and the Scroll buttons sent PGUP to bash
     * (shell-history recall).
     *
     * Turning it off costs nothing for apps that really do want the mouse: screen
     * forwards an application's own `?1000h/?1002h/?1006h` to the outer terminal and
     * relays clicks and wheel events to it whether or not `mousetrack` is on, and
     * `mousetrack off` while an app holds the mouse sends nothing at all (all measured).
     */
    fun disableMouseTrackingCommand(name: String): String {
        return "screen -S ${ShellQuote.single(name)} -X mousetrack off"
    }

    /** Validate + normalize a user-supplied session name for the terminal route. */
    fun normalizeName(raw: String): String {
        // Strip control characters that could be injected via a deep link or form.
        return raw.replace(Regex("[\\u0000\\r\\n]"), "").trim()
    }

    fun isValidName(name: String): Boolean = NAME_REGEX.matches(name)

    fun isValidSocket(socket: String): Boolean = SOCKET_REGEX.matches(socket)

    /**
     * Human-friendly GNU Screen name for a tab, from either a socket id or a plain
     * session name. A socket looks like "<pid>.<name>" (e.g. "1234.work") and its
     * tab should show the name part ("work"); a plain name (created here or the
     * connection's default session) is returned unchanged.
     */
    fun friendlyScreenName(socketOrName: String): String {
        val cleaned = normalizeName(socketOrName)
        if (cleaned.isEmpty()) return cleaned
        return if (SOCKET_REGEX.matches(cleaned)) cleaned.substringAfter('.') else cleaned
    }
}

/** POSIX single-quote escaping so arbitrary names are safe to splice into shell commands. */
object ShellQuote {
    fun single(value: String): String = "'" + value.replace("'", "'\\''") + "'"
}

/**
 * Parses `screen -ls` output into session entries. Kept pure so it can be unit tested.
 */
object ScreenSessions {
    // Lines look like: "\t1234.work\t(Detached)" or "\t1234.pts-0.myserver\t(Attached)".
    // Newer GNU Screen may insert a timestamp before the state, e.g.:
    //   "\t1134.demo\t(10/21/26 18:15:31)\t(Detached)"
    private val PATTERN = Regex(
        """^\s*(\d+)\.(\S+)\s+(?:\([^)]*\)\s+)?\((Attached|Detached|Unavailable)\)\s*$"""
    )

    fun parse(text: String): List<ScreenSession> {
        val result = mutableListOf<ScreenSession>()
        for (line in text.lines()) {
            val match = PATTERN.find(line) ?: continue
            val pid = match.groupValues[1]
            val suffix = match.groupValues[2]
            val state = match.groupValues[3]
            val socket = "$pid.$suffix"
            if (!ScreenCommands.SOCKET_REGEX.matches(socket)) continue
            // GNU Screen shows the socket suffix verbatim: with -S <name> it is the
            // session name (e.g. "1234.work"), otherwise the pty, e.g. "pts-0.host".
            val name = suffix
            result += ScreenSession(socket = socket, name = name, state = state)
        }
        return result
    }
}

/**
 * Build the attach/create command for the terminal from a route's raw session name.
 *
 * The two modes are deliberately different:
 *  - "attach": `screen -dr <name>` - resumes the named session and FAILS loudly if it
 *    really is gone (so a user who wanted their work back is never silently handed a
 *    brand-new shell).
 *  - "create":  `screen -dR <name>` - attaches or auto-creates (used by the "default
 *    session" quick-open which is documented as "attaches or creates").
 */
fun buildTerminalScreenCommand(mode: String, rawSessionName: String?): String? {
    if (rawSessionName == null) return null
    val name = ScreenCommands.normalizeName(rawSessionName)
    if (name.isEmpty()) return null
    return when (mode) {
        "attach" -> ScreenCommands.attachCommand(name)
        "create" -> ScreenCommands.attachOrCreateCommand(name)
        else -> null
    }
}
