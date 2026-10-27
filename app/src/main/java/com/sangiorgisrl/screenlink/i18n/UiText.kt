package com.sangiorgisrl.screenlink.i18n

import android.content.res.Resources
import androidx.annotation.StringRes
import androidx.annotation.PluralsRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource

/**
 * A user-visible message that is only turned into text when it is drawn.
 *
 * View models, the SSH layer and the foreground service outlive a language change
 * (switching language recreates the activity but keeps the view model and the
 * service), so they must not bake a translated string in early. They describe
 * *what to say*; the composable that shows it resolves it in the current language.
 */
sealed interface UiText {

    /**
     * A string resource, optionally with one argument. [arg] is deliberately
     * another [UiText] so a translated wrapper can carry an untranslated detail
     * (server stderr, a socket name) and still be re-resolved later.
     */
    data class Resource(@StringRes val id: Int, val arg: UiText? = null) : UiText

    /**
     * A plural resource (e.g. "3 files queued"). The transfer queue counts things
     * the user cannot predict, so its messages have to inflect; [count] picks the
     * grammatical number in the language that is active WHEN the text is shown,
     * not when the queue produced it.
     */
    data class Plural(@PluralsRes val id: Int, val count: Int, val arg: UiText? = null) : UiText

    /** Text produced outside the app: raw server output, library messages. */
    data class Literal(val text: String) : UiText
}

/** Resolve for non-composable callers (notifications, hand-built snackbars). */
fun UiText.resolve(resources: Resources): String = when (this) {
    is UiText.Literal -> text
    is UiText.Plural -> if (arg == null) {
        // The count doubles as the %1$d argument: every plural in this app shows
        // the number it inflects for. Passing it is also what makes a plural with
        // a placeholder render at all (getQuantityString(id, count) alone would
        // print a literal "%1$d"); a plural without one ignores the extra arg.
        resources.getQuantityString(id, count, count)
    } else {
        resources.getQuantityString(id, count, arg.resolve(resources))
    }
    is UiText.Resource -> if (arg == null) {
        resources.getString(id)
    } else {
        resources.getString(id, arg.resolve(resources))
    }
}

/** Resolve inside the composition, so a language change recomposes the text. */
@Composable
fun UiText.rememberResolved(): String {
    // LocalResources (not LocalContext.current.resources) recomposes when the
    // configuration changes, which is exactly what a language switch is.
    val resources = LocalResources.current
    return when (this) {
        is UiText.Literal -> text
    is UiText.Plural -> if (arg == null) {
        pluralStringResource(id, count, count)
    } else {
            pluralStringResource(id, count, arg.resolve(resources))
        }
        is UiText.Resource -> if (arg == null) {
            stringResource(id)
        } else {
            stringResource(id, arg.resolve(resources))
        }
    }
}

/** Shorthand for wrapping a value that is not ours to translate. */
fun uiArg(text: String): UiText = UiText.Literal(text)
