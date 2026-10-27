package com.sangiorgisrl.screenlink.i18n

import android.content.Context
import android.content.res.Configuration
import android.app.LocaleManager
import android.os.Build
import android.os.LocaleList
import androidx.core.content.edit
import java.util.Locale

/**
 * The language the app is drawn in, which may differ from the system language.
 *
 * Two mechanisms, because they arrived at different API levels:
 *  - **Android 13+ (API 33)**: [LocaleManager.setApplicationLocales] is the source
 *    of truth. The platform then injects the chosen locale into every context of
 *    the app (activity, service, notification) and restarts the app for us, which
 *    is also what makes the system "Languages" settings page work. That restart is
 *    why the picker warns before switching while sessions are live: the foreground
 *    service goes down with the process.
 *  - **Android 8.1-12 (minSdk 26)**: the choice is stored in SharedPreferences and
 *    applied by [wrap] in `attachBaseContext` of every entry point (Application,
 *    Activity, Service). A Service gets its own ContextImpl, so wrapping only the
 *    Application would leave the notification in the system language.
 *
 * `null` means "follow the system language" - the default, and what a fresh
 * install does.
 */
object AppLocale {

    /** Language codes the app has translations for, in picker order. */
    val SUPPORTED: List<String> = listOf("en", "es")

    private const val PREFS = "app_locale"
    private const val KEY_TAG = "tag"

    /** The user's explicit choice, or null when following the system language. */
    fun configuredTag(context: Context): String? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val manager = context.getSystemService(LocaleManager::class.java) ?: return null
            val locales = manager.applicationLocales
            // Empty LocaleList == "system default".
            return if (locales.isEmpty) null else locales[0].language
        }
        return prefs(context).getString(KEY_TAG, null)
    }

    /**
     * Pick a language ([SUPPORTED]) or pass null to follow the system again.
     *
     * On Android 13+ the platform recreates the activity itself. Below that the
     * caller has to recreate it, because only a fresh `attachBaseContext` picks up
     * the new configuration.
     */
    fun setTag(context: Context, tag: String?) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val manager = context.getSystemService(LocaleManager::class.java) ?: return
            manager.applicationLocales = if (tag == null) {
                LocaleList.getEmptyLocaleList()
            } else {
                LocaleList.forLanguageTags(tag)
            }
            return
        }
        // A null tag removes the key, which is what "follow the system language" means.
        prefs(context).edit { putString(KEY_TAG, tag) }
    }

    /** True when the caller must recreate its activity for the change to show. */
    val needsManualRecreate: Boolean
        get() = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU

    /**
     * Returns a context whose resources use the app's language. A no-op on
     * Android 13+, where the platform already did this.
     */
    fun wrap(base: Context): Context {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) return base
        val tag = prefs(base).getString(KEY_TAG, null) ?: return base
        if (tag == base.resources.configuration.locales[0].language) return base
        val config = Configuration(base.resources.configuration)
        config.setLocale(Locale.forLanguageTag(tag))
        return base.createConfigurationContext(config)
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
