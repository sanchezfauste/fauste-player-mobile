package org.fauste.player.crash

import android.app.Application
import android.os.Build
import android.util.Log
import java.io.File
import java.util.concurrent.atomic.AtomicLong

/**
 * The JVM half of the mobile's crash reporting (Phase 5, Ruling 5): the
 * default uncaught-exception handler writes one report file under
 * `filesDir/crash/`, prunes the directory to the desktop's
 * `limits.max_crash_reports` default, then chains the PREVIOUS handler —
 * on Android the system's, which logs the crash and kills the process.
 *
 * **The split, documented.** The desktop is a pure Rust app — its whole
 * crash machinery is the panic hook that writes `crash-*.txt` into the
 * log directory (`fp-app/src/crash.rs`, installed at
 * `fp-app/src/main.rs:93-96`), so there is no desktop JVM side to
 * mirror. The mobile therefore ports BOTH halves of itself: the Rust
 * half (`crates-mobile/fp-backends-mobile/src/crash.rs`, installed by
 * the FFI constructor) keeps the desktop's exact semantics for Rust
 * panics — reports into the store's log dir (`filesDir/logs/`), at most
 * `max_crash_reports` per RUN, never deleted — while this Kotlin half
 * covers the exceptions the desktop never has (JVM/Kotlin crashes),
 * under `filesDir/crash/`. The one deliberate deviation from the
 * desktop's shape is the PRUNE: the desktop never deletes a report
 * (its per-run cap alone bounds the flood of a repeating contained
 * panic), but a phone process dies on every uncaught exception — one
 * file per crashing run, forever — so this handler also keeps the
 * DIRECTORY at [MAX_CRASH_REPORTS] files, deleting the oldest.
 *
 * The cap: the desktop's `limits.max_crash_reports` default, 20
 * (`fp-model/src/config.rs`, `Limits::default()`), mirrored as a
 * constant because the FFI does not expose `Limits` to Kotlin (the same
 * ruling as the core's `SESSION_SAVE_DEBOUNCE`: a named constant
 * citing the desktop default, until a settings surface exists).
 *
 * **Tolerance**: everything our half does is best-effort — a process
 * crashing on a broken filesystem must not lose the system's crash
 * handling to an exception of ours (the no-panic-in-the-handler rule the
 * desktop's hook keeps by ignoring every write failure). One try wraps
 * write + prune; failures are logged and swallowed; the previous handler
 * runs regardless.
 */
class CrashReportHandler(
    private val crashDir: File,
    private val appVersion: String,
    private val maxReports: Int = MAX_CRASH_REPORTS,
) : Thread.UncaughtExceptionHandler {
    /** The handler this one replaces, captured at construction; chained after our half. */
    private val previous = Thread.getDefaultUncaughtExceptionHandler()

    /** Keeps same-millisecond file names distinct when several threads crash at once. */
    private val sequence = AtomicLong()

    override fun uncaughtException(
        thread: Thread,
        throwable: Throwable,
    ) {
        try {
            writeReport(thread, throwable)
            prune(crashDir, maxReports)
        } catch (e: Exception) {
            Log.w(LOG_TAG, "the crash report could not be written", e)
        }
        previous?.uncaughtException(thread, throwable)
    }

    /**
     * One report file, mirroring the desktop's report shape
     * (`fp-app/src/crash.rs:48-58`): the header, version, os, thread,
     * message, location — then the stack trace where the desktop appends
     * its backtrace. The name carries the wall-clock stamp (the desktop's
     * file name is its epoch-nanos stamp) plus the per-process sequence
     * so two same-millisecond crashes both survive.
     *
     * The write is ATOMIC — tmp file, then rename — the store's own
     * crash-safety shape (`fp-store/src/atomic.rs:44`: "a crash at any
     * point leaves either the old file or the new one"): a shell-induced
     * Android crash skips the crash dialog and the system SIGKILLs fast,
     * so this code runs INSIDE a race (observed on-device: a kill landing
     * between the file's creation and its write left a 0-byte artifact).
     * The rename makes that class of artifact impossible — the final
     * report is either complete or absent, never a misleading empty file;
     * a killed tmp is never renamed. The race itself is accepted, as the
     * desktop accepts it (`std::fs::write` interrupted by an abort, its
     * every failure `let _`-ignored).
     */
    internal fun writeReport(
        thread: Thread,
        throwable: Throwable,
    ) {
        if (!crashDir.isDirectory && !crashDir.mkdirs()) {
            throw IllegalStateException("the crash directory is unusable: $crashDir")
        }
        val report =
            buildString {
                appendLine("Fauste Player crash report")
                appendLine("version: $appVersion")
                appendLine("os: android ${Build.VERSION.SDK_INT} ${Build.DEVICE}")
                appendLine("thread: ${thread.name}")
                appendLine("message: ${throwable.javaClass.name}: ${throwable.message}")
                appendLine("location: ${locationOf(throwable)}")
                appendLine()
                append(Log.getStackTraceString(throwable))
            }
        val final =
            File(crashDir, "crash-${System.currentTimeMillis()}-${sequence.incrementAndGet()}.txt")
        val tmp = File("${final.absolutePath}.tmp")
        tmp.writeText(report)
        if (!tmp.renameTo(final)) {
            tmp.delete()
            throw IllegalStateException("the crash report could not be renamed into place: $final")
        }
    }

    /** The top frame — the JVM's `file:line` where the desktop names `src/main.rs:93`. */
    internal fun locationOf(throwable: Throwable): String {
        val frame = throwable.stackTrace.firstOrNull() ?: return "unknown"
        return "${frame.className}.${frame.methodName}(${frame.fileName}:${frame.lineNumber})"
    }

    companion object {
        /** The application's logcat tag (the tolerance path's one honest log). */
        private const val LOG_TAG = "CrashReportHandler"

        /**
         * The report-file prefix, shared with [prune] so only reports are
         * ever counted or deleted (the desktop's `crash-` filter,
         * `fp-app/tests/crash_cap.rs:19-23`).
         */
        internal const val FILE_PREFIX = "crash-"

        /**
         * How many crash reports the directory keeps: the desktop's
         * `limits.max_crash_reports` default, 20 (`fp-model/src/config.rs`,
         * `Limits::default()`). A documented constant — the FFI does not
         * expose `Limits` to Kotlin this phase.
         */
        const val MAX_CRASH_REPORTS = 20

        /**
         * The Application wiring: reads the installed version ONCE here
         * (never in the crash path — PackageManager may be part of what
         * is broken), and sets this handler as the process default.
         * Called from `FausteApplication.onCreate`, before any other
         * wiring can throw.
         */
        fun install(app: Application) {
            // minSdk 26 < API 33: the non-deprecated PackageInfoFlags
            // overload needs API 33, so the version read stays on the
            // deprecated signature.
            val version =
                try {
                    @Suppress("DEPRECATION")
                    app.packageManager.getPackageInfo(app.packageName, 0).versionName
                } catch (e: Exception) {
                    null
                } ?: "unknown"
            Thread.setDefaultUncaughtExceptionHandler(
                CrashReportHandler(File(app.filesDir, "crash"), version),
            )
        }
    }
}

/**
 * Keeps the crash directory at `maxReports` files: the oldest
 * `crash-*` files beyond the cap are deleted, newest kept — the
 * cross-run bound the desktop's per-run cap cannot give a process that
 * dies on every crash (see [CrashReportHandler]'s split note). Pure
 * `java.io`, so the unit lane drives it directly.
 */
internal fun prune(
    crashDir: File,
    maxReports: Int,
) {
    val reports =
        crashDir.listFiles { file -> file.isFile && file.name.startsWith(CrashReportHandler.FILE_PREFIX) }
            ?: return
    if (reports.size <= maxReports) return
    reports
        .sortedWith(compareBy({ it.lastModified() }, { it.name }))
        .take(reports.size - maxReports)
        .forEach { it.delete() }
}
