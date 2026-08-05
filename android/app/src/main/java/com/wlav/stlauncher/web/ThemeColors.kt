package com.wlav.stlauncher.web

import kotlin.math.roundToInt

/**
 * Parses the CSS colour SillyTavern reports through the `theme.set` bridge
 * message.
 *
 * Implemented without `android.graphics.Color` so the parsing rules are covered
 * by ordinary JVM unit tests rather than needing an emulator or Robolectric.
 *
 * Hex forms follow CSS ordering — `#RGBA` and `#RRGGBBAA` put alpha last — since
 * every value reaching this function originates in a stylesheet.
 */
object ThemeColors {
    private val FUNCTIONAL_PATTERN = Regex("""^rgba?\(([^()]*)\)$""", RegexOption.IGNORE_CASE)
    private val SEPARATORS = Regex("""[,/\s]+""")

    /** Returns an ARGB colour int, or `null` if the value is not understood. */
    fun parse(value: String?): Int? {
        val trimmed = value?.trim().orEmpty()
        return when {
            trimmed.isEmpty() -> null
            trimmed.startsWith('#') -> parseHex(trimmed.substring(1))
            else -> parseFunctional(trimmed)
        }
    }

    private fun parseHex(digits: String): Int? {
        if (digits.isEmpty() || !digits.all { it.isHexDigit() }) return null
        return when (digits.length) {
            3 -> compose(255, digits.nibble(0), digits.nibble(1), digits.nibble(2))
            4 -> compose(digits.nibble(3), digits.nibble(0), digits.nibble(1), digits.nibble(2))
            6 -> compose(255, digits.byteAt(0), digits.byteAt(2), digits.byteAt(4))
            8 -> compose(digits.byteAt(6), digits.byteAt(0), digits.byteAt(2), digits.byteAt(4))
            else -> null
        }
    }

    private fun parseFunctional(value: String): Int? {
        val body = FUNCTIONAL_PATTERN.matchEntire(value)?.groupValues?.get(1) ?: return null
        val parts = body.trim().split(SEPARATORS).filter(String::isNotEmpty)
        if (parts.size !in 3..4) return null
        val red = parseChannel(parts[0]) ?: return null
        val green = parseChannel(parts[1]) ?: return null
        val blue = parseChannel(parts[2]) ?: return null
        val alpha = parts.getOrNull(3)?.let { parseAlpha(it) ?: return null } ?: 255
        return compose(alpha, red, green, blue)
    }

    private fun parseChannel(value: String): Int? =
        if (value.endsWith('%')) {
            value.dropLast(1).toFloatOrNull()?.let { scaleToByte(it / 100f) }
        } else {
            value.toFloatOrNull()?.let { it.roundToInt().coerceIn(0, 255) }
        }

    private fun parseAlpha(value: String): Int? =
        if (value.endsWith('%')) {
            value.dropLast(1).toFloatOrNull()?.let { scaleToByte(it / 100f) }
        } else {
            value.toFloatOrNull()?.let(::scaleToByte)
        }

    private fun scaleToByte(fraction: Float): Int = (fraction * 255f).roundToInt().coerceIn(0, 255)

    private fun compose(
        alpha: Int,
        red: Int,
        green: Int,
        blue: Int,
    ): Int = (alpha shl 24) or (red shl 16) or (green shl 8) or blue

    private fun Char.isHexDigit(): Boolean = this in '0'..'9' || this in 'a'..'f' || this in 'A'..'F'

    /** Expands a single hex digit to a full byte, so `f` becomes `0xFF`. */
    private fun String.nibble(index: Int): Int = Character.digit(this[index], 16).let { it * 16 + it }

    private fun String.byteAt(index: Int): Int = Character.digit(this[index], 16) * 16 + Character.digit(this[index + 1], 16)
}
