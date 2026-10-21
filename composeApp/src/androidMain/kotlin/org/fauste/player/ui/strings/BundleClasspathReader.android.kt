package org.fauste.player.ui.strings

/**
 * The Android/JVM actual of the host-lane bundle fallback — see
 * [FluentStrings.readBundleSource]'s KDoc for the design: the unit-test
 * lane (Robolectric) cannot use CMP's resource runtime because its
 * ContentProviders install lazily, so the same bundle files the Gradle
 * wiring publishes onto the test classpath (the `locales/` directory as
 * a classpath root — bundles at `$bundleTag/main.ftl`) are read
 * directly. A missing file fails LOUDLY (an explicit error naming the
 * path — never a silent null masquerading as an empty bundle).
 */
internal actual fun readBundleSourceFromClasspath(bundleTag: String): String {
    val path = "$bundleTag/main.ftl"
    val stream = FluentStrings::class.java.classLoader?.getResourceAsStream(path)
    return stream?.use { it.readBytes().decodeToString() }
        ?: error("the Fluent bundle $path is missing from the classpath")
}
