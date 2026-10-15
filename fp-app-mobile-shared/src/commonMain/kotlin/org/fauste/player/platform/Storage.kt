package org.fauste.player.platform

/**
 * Copies user-picked audio into app-private storage so the engine can read it
 * by absolute path (the Rust core takes file paths, not content URIs).
 *
 * The constructor is platform-specific and intentionally not part of the
 * `expect` contract: common code only ever receives an instance from DI.
 *
 * `open` for exactly one consumer: the commonTest double that fakes the
 * copy seam for `org.fauste.player.repository.MediaRepository` — the
 * Android actual's `Uri.parse`/`ContentResolver` path cannot run on the
 * host JVM, so the repository's never-enqueue-a-failed-copy contract is
 * pinned against an open subclass instead. Production code must not
 * subclass.
 */
expect open class Storage {
    /**
     * Copy the bytes behind a content/document [uri] into
     * `<filesDir>/import/<uuid>.<ext>` and return the absolute path, or `null`
     * if the copy failed (unreadable URI, I/O error, unsupported type; partial
     * files are cleaned up). Coroutine cancellation is NOT a failure: a
     * [kotlinx.coroutines.CancellationException] propagates to the caller.
     * Open with the class — the test seam above overrides it.
     */
    open suspend fun importToAppStorage(uri: String): String?
}
