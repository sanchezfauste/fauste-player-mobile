package org.fauste.player.ui.strings

import dev.karmakrafts.fluently.LocalizationFile
import dev.karmakrafts.fluently.eval.EvaluationContextBuilder
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.fauste.player.res.Res

/**
 * The [Strings] seam's Fluent implementation: one parsed
 * [LocalizationFile] for the active locale plus the en-US source bundle
 * as the fallback, per the spec §4 ruling (en-US is the source of truth;
 * every other locale is a translation of it).
 *
 * KEY-MISS BEHAVIOR (the documented ladder): a message missing from the
 * locale bundle falls back to en-US; a message missing from BOTH renders
 * the key id itself (`strings[StringKeys.X]` on an un-translated key
 * shows `x`, never a crash, never an invented string). Fluently's own
 * `<key>` placeholder is bypassed by using its `formatOrNull` — the key
 * id alone reads better in a UI than `<key>` angle brackets.
 *
 * ARGUMENTS map onto Fluent variables by type: strings as strings,
 * booleans as `true`/`false` selector values (the backend rows select on
 * them), numbers as numbers; anything else is rendered via `toString()`.
 * Timecodes arrive pre-formatted (formatClock) — Fluent formats messages,
 * not media positions, and the mono timecode must not be locale-reordered.
 *
 * The class is pure commonMain (parse + format); [Companion.forLocale]
 * is the app's factory, reading the bundled `.ftl` sources through CMP's
 * resource runtime (the fonts' proven lane) and memoizing one instance
 * per bundle — the parse is ANTLR-backed and measured at ~128 ms cold /
 * ~12 ms warm per bundle on the JVM, so every consumer shares the cached
 * instance instead of re-parsing.
 */
class FluentStrings(
    private val locale: LocalizationFile,
    private val fallback: LocalizationFile,
) : Strings {
    override fun get(key: String): String = resolve(key) { }

    override fun get(
        key: String,
        vararg args: Pair<String, Any?>,
    ): String =
        resolve(key) {
            args.forEach { (name, value) -> bind(name, value) }
        }

    /** The key-miss ladder's one place: locale → en-US → the key id itself. */
    private inline fun resolve(
        key: String,
        crossinline build: EvaluationContextBuilder.() -> Unit,
    ): String = locale.formatOrNull(key) { build() } ?: fallback.formatOrNull(key) { build() } ?: key

    /** Maps one argument onto a Fluent variable by runtime type (the class KDoc's table). */
    private fun EvaluationContextBuilder.bind(
        name: String,
        value: Any?,
    ) {
        when (value) {
            is String -> stringVariable(name, value)
            is Boolean -> boolVariable(name, value)
            is Number -> numberVariable(name, value)
            null -> stringVariable(name, "")
            else -> stringVariable(name, value.toString())
        }
    }

    companion object {
        /** en-US: the source bundle and the always-present fallback (spec §4). */
        const val DEFAULT_LOCALE: String = "en-US"

        /** The one Spanish bundle today; es-* locales all read it (the mapping's KDoc). */
        private const val SPANISH_BUNDLE: String = "es-ES"

        /** The exact failure wording of CMP's uninitialized resource context (readBundleSource matches it). */
        private const val CMP_CONTEXT_NOT_INITIALIZED: String = "Android context is not initialized"

        // One instance per bundle, produced single-flight: the first caller
        // parses, everyone else joins the mutex and takes the cached value.
        private val singleFlight = Mutex()
        private val cache = mutableMapOf<String, FluentStrings>()

        /**
         * The locale→bundle mapping, deliberately coarse and documented:
         * any Spanish system locale (exact `es`, or any `es-*` region)
         * reads the es-ES bundle — regional varieties are mutually
         * intelligible and one careful Spain-Spanish copy serves them
         * better than an un-translated fallback; every other locale reads
         * en-US. Revisit the day a second variant is actually requested.
         */
        fun bundleTagFor(localeTag: String): String {
            val normalized = localeTag.trim().lowercase()
            return if (normalized == "es" || normalized.startsWith("es-")) SPANISH_BUNDLE else DEFAULT_LOCALE
        }

        /**
         * The memoized, single-flight resolver for [localeTag]. Suspending:
         * the bundled sources load through CMP's resource runtime, and
         * the parse runs on the caller's context — callers own where that
         * is (MainActivity reads it under the launch window via
         * runBlocking; the playback service resolves it inside its
         * off-main scope; tests runBlocking their own).
         */
        suspend fun forLocale(localeTag: String): FluentStrings {
            val bundleTag = bundleTagFor(localeTag)
            return singleFlight.withLock {
                cache.getOrPut(bundleTag) { load(bundleTag) }
            }
        }

        private suspend fun load(bundleTag: String): FluentStrings {
            val localeSource = readBundleSource(bundleTag)
            val fallbackSource =
                if (bundleTag == DEFAULT_LOCALE) {
                    localeSource
                } else {
                    readBundleSource(DEFAULT_LOCALE)
                }
            return FluentStrings(
                locale = LocalizationFile.parse(localeSource),
                fallback = LocalizationFile.parse(fallbackSource),
            )
        }

        /**
         * ONE reader, two documented physical paths, identical bytes: the
         * CMP resource runtime is the loader (`Res.readBytes` — the
         * proven lane the fonts use on device), and the host lane's
         * fallback is the platform classpath (the Gradle wiring publishes
         * the same `locales/` files onto the unit-test classpath). The
         * fallback exists because Robolectric instantiates manifest
         * ContentProviders LAZILY, so CMP's AndroidContextProvider — the
         * thing that hands the resource runtime its Application context,
         * eagerly installed by the platform on every device — is not
         * initialized on the test lane (verified empirically: the static
         * reads null at test-method start; the fonts have been failing
         * silently there all along — an async font failure renders with a
         * fallback typeface, a string resolver fails loudly). The catch
         * matches ONLY that exact failure by message (CMP 1.9.3-pinned
         * wording); anything else rethrows.
         */
        internal suspend fun readBundleSource(bundleTag: String): String =
            try {
                Res.readBytes("files/locales/$bundleTag/main.ftl").decodeToString()
            } catch (e: IllegalStateException) {
                if (e.message?.contains(CMP_CONTEXT_NOT_INITIALIZED) != true) throw e
                readBundleSourceFromClasspath(bundleTag)
            }
    }
}

/**
 * The host lane's bundle fallback (see [FluentStrings.readBundleSource]'s
 * KDoc for the whole story): reads the bundle from the platform's
 * resource classpath — the Gradle wiring publishes the app's own
 * `locales/` directory as a classpath root, so a bundle sits at
 * `/$bundleTag/main.ftl`. The Android actual is the JVM classloader
 * read; a future non-JVM target implements its own host-lane reader.
 */
internal expect fun readBundleSourceFromClasspath(bundleTag: String): String
