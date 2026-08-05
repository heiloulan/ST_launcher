package com.wlav.stlauncher.ui

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class KaomojiFramesTest {
    @Test
    fun `every mood exposes at least one non-blank frame`() {
        KaomojiMood.entries.forEach { mood ->
            val frames = KaomojiFrames.frames(mood)
            assertTrue(frames.isNotEmpty(), "$mood has no frames")
            assertTrue(frames.none(String::isBlank), "$mood has a blank frame")
        }
    }

    @Test
    fun `the praying cycle advances one frame per frame duration`() {
        val duration = KaomojiFrames.frameDurationMillis(KaomojiMood.PRAYING)
        assertEquals(0, KaomojiFrames.frameIndexAt(KaomojiMood.PRAYING, 0))
        assertEquals(0, KaomojiFrames.frameIndexAt(KaomojiMood.PRAYING, duration - 1))
        assertEquals(1, KaomojiFrames.frameIndexAt(KaomojiMood.PRAYING, duration))
        assertEquals(3, KaomojiFrames.frameIndexAt(KaomojiMood.PRAYING, duration * 3))
    }

    @Test
    fun `the cycle wraps instead of running off the end`() {
        val mood = KaomojiMood.PRAYING
        val duration = KaomojiFrames.frameDurationMillis(mood)
        val count = KaomojiFrames.frames(mood).size
        assertEquals(0, KaomojiFrames.frameIndexAt(mood, duration * count))
        assertEquals(2, KaomojiFrames.frameIndexAt(mood, duration * (count * 4 + 2)))
        assertEquals(
            KaomojiFrames.frameAt(mood, 0),
            KaomojiFrames.frameAt(mood, duration * count),
        )
    }

    @Test
    fun `a single-frame mood never advances`() {
        val duration = KaomojiFrames.frameDurationMillis(KaomojiMood.SLEEPING)
        assertEquals(0, KaomojiFrames.frameIndexAt(KaomojiMood.SLEEPING, duration * 99))
    }

    @Test
    fun `the praying bow rises to a peak and returns to rest`() {
        val mood = KaomojiMood.PRAYING
        val curve = KaomojiFrames.frames(mood).indices.map { KaomojiFrames.bowFactorAt(mood, it) }

        assertEquals(0f, curve.first(), EPSILON)
        assertEquals(0f, curve.last(), EPSILON)
        assertEquals(1f, curve.max(), EPSILON)
        // The deepest bow lines up with the hardest-praying face.
        assertEquals(3, curve.indexOf(1f))
        // Monotonic down to the peak, then monotonic back up.
        assertTrue(curve.subList(0, 4).zipWithNext().all { (a, b) -> b > a })
        assertTrue(curve.subList(3, curve.size).zipWithNext().all { (a, b) -> b < a })
    }

    @Test
    fun `bow indices wrap so the rig can interpolate past the last frame`() {
        val mood = KaomojiMood.PRAYING
        val count = KaomojiFrames.frames(mood).size
        assertEquals(
            KaomojiFrames.bowFactorAt(mood, 0),
            KaomojiFrames.bowFactorAt(mood, count),
            EPSILON,
        )
    }

    @Test
    fun `bow factors stay within the normalised range for every mood`() {
        KaomojiMood.entries.forEach { mood ->
            KaomojiFrames.frames(mood).indices.forEach { index ->
                val bow = KaomojiFrames.bowFactorAt(mood, index)
                assertTrue(bow in -1f..1f, "$mood frame $index has bow $bow")
            }
        }
    }

    @Test
    fun `decorative layers are switched off for the resting mood`() {
        assertTrue(KaomojiFrames.hasHalo(KaomojiMood.PRAYING))
        assertTrue(KaomojiFrames.hasSparkles(KaomojiMood.PRAYING))
        // A failure should read as quiet, not festive.
        assertTrue(KaomojiFrames.hasHalo(KaomojiMood.SAD))
        assertFalse(KaomojiFrames.hasSparkles(KaomojiMood.SAD))
        assertFalse(KaomojiFrames.hasHalo(KaomojiMood.SLEEPING))
        assertFalse(KaomojiFrames.hasSparkles(KaomojiMood.SLEEPING))
    }

    @Test
    fun `particle periods are distinct so the field never resynchronises`() {
        val periods = (0 until KaomojiFrames.PARTICLE_COUNT).map(KaomojiFrames::particlePeriodMillis)
        assertEquals(periods.size, periods.distinct().size)
        assertTrue(periods.all { it > 0 })
    }

    @Test
    fun `particle progress stays in range and differs between particles`() {
        val elapsed = 1_234L
        val values = (0 until KaomojiFrames.PARTICLE_COUNT).map { KaomojiFrames.particleProgressAt(it, elapsed) }
        assertTrue(values.all { it in 0f..1f }, "progress out of range: $values")
        assertTrue(values[0] != values[1], "particles share a phase: $values")
    }

    @Test
    fun `particle progress is periodic`() {
        val period = KaomojiFrames.particlePeriodMillis(0)
        assertEquals(
            KaomojiFrames.particleProgressAt(0, 500),
            KaomojiFrames.particleProgressAt(0, 500 + period * 3),
            EPSILON,
        )
    }

    @Test
    fun `every particle index has a full set of layout data`() {
        (0 until KaomojiFrames.PARTICLE_COUNT).forEach { index ->
            assertTrue(KaomojiFrames.particleGlyph(index).isNotBlank())
            assertTrue(KaomojiFrames.particlePhase(index) in 0f..1f)
            assertTrue(KaomojiFrames.particleScale(index) > 0f)
        }
    }

    private companion object {
        const val EPSILON = 1e-6f
    }
}
