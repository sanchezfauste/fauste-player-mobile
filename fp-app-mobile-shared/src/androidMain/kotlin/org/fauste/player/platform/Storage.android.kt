package org.fauste.player.platform

import android.content.Context
import android.net.Uri
import android.webkit.MimeTypeMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

actual class Storage(
    private val context: Context,
) {
    actual suspend fun importToAppStorage(uri: String): String? = withContext(Dispatchers.IO) { copyToImportDir(Uri.parse(uri)) }

    private fun copyToImportDir(source: Uri): String? {
        val resolver = context.contentResolver
        val extension =
            resolver
                .getType(source)
                ?.let(MimeTypeMap.getSingleton()::getExtensionFromMimeType)
                ?: DEFAULT_EXTENSION
        val importDir = File(context.filesDir, IMPORT_SUBDIR).apply { mkdirs() }
        val destination = File(importDir, "${UUID.randomUUID()}.$extension")
        return runCatching {
            resolver.openInputStream(source)?.use { input ->
                destination.outputStream().use { output -> input.copyTo(output) }
            } ?: return null
            destination.absolutePath
        }.onFailure { failure ->
            // Cancellation is not an import failure: it must propagate.
            if (failure is CancellationException) throw failure
            // Best-effort: drop the partial copy; never mask the original failure.
            runCatching { destination.delete() }
        }.getOrNull()
    }

    private companion object {
        const val IMPORT_SUBDIR = "import"
        const val DEFAULT_EXTENSION = "bin"
    }
}
