package com.wlav.stlauncher.web

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class ThemeColorsTest {
    @Test
    fun `parses six digit hex`() {
        assertEquals(0xFFE94560.toInt(), ThemeColors.parse("#E94560"))
        assertEquals(0xFF000000.toInt(), ThemeColors.parse("#000000"))
        assertEquals(0xFFFFFFFF.toInt(), ThemeColors.parse("#ffffff"))
    }

    @Test
    fun `parses shorthand hex by doubling each digit`() {
        assertEquals(0xFFFFFFFF.toInt(), ThemeColors.parse("#fff"))
        assertEquals(0xFF112233.toInt(), ThemeColors.parse("#123"))
    }

    @Test
    fun `hex alpha follows CSS ordering`() {
        // CSS puts alpha last, and every value reaching this parser is CSS.
        assertEquals(0x80E94560.toInt(), ThemeColors.parse("#E9456080"))
        assertEquals(0x88FF0000.toInt(), ThemeColors.parse("#f008"))
    }

    @Test
    fun `parses rgb with assorted separators`() {
        val expected = 0xFF102030.toInt()
        assertEquals(expected, ThemeColors.parse("rgb(16, 32, 48)"))
        assertEquals(expected, ThemeColors.parse("rgb(16 32 48)"))
        assertEquals(expected, ThemeColors.parse("RGB(16,32,48)"))
        assertEquals(expected, ThemeColors.parse("  rgb(16,32,48)  "))
    }

    @Test
    fun `parses rgba with a fractional alpha`() {
        assertEquals(0x80102030.toInt(), ThemeColors.parse("rgba(16, 32, 48, 0.5)"))
        assertEquals(0xFF102030.toInt(), ThemeColors.parse("rgba(16, 32, 48, 1)"))
        assertEquals(0x00102030, ThemeColors.parse("rgba(16, 32, 48, 0)"))
    }

    @Test
    fun `a fractional alpha is not mistaken for two channels`() {
        // The previous implementation scraped digits with a regex, so "0.5"
        // contributed a "0" and a "5" to the channel list.
        assertEquals(0x80FF0000.toInt(), ThemeColors.parse("rgba(255, 0, 0, 0.5)"))
    }

    @Test
    fun `parses percentage channels`() {
        assertEquals(0xFFFF0000.toInt(), ThemeColors.parse("rgb(100%, 0%, 0%)"))
        assertEquals(0x80FF0000.toInt(), ThemeColors.parse("rgba(100%, 0%, 0%, 50%)"))
    }

    @Test
    fun `clamps out-of-range channels`() {
        assertEquals(0xFFFF00FF.toInt(), ThemeColors.parse("rgb(300, -20, 255)"))
        assertEquals(0xFF102030.toInt(), ThemeColors.parse("rgba(16, 32, 48, 4)"))
    }

    @Test
    fun `rejects malformed input`() {
        listOf(
            null,
            "",
            "   ",
            "#",
            "#12",
            "#12345",
            "#gggggg",
            "rgb(16, 32)",
            "rgb(16, 32, 48, 0.5, 7)",
            "rgb(16, thirty, 48)",
            "rgb 16 32 48",
            "hsl(120, 50%, 50%)",
            "red",
            "rgb(16, 32, 48",
        ).forEach { value ->
            assertNull(ThemeColors.parse(value), "expected null for <$value>")
        }
    }
}
