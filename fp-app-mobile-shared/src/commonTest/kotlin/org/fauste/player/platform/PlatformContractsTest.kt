package org.fauste.player.platform

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Host-JVM honest tests: `Build.VERSION.SDK_INT` is 0 in Android unit tests,
 * so the permission actual takes the pre-33 branch here; the TIRAMISU branch
 * is verified on-device in the instrumented layer.
 */
class PlatformContractsTest {
    @Test
    fun audioReadPermissionIsTheLegacyConstantOnHostJdkDefaults() {
        assertEquals("android.permission.READ_EXTERNAL_STORAGE", audioReadPermission)
    }

    @Test
    fun lifecycleHolderRoundTripsState() {
        val holder = AppLifecycleHolder()
        assertEquals(AppLifecycleState.Foreground, holder.current())
        holder.update(AppLifecycleState.Background)
        assertEquals(AppLifecycleState.Background, holder.current())
    }
}
