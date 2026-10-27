package com.sangiorgisrl.screenlink.terminal.emulator

/*
 * Derived from the terminal-emulator module of the Termux terminal emulator
 * (https://github.com/termux/termux-app), Copyright (c) the Termux contributors,
 * Apache License 2.0 - see the NOTICE file at the root of this repository.
 * Rewritten in Kotlin for this application.
 */

/**
 * The style word stored in every cell of a [TerminalRow].
 *
 * One 64-bit long per cell: 16 flag bits (11 of them used), then 24 bits of foreground
 * and 24 bits of background. The two truecolour flags say whether a colour field holds
 * an index into the 259-entry palette or a literal 0xRRGGBB triple, which is what lets
 * `ESC[38;2;r;g;bm` and `ESC[31m` share one representation.
 */
class TextStyle private constructor() {

    companion object {

        const val CHARACTER_ATTRIBUTE_BOLD = 1
        const val CHARACTER_ATTRIBUTE_ITALIC = 1 shl 1
        const val CHARACTER_ATTRIBUTE_UNDERLINE = 1 shl 2
        const val CHARACTER_ATTRIBUTE_BLINK = 1 shl 3
        const val CHARACTER_ATTRIBUTE_INVERSE = 1 shl 4
        const val CHARACTER_ATTRIBUTE_INVISIBLE = 1 shl 5
        const val CHARACTER_ATTRIBUTE_STRIKETHROUGH = 1 shl 6

        /**
         * Set by DECSCA for the characters that the selective erases (DECSED, DECSEL)
         * are allowed to erase.
         */
        const val CHARACTER_ATTRIBUTE_PROTECTED = 1 shl 7

        /** Dim colours, also known as faint or half intensity. */
        const val CHARACTER_ATTRIBUTE_DIM = 1 shl 8

        private const val TRUECOLOR_FOREGROUND = 1 shl 9
        private const val TRUECOLOR_BACKGROUND = 1 shl 10

        const val COLOR_INDEX_FOREGROUND = 256
        const val COLOR_INDEX_BACKGROUND = 257
        const val COLOR_INDEX_CURSOR = 258

        /** The 256 standard palette entries plus the three special ones. */
        const val NUM_INDEXED_COLORS = 259

        /** Normal foreground and background colours, no effects. */
        @JvmField
        val NORMAL: Long = encode(COLOR_INDEX_FOREGROUND, COLOR_INDEX_BACKGROUND, 0)

        /** Only the nine low effect bits may be packed; bits 9 and 10 are the truecolour flags. */
        private const val EFFECT_BITS = 0b111111111
        private const val INDEX_BITS = 0b111111111L
        private const val RGB_BITS = 0x00FFFFFFL
        private const val OPAQUE_RGB = 0xFF000000.toInt()

        /** Pack a foreground, a background and the effect flags into one style word. */
        @JvmStatic
        fun encode(foreColor: Int, backColor: Int, effect: Int): Long {
            var style = (effect and EFFECT_BITS).toLong()
            if (foreColor and OPAQUE_RGB == OPAQUE_RGB) {
                style = style or (TRUECOLOR_FOREGROUND.toLong() or ((foreColor.toLong() and RGB_BITS) shl 40))
            } else {
                style = style or ((foreColor.toLong() and INDEX_BITS) shl 40)
            }
            if (backColor and OPAQUE_RGB == OPAQUE_RGB) {
                style = style or (TRUECOLOR_BACKGROUND.toLong() or ((backColor.toLong() and RGB_BITS) shl 16))
            } else {
                style = style or ((backColor.toLong() and INDEX_BITS) shl 16)
            }
            return style
        }

        @JvmStatic
        fun decodeForeColor(style: Long): Int =
            if (style and TRUECOLOR_FOREGROUND.toLong() == 0L) {
                ((style ushr 40) and INDEX_BITS).toInt()
            } else {
                OPAQUE_RGB or ((style ushr 40) and RGB_BITS).toInt()
            }

        @JvmStatic
        fun decodeBackColor(style: Long): Int =
            if (style and TRUECOLOR_BACKGROUND.toLong() == 0L) {
                ((style ushr 16) and INDEX_BITS).toInt()
            } else {
                OPAQUE_RGB or ((style ushr 16) and RGB_BITS).toInt()
            }

        /** The effect flags, truecolour bits included, so 11 bits come back. */
        @JvmStatic
        fun decodeEffect(style: Long): Int = (style and 0b11111111111L).toInt()
    }
}
