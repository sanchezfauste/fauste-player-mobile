package org.fauste.player.platform

/**
 * Copies user-picked audio into app-private storage so the engine can read it
 * by absolute path (the Rust core takes file paths, not content URIs).
 *
 * The constructor is platform-specific and intentionally not part of the
 * `expect` contract: common code only ever receives an instance from DI.
 */
expect class Storage {
    /**
     * Copy the bytes behind a content/document [uri] into
     * `<filesDir>/import/<uuid>.<ext>` and return the absolute path, or `null`
     * if the copy failed (unreadable URI, I/O error, unsupported type).
     */
    suspend fun importToAppStorage(uri: String): String?
}
