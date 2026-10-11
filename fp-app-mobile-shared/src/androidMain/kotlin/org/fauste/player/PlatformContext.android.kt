package org.fauste.player

import android.content.Context

actual class PlatformContext(private val context: Context) {
    actual val filesDir: String
        get() = context.filesDir.absolutePath

    actual val cacheDir: String
        get() = context.cacheDir.absolutePath
}
