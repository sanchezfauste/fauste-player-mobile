package org.fauste.player.player

import org.fauste.player.rust.EngineConfig
import org.fauste.player.rust.FausteEngine
import org.fauste.player.rust.Track
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.io.path.createTempDirectory

/**
 * Fixtures for the real-engine tests — the Kotlin mirror of the Rust host
 * suite (`crates-mobile/fp-backends-mobile/tests/ffi_state.rs`): a unique
 * temp dir pair per test, a tiny valid WAV, and a real [FausteEngine] over
 * both (Ruling 7: the cdylib loads through `jna.library.path`; the host has
 * no AAudio, so the engine falls back to its Null backend while the model
 * still transitions — exactly what the bounded waits poll for).
 */
object RealEngineFixture {
    /** A unique, empty data/cache dir pair under the JVM temp dir. */
    class TempDirs(
        tag: String,
    ) {
        val root: File = createTempDirectory("fp-player-test-$tag").toFile()
        val data: File = File(root, "data").apply { mkdirs() }
        val cache: File = File(root, "cache").apply { mkdirs() }

        fun cleanup() {
            root.deleteRecursively()
        }
    }

    /** A real engine over the temp dirs, like the Rust suite's `engine` helper. */
    fun newEngine(dirs: TempDirs): FausteEngine =
        FausteEngine(
            EngineConfig(
                sampleRate = 48_000u,
                bufferFrames = 512u,
                dataDir = dirs.data.absolutePath,
                cacheDir = dirs.cache.absolutePath,
            ),
        )

    /** A [Track] with every metadata field empty: the enqueue contract ignores them all. */
    fun bareTrack(path: String): Track =
        Track(
            path = path,
            title = "",
            artist = "",
            duration = 0.0,
            sampleRate = 0u,
            channels = 0u,
        )

    /**
     * A tiny valid WAV: 44-byte RIFF header + [samples] 16-bit mono PCM
     * samples at 44.1 kHz, a non-silent square wave so a decoder really
     * consumes data (mirrors the Rust suite's `tiny_wav_as`, default
     * 1000 samples ≈ 23 ms).
     */
    fun tinyWav(
        dirs: TempDirs,
        name: String,
        samples: Int = 1_000,
    ): File {
        val dataLen = samples * 2
        val wav = ByteBuffer.allocate(44 + dataLen).order(ByteOrder.LITTLE_ENDIAN)
        wav.put("RIFF".toByteArray(Charsets.US_ASCII))
        wav.putInt(36 + dataLen)
        wav.put("WAVE".toByteArray(Charsets.US_ASCII))
        wav.put("fmt ".toByteArray(Charsets.US_ASCII))
        wav.putInt(16) // fmt chunk size
        wav.putShort(1) // PCM
        wav.putShort(1) // mono
        wav.putInt(44_100) // sample rate
        wav.putInt(88_200) // byte rate
        wav.putShort(2) // block align
        wav.putShort(16) // bits per sample
        wav.put("data".toByteArray(Charsets.US_ASCII))
        wav.putInt(dataLen)
        repeat(samples) { i -> wav.putShort((if (i % 2 == 0) 16_384 else -16_384).toShort()) }
        val path = File(dirs.data, name)
        path.writeBytes(wav.array())
        return path
    }
}
