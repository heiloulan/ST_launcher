package com.wlav.stlauncher.ui

import android.animation.ArgbEvaluator
import android.animation.ValueAnimator
import android.os.SystemClock
import android.provider.Settings
import android.text.SpannableString
import android.text.Spanned
import android.text.TextPaint
import android.text.style.CharacterStyle
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.animation.LinearInterpolator
import android.widget.FrameLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import androidx.core.view.isVisible
import com.wlav.stlauncher.R
import kotlin.math.PI
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * The loading-screen animation rig.
 *
 * Four layers move independently — a breathing halo, a drifting sparkle field,
 * the kaomoji itself, and a shimmer across the title. Each layer runs on its own
 * period, and the periods are mutually non-harmonic, so the composition never
 * settles into a single visible beat.
 *
 * All four are driven from one ticker. That is deliberate: the layers are pure
 * functions of elapsed time, so a shared clock costs nothing in independence
 * while avoiding four animators to keep in sync across lifecycle transitions.
 */
class PrayerAnimation(
    private val halo: View,
    private val particleLayer: ViewGroup,
    private val kaomoji: TextView,
    private val title: TextView,
) {
    private val context = kaomoji.context
    private val density = context.resources.displayMetrics.density
    private val accentColor = ContextCompat.getColor(context, R.color.accent)
    private val accentSoftColor = ContextCompat.getColor(context, R.color.accent_soft)
    private val sparkleColor = ContextCompat.getColor(context, R.color.sparkle)
    private val titleColor = ContextCompat.getColor(context, R.color.text_primary)
    private val colorEvaluator = ArgbEvaluator()
    private val particles = createParticles()
    private val titleSpans = mutableListOf<AlphaSpan>()

    private var ticker: ValueAnimator? = null
    private var mood = KaomojiMood.PRAYING
    private var elapsedMillis = 0L
    private var lastTickAt = 0L
    private var renderedFrameIndex = -1
    private var running = false
    private var boundTitle: CharSequence? = null

    /**
     * Honours the system "remove animations" setting. Read on demand rather
     * than cached, so toggling accessibility settings takes effect on the next
     * start without recreating the Activity.
     */
    private val reducedMotion: Boolean
        get() =
            Settings.Global.getFloat(
                context.contentResolver,
                Settings.Global.ANIMATOR_DURATION_SCALE,
                1f,
            ) == 0f

    fun setMood(next: KaomojiMood) {
        if (mood == next) return
        mood = next
        renderedFrameIndex = -1
        applyLayerVisibility()
        if (!running) renderResting()
    }

    /**
     * Installs the title text, optionally with the per-character shimmer.
     *
     * The shimmer is an alpha wave over spans rather than a text substitution,
     * so 「少女祈祷中……」 stays literally correct on every frame.
     */
    fun bindTitle(
        text: CharSequence,
        shimmer: Boolean,
    ) {
        // Progress updates re-render the panel many times a second; rebuilding
        // the spans each time would restart the wave and churn allocations.
        if (boundTitle == text) return
        boundTitle = text
        titleSpans.clear()
        if (!shimmer || reducedMotion || text.isEmpty()) {
            title.text = text
            return
        }
        val spannable = SpannableString(text)
        text.indices.forEach { index ->
            val span = AlphaSpan(titleColor)
            titleSpans += span
            spannable.setSpan(span, index, index + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        title.text = spannable
    }

    fun start() {
        if (running) return
        running = true
        if (reducedMotion) {
            renderResting()
            return
        }
        applyLayerVisibility()
        lastTickAt = SystemClock.uptimeMillis()
        ticker =
            ValueAnimator.ofFloat(0f, 1f).apply {
                duration = TICKER_CYCLE_MS
                repeatCount = ValueAnimator.INFINITE
                interpolator = LinearInterpolator()
                addUpdateListener { onTick() }
                start()
            }
    }

    fun stop() {
        if (!running) return
        running = false
        ticker?.cancel()
        ticker = null
    }

    private fun onTick() {
        val now = SystemClock.uptimeMillis()
        // Clamp the delta so a stalled frame does not teleport the animation.
        elapsedMillis += (now - lastTickAt).coerceIn(0L, MAXIMUM_TICK_DELTA_MS)
        lastTickAt = now
        render(elapsedMillis)
    }

    private fun render(elapsed: Long) {
        renderKaomoji(elapsed)
        renderHalo(elapsed)
        renderParticles(elapsed)
        renderTitle(elapsed)
    }

    private fun renderKaomoji(elapsed: Long) {
        val frameIndex = KaomojiFrames.frameIndexAt(mood, elapsed)
        if (frameIndex != renderedFrameIndex) {
            kaomoji.text = KaomojiFrames.frames(mood)[frameIndex]
            renderedFrameIndex = frameIndex
        }

        // The face snaps between frames while the body eases between their bow
        // offsets, so the two read as one gesture instead of a slideshow.
        val frameDuration = KaomojiFrames.frameDurationMillis(mood)
        val withinFrame = Math.floorMod(elapsed, frameDuration).toFloat() / frameDuration
        val bow =
            lerp(
                KaomojiFrames.bowFactorAt(mood, frameIndex),
                KaomojiFrames.bowFactorAt(mood, frameIndex + 1),
                smoothStep(withinFrame),
            )

        val bob = wave(elapsed, BOB_PERIOD_MS) * BOB_TRAVEL_DP
        val sway = wave(elapsed, SWAY_PERIOD_MS) * SWAY_DEGREES
        val breath = 1f + wave(elapsed, BREATH_PERIOD_MS) * BREATH_AMPLITUDE
        val shimmer = (wave(elapsed, SHIMMER_PERIOD_MS) + 1f) / 2f

        kaomoji.translationY = (bob + bow * BOW_TRAVEL_DP) * density
        kaomoji.rotation = sway
        kaomoji.scaleX = breath
        kaomoji.scaleY = breath
        kaomoji.setTextColor(
            colorEvaluator.evaluate(shimmer, accentColor, accentSoftColor) as Int,
        )
    }

    private fun renderHalo(elapsed: Long) {
        if (!halo.isVisible) return
        val pulse = (wave(elapsed, HALO_PERIOD_MS) + 1f) / 2f
        val dim = if (mood == KaomojiMood.SAD) SAD_HALO_DIM else 1f
        halo.alpha = (HALO_MINIMUM_ALPHA + pulse * HALO_ALPHA_SWING) * dim
        // Offset the scale from the alpha so the glow swells slightly ahead of
        // its brightest point rather than pumping in lockstep.
        val scale = HALO_BASE_SCALE + wave(elapsed + HALO_SCALE_LEAD_MS, HALO_PERIOD_MS) * HALO_SCALE_SWING
        halo.scaleX = scale
        halo.scaleY = scale
    }

    private fun renderParticles(elapsed: Long) {
        particles.forEachIndexed { index, particle ->
            if (!particle.isVisible) return@forEachIndexed
            val progress = KaomojiFrames.particleProgressAt(index, elapsed)
            // sin over half a turn: invisible at both ends of the drift, full
            // brightness in the middle.
            val fade = sin(PI * progress).toFloat()
            particle.alpha = fade
            particle.translationY =
                (KaomojiFrames.particleOffsetYDp(index) - progress * PARTICLE_DRIFT_DP) * density
            val pop = PARTICLE_MINIMUM_SCALE + fade * (1f - PARTICLE_MINIMUM_SCALE)
            particle.scaleX = pop
            particle.scaleY = pop
        }
    }

    private fun renderTitle(elapsed: Long) {
        if (titleSpans.isEmpty()) return
        val lastIndex = (titleSpans.size - 1).coerceAtLeast(1)
        titleSpans.forEachIndexed { index, span ->
            // Amplitude grows quadratically toward the end of the line, so the
            // trailing 「……」 carries the motion and the phrase stays legible.
            val depth = index.toFloat() / lastIndex
            val amplitude = TITLE_BASE_SWING + TITLE_TAIL_SWING * depth * depth
            val phase = index * TITLE_PHASE_STEP
            val brightness = 1f - amplitude + amplitude * (wave(elapsed, TITLE_PERIOD_MS, phase) + 1f) / 2f
            span.alpha = (brightness * 255f).roundToInt().coerceIn(0, 255)
        }
        title.invalidate()
    }

    private fun renderResting() {
        renderedFrameIndex = 0
        kaomoji.text = KaomojiFrames.frames(mood).first()
        kaomoji.translationY = 0f
        kaomoji.rotation = 0f
        kaomoji.scaleX = 1f
        kaomoji.scaleY = 1f
        kaomoji.setTextColor(accentColor)
        halo.isVisible = false
        particles.forEach { it.isVisible = false }
    }

    private fun applyLayerVisibility() {
        if (reducedMotion) {
            renderResting()
            return
        }
        halo.isVisible = KaomojiFrames.hasHalo(mood)
        val sparkling = KaomojiFrames.hasSparkles(mood)
        particles.forEach { it.isVisible = sparkling }
    }

    private fun createParticles(): List<TextView> =
        List(KaomojiFrames.PARTICLE_COUNT) { index ->
            TextView(context)
                .apply {
                    text = KaomojiFrames.particleGlyph(index)
                    setTextColor(sparkleColor)
                    setTextSize(
                        TypedValue.COMPLEX_UNIT_SP,
                        PARTICLE_BASE_SIZE_SP * KaomojiFrames.particleScale(index),
                    )
                    includeFontPadding = false
                    alpha = 0f
                    translationX = KaomojiFrames.particleOffsetXDp(index) * density
                    translationY = KaomojiFrames.particleOffsetYDp(index) * density
                    layoutParams =
                        FrameLayout.LayoutParams(
                            ViewGroup.LayoutParams.WRAP_CONTENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT,
                            Gravity.CENTER,
                        )
                }.also(particleLayer::addView)
        }

    /** A sine in `-1..1` with the given period, optionally phase-shifted. */
    private fun wave(
        elapsed: Long,
        periodMillis: Long,
        phase: Float = 0f,
    ): Float = sin(TWO_PI * elapsed / periodMillis + phase).toFloat()

    private fun lerp(
        from: Float,
        to: Float,
        fraction: Float,
    ): Float = from + (to - from) * fraction

    private fun smoothStep(value: Float): Float {
        val clamped = value.coerceIn(0f, 1f)
        return clamped * clamped * (3f - 2f * clamped)
    }

    /**
     * A colour span whose alpha can be mutated in place. `TextView` re-runs
     * [updateDrawState] for every [CharacterStyle] on each draw, so an
     * `invalidate()` is enough to animate it — no relayout, no span churn.
     */
    private class AlphaSpan(
        private val baseColor: Int,
    ) : CharacterStyle() {
        var alpha: Int = 255

        override fun updateDrawState(tp: TextPaint) {
            tp.color = ColorUtils.setAlphaComponent(baseColor, alpha)
        }
    }

    private companion object {
        const val TWO_PI = 2.0 * PI
        const val TICKER_CYCLE_MS = 1_000L
        const val MAXIMUM_TICK_DELTA_MS = 100L

        const val BOB_PERIOD_MS = 1_600L
        const val BOB_TRAVEL_DP = 5f
        const val BOW_TRAVEL_DP = 10f
        const val SWAY_PERIOD_MS = 2_300L
        const val SWAY_DEGREES = 2.5f
        const val BREATH_PERIOD_MS = 1_900L
        const val BREATH_AMPLITUDE = 0.03f
        const val SHIMMER_PERIOD_MS = 3_100L

        const val HALO_PERIOD_MS = 2_600L
        const val HALO_SCALE_LEAD_MS = 430L
        const val HALO_MINIMUM_ALPHA = 0.25f
        const val HALO_ALPHA_SWING = 0.30f
        const val HALO_BASE_SCALE = 0.99f
        const val HALO_SCALE_SWING = 0.07f
        const val SAD_HALO_DIM = 0.35f

        const val PARTICLE_BASE_SIZE_SP = 16f
        const val PARTICLE_DRIFT_DP = 14f
        const val PARTICLE_MINIMUM_SCALE = 0.7f

        const val TITLE_PERIOD_MS = 2_100L
        const val TITLE_PHASE_STEP = 0.55f
        const val TITLE_BASE_SWING = 0.12f
        const val TITLE_TAIL_SWING = 0.5f
    }
}
