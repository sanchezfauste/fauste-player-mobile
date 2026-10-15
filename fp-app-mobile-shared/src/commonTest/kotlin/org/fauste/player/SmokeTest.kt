package org.fauste.player

import org.fauste.player.rust.EngineConfig
import kotlin.test.Test
import kotlin.test.assertEquals

class SmokeTest {
    @Test
    fun anEngineConfigCarriesThePathsUntouched() {
        val config =
            EngineConfig(
                sampleRate = 48_000u,
                bufferFrames = 512u,
                dataDir = "/data/data/org.fauste.player/files",
                cacheDir = "/data/data/org.fauste.player/cache",
            )
        assertEquals(48_000u, config.sampleRate)
        assertEquals("/data/data/org.fauste.player/files", config.dataDir)
    }
}
