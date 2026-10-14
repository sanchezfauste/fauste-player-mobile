package org.fauste.player.platform

import android.content.Context
import android.net.Uri
import android.webkit.MimeTypeMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

actual class Storage(
    private val context: Context,
) {
    actual suspend fun importToAppStorage(uri: String): String? =
        withContext(Dispatchers.IO) {
            runCatching { copyToImportDir(Uri.parse(uri)) }.getOrNull()
        }

    private fun copyToImportDir(source: Uri): String? {
        val resolver = context.contentResolver
        val extension =
            resolver
                .getType(source)
                ?.let(MimeTypeMap.getSingleton()::getExtensionFromMimeType)
                ?: DEFAULT_EXTENSION
        val importDir = File(context.filesDir, IMPORT_SUBDIR).apply { mkdirs() }
        val destination = File(importDir, "${UUID.randomUUID()}.$extension")
        resolver.openInputStream(source)?.use { input ->
            destination.outputStream().use { output -> input.copyTo(output) }
        } ?: return null
        return destination.absolutePath
    }

    private companion object {
        const val IMPORT_SUBDIR = "import"
        const val DEFAULT_EXTENSION = "bin"
    }
}
