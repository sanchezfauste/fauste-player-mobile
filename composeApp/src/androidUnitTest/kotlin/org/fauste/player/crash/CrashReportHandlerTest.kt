package org.fauste.player.crash

import android.app.Application
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * The JVM crash handler (Phase 5): one report file per uncaught
 * exception under `filesDir/crash/`, the directory pruned to the
 * desktop's `max_crash_reports` default (20), the PREVIOUS handler
 * chained after ours, and the write itself best-effort — a failed
 * report must never cost the process its system crash handling.
 *
 * The handler is invoked DIRECTLY (not via
 * `Thread.setDefaultUncaughtExceptionHandler`), so only the tests that
 * pin the default-handler wiring itself touch the process global, and
 * each such test saves and restores it. The Robolectric lane
 * (`@Config(sdk = [36], application = Application::class)`, the
 * house pattern) gives the handler a real `filesDir`; the RUST half's
 * tests live in `crates-mobile/fp-backends-mobile/tests/crash_cap.rs`.
 */
@RunWith(org.robolectric.RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class CrashReportHandlerTest {
    private val app: Application get() = RuntimeEnvironment.getApplication()

    /** Records what the previous-handler chain passed on. */
    private class RecordingHandler : Thread.UncaughtExceptionHandler {
        var thread: Thread? = null
        var throwable: Throwable? = null

        override fun uncaughtException(
            thread: Thread,
            throwable: Throwable,
        ) {
            this.thread = thread
            this.throwable = throwable
        }
    }

    /** A fresh, empty crash directory under the app's filesDir. */
    private fun freshCrashDir(name: String): File =
        File(File(app.filesDir, name), "crash").apply {
            deleteRecursively()
            mkdirs()
        }

    /** A report-shaped file, seeded with a controlled age for the prune. */
    private fun seed(
        dir: File,
        name: String,
        age: Long,
    ): File =
        File(dir, name).apply {
            writeText("seed $name\n")
            setLastModified(age)
        }

    @Test
    fun a_synthetic_throwable_writes_a_report_under_files_dir_crash() {
        val dir = freshCrashDir("writes")
        val handler = CrashReportHandler(dir, "test-version")
        val thread = Thread.currentThread()
        val boom = RuntimeException("synthetic boom")

        handler.uncaughtException(thread, boom)

        val reports = dir.listFiles { file -> file.name.startsWith(CrashReportHandler.FILE_PREFIX) }
        assertEquals(1, reports?.size)
        assertTrue(
            reports?.none { it.name.endsWith(".tmp") } == true,
            "a completed write renames its tmp into place; none may linger",
        )
        val content = reports?.first()?.readText().orEmpty()
        assertTrue(
            content.startsWith("Fauste Player crash report\nversion: test-version\n"),
            "the report must carry the desktop's header, got: $content",
        )
        assertTrue(content.contains("os: android "), "the os line names the platform")
        assertTrue(content.contains("thread: ${thread.name}\n"), "the report names the thread")
        assertTrue(
            content.contains("message: java.lang.RuntimeException: synthetic boom\n"),
            "the report carries the throwable",
        )
        assertTrue(
            content.contains("location: org.fauste.player.crash.CrashReportHandlerTest."),
            "the location points at the throwing frame, got: $content",
        )
        assertTrue(
            content.contains("\njava.lang.RuntimeException: synthetic boom\n"),
            "the stack trace follows the header (the desktop's backtrace slot)",
        )
    }

    @Test
    fun the_prune_keeps_the_directory_at_the_desktop_default() {
        val dir = freshCrashDir("prune-cap")
        val handler = CrashReportHandler(dir, "test-version")
        // 25 seeded reports, ages 1..25 — a phone that has crashed 25
        // times across 25 process runs.
        for (age in 1..25L) {
            seed(dir, "crash-seed-${age.toString().padStart(2, '0')}.txt", age)
        }

        handler.uncaughtException(Thread.currentThread(), RuntimeException("twenty sixth"))

        val reports = dir.listFiles { file -> file.name.startsWith(CrashReportHandler.FILE_PREFIX) }
        assertEquals(CrashReportHandler.MAX_CRASH_REPORTS, reports?.size)
        assertEquals(20, CrashReportHandler.MAX_CRASH_REPORTS, "the desktop default, fp-model Limits")
        // The 6 oldest seeded reports went, the newest 19 and the fresh
        // report stay — the prune is age-ordered, not name-ordered.
        assertTrue(!File(dir, "crash-seed-06.txt").exists(), "the 6th oldest is gone")
        assertTrue(File(dir, "crash-seed-07.txt").exists(), "the 7th oldest stays")
        assertTrue(
            reports?.any { it.name.endsWith("-1.txt") && it.readText().contains("twenty sixth") } == true,
            "the fresh report survived its own prune",
        )
    }

    @Test
    fun the_prune_is_pure_and_touches_nothing_under_the_cap() {
        val dir = freshCrashDir("prune-pure")
        for (age in 30L downTo 1L) {
            seed(dir, "crash-under-$age.txt", age)
        }
        prune(dir, 20)
        assertEquals(20, dir.listFiles()?.size, "exactly the newest 20 survive")
        assertTrue(!File(dir, "crash-under-10.txt").exists(), "the 10 oldest went")

        val small = freshCrashDir("prune-pure-small")
        for (age in 1..5L) {
            seed(small, "crash-keep-$age.txt", age)
        }
        prune(small, 20)
        assertEquals(5, small.listFiles()?.size, "a directory under the cap loses nothing")
    }

    @Test
    fun the_previous_handler_is_chained() {
        val original = Thread.getDefaultUncaughtExceptionHandler()
        try {
            val recorder = RecordingHandler()
            Thread.setDefaultUncaughtExceptionHandler(recorder)
            val handler = CrashReportHandler(freshCrashDir("chain"), "test-version")
            val thread = Thread.currentThread()
            val boom = RuntimeException("chained")

            handler.uncaughtException(thread, boom)

            assertSame(thread, recorder.thread, "the previous handler saw the same thread")
            assertSame(boom, recorder.throwable, "the previous handler saw the same throwable")
        } finally {
            Thread.setDefaultUncaughtExceptionHandler(original)
        }
    }

    @Test
    fun a_failed_write_never_loses_the_chain() {
        val original = Thread.getDefaultUncaughtExceptionHandler()
        try {
            val recorder = RecordingHandler()
            Thread.setDefaultUncaughtExceptionHandler(recorder)
            // A FILE squatting on the crash-directory path: mkdirs fails,
            // the write must throw inside — and only inside — the
            // best-effort try.
            val notADir =
                File(File(app.filesDir, "unwritable").apply { mkdirs() }, "occupied")
                    .apply { writeText("not a directory") }
            val handler = CrashReportHandler(notADir, "test-version")
            val boom = RuntimeException("tolerated")

            handler.uncaughtException(Thread.currentThread(), boom)

            assertSame(boom, recorder.throwable, "the system's handler still ran")
        } finally {
            Thread.setDefaultUncaughtExceptionHandler(original)
        }
    }

    @Test
    fun install_wires_the_process_default_handler() {
        val original = Thread.getDefaultUncaughtExceptionHandler()
        try {
            CrashReportHandler.install(app)
            assertTrue(
                Thread.getDefaultUncaughtExceptionHandler() is CrashReportHandler,
                "install must own the process default",
            )
        } finally {
            Thread.setDefaultUncaughtExceptionHandler(original)
        }
    }
}
