package org.fauste.player.ui.format

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Pins formatClock to the desktop's `format::clock` behaviour
 * (crates/fp-app/src/ui/format.rs) so mobile and desktop render identical
 * timecodes: zero-padded `mm:ss`, escalating to `h:mm:ss` (unpadded hour)
 * from one hour on, and degenerate input reading as zero.
 */
class TimeFormattingTest {
    @Test
    fun zeroRendersPaddedZeroes() {
        assertEquals("00:00", formatClock(0.0))
    }

    @Test
    fun subMinuteFloorsToWholeSeconds() {
        assertEquals("00:59", formatClock(59.99))
    }

    @Test
    fun minuteBoundaryPads() {
        assertEquals("01:00", formatClock(60.0))
    }

    @Test
    fun padsBothFields() {
        assertEquals("01:05", formatClock(65.4))
    }

    @Test
    fun negativeReadsAsZero() {
        assertEquals("00:00", formatClock(-3.0))
    }

    @Test
    fun nonFiniteReadsAsZero() {
        assertEquals("00:00", formatClock(Double.NaN))
        assertEquals("00:00", formatClock(Double.POSITIVE_INFINITY))
    }

    @Test
    fun hourBoundaryEscalatesToHourForm() {
        assertEquals("1:00:00", formatClock(3600.0))
    }

    @Test
    fun hourFormPadsMinutesAndSeconds() {
        assertEquals("1:01:05", formatClock(3665.0))
    }

    @Test
    fun longDjTrackStaysInMinuteFormUnderOneHour() {
        assertEquals("59:59", formatClock(3599.4))
    }

    @Test
    fun multiHourTrack() {
        assertEquals("2:06:40", formatClock(7600.0))
    }
}
