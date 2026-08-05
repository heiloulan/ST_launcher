package com.wlav.stlauncher.ui

/**
 * Emotional register of the loading-screen figure. Each mood selects a frame
 * cycle and decides whether the halo and sparkle layers participate.
 */
enum class KaomojiMood {
    /** Waiting on the runtime: the praying cycle, halo and sparkles on. */
    PRAYING,

    /** The endpoint answered and the page is coming up. */
    HAPPY,

    /** Startup failed; the figure slumps and the decorative layers go quiet. */
    SAD,

    /** Nothing is running; a single static frame with no animation at all. */
    SLEEPING,
}

/**
 * Frame data for the loading-screen animation.
 *
 * Deliberately free of Android types so the timing curves stay unit-testable.
 * Everything is a pure function of elapsed time, which also means the rig can
 * be driven from a single ticker without any per-layer state.
 *
 * Frames are single-line on purpose: multi-line kaomoji art misaligns badly in
 * a `TextView` because CJK and symbol glyphs do not share an advance width even
 * in a monospace typeface. Visual richness comes from layering instead.
 */
object KaomojiFrames {
    private val PRAYING_FRAMES =
        listOf(
            "( ˘ω˘ )",
            "( ˘人˘ )",
            "( -人- )",
            "( ×人× )",
            "( -人- )",
            "( ˘人˘ )",
            "( ･ω･ )",
            "( ˘ω˘ )",
        )

    /**
     * Downward dip applied per frame, normalised to `-1..1`. Positive values
     * bow forward, negative ones hop up. Coupling the offset to the frame index
     * is what makes the face change and the body movement read as one gesture
     * instead of a slideshow of unrelated faces.
     */
    private val PRAYING_BOW = listOf(0f, 0.30f, 0.70f, 1f, 0.70f, 0.35f, 0.05f, 0f)

    private val HAPPY_FRAMES = listOf("( ˃̶ᴗ˂̶ )", "( ≧▽≦ )", "( ˃̶ᴗ˂̶ )", "( ・ᴗ・ )")
    private val HAPPY_BOW = listOf(-0.8f, -1f, -0.5f, 0f)

    private val SAD_FRAMES = listOf("( ｡•́︿•̀｡ )", "( ；´д｀)")
    private val SAD_BOW = listOf(0.35f, 0.6f)

    private val SLEEPING_FRAMES = listOf("( -ω- ) zzZ")
    private val SLEEPING_BOW = listOf(0f)

    /** Sparkle glyphs, one per particle. */
    val PARTICLE_GLYPHS = listOf("✧", "✦", "⋆", "˚", "·", "｡")

    val PARTICLE_COUNT: Int get() = PARTICLE_GLYPHS.size

    /**
     * Starting phase of each particle within its own loop. Hand-picked rather
     * than evenly spaced so the field never resolves into a single pulse.
     */
    private val PARTICLE_PHASES = listOf(0f, 0.37f, 0.68f, 0.14f, 0.83f, 0.51f)

    /** Per-particle loop length. Mutually non-harmonic, so the field never resynchronises. */
    private val PARTICLE_PERIODS_MS = listOf(2300L, 3100L, 2700L, 3700L, 2050L, 3350L)

    /** Horizontal placement around the figure, in dp relative to centre. */
    private val PARTICLE_X_DP = listOf(-64f, 58f, -38f, 72f, 34f, -78f)

    /** Vertical placement around the figure, in dp relative to centre. */
    private val PARTICLE_Y_DP = listOf(-30f, -42f, 26f, 8f, -14f, 18f)

    /** Relative glyph size, so the field has depth rather than reading as a flat row. */
    private val PARTICLE_SCALES = listOf(1f, 0.8f, 0.65f, 0.9f, 0.55f, 0.75f)

    fun frames(mood: KaomojiMood): List<String> =
        when (mood) {
            KaomojiMood.PRAYING -> PRAYING_FRAMES
            KaomojiMood.HAPPY -> HAPPY_FRAMES
            KaomojiMood.SAD -> SAD_FRAMES
            KaomojiMood.SLEEPING -> SLEEPING_FRAMES
        }

    private fun bowCurve(mood: KaomojiMood): List<Float> =
        when (mood) {
            KaomojiMood.PRAYING -> PRAYING_BOW
            KaomojiMood.HAPPY -> HAPPY_BOW
            KaomojiMood.SAD -> SAD_BOW
            KaomojiMood.SLEEPING -> SLEEPING_BOW
        }

    fun frameDurationMillis(mood: KaomojiMood): Long =
        when (mood) {
            KaomojiMood.PRAYING -> 420L
            KaomojiMood.HAPPY -> 260L
            // A slow blink reads as dejected; a fast one reads as panic.
            KaomojiMood.SAD -> 1_400L
            KaomojiMood.SLEEPING -> 1_000L
        }

    fun frameIndexAt(
        mood: KaomojiMood,
        elapsedMillis: Long,
    ): Int {
        val count = frames(mood).size
        if (count <= 1) return 0
        val ticks = Math.floorDiv(elapsedMillis, frameDurationMillis(mood))
        return Math.floorMod(ticks, count.toLong()).toInt()
    }

    fun frameAt(
        mood: KaomojiMood,
        elapsedMillis: Long,
    ): String = frames(mood)[frameIndexAt(mood, elapsedMillis)]

    /** Bow offset for a frame, normalised to `-1..1`. Out-of-range indices wrap. */
    fun bowFactorAt(
        mood: KaomojiMood,
        frameIndex: Int,
    ): Float {
        val curve = bowCurve(mood)
        return curve[Math.floorMod(frameIndex, curve.size)]
    }

    /** Whether the halo layer participates in this mood. */
    fun hasHalo(mood: KaomojiMood): Boolean = mood != KaomojiMood.SLEEPING

    /** Whether the sparkle field participates in this mood. */
    fun hasSparkles(mood: KaomojiMood): Boolean = mood == KaomojiMood.PRAYING || mood == KaomojiMood.HAPPY

    fun particleGlyph(index: Int): String = PARTICLE_GLYPHS[wrapParticle(index)]

    fun particlePhase(index: Int): Float = PARTICLE_PHASES[wrapParticle(index)]

    fun particlePeriodMillis(index: Int): Long = PARTICLE_PERIODS_MS[wrapParticle(index)]

    fun particleOffsetXDp(index: Int): Float = PARTICLE_X_DP[wrapParticle(index)]

    fun particleOffsetYDp(index: Int): Float = PARTICLE_Y_DP[wrapParticle(index)]

    fun particleScale(index: Int): Float = PARTICLE_SCALES[wrapParticle(index)]

    /**
     * Progress of a particle through its fade-in / drift / fade-out loop,
     * in `0..1`.
     */
    fun particleProgressAt(
        index: Int,
        elapsedMillis: Long,
    ): Float {
        val period = particlePeriodMillis(index)
        val offset = Math.floorMod(elapsedMillis, period).toFloat() / period
        return (offset + particlePhase(index)) % 1f
    }

    private fun wrapParticle(index: Int): Int = Math.floorMod(index, PARTICLE_GLYPHS.size)
}
