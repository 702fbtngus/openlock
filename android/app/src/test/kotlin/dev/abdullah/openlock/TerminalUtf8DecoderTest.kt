package dev.abdullah.openlock

import org.junit.Assert.assertEquals
import org.junit.Test

class TerminalUtf8DecoderTest {
    @Test fun decodesKoreanAndEmojiAcrossEveryByteBoundary() {
        val text = "shell 출력 확인 🧪\n"
        val bytes = text.toByteArray(Charsets.UTF_8)
        for (boundary in 0..bytes.size) {
            val decoder = TerminalUtf8Decoder()
            val first = decoder.decode(bytes.copyOfRange(0, boundary), boundary)
            val second = bytes.copyOfRange(boundary, bytes.size)
            assertEquals(text, first + decoder.decode(second, second.size, true))
        }
    }

    @Test fun preservesPartialCharactersWhenReadingOneByteAtATime() {
        val text = "한글 🙂"
        val decoder = TerminalUtf8Decoder()
        val result = buildString {
            for (byte in text.toByteArray(Charsets.UTF_8)) {
                append(decoder.decode(byteArrayOf(byte), 1))
            }
            append(decoder.decode(byteArrayOf(), 0, true))
        }
        assertEquals(text, result)
    }

    @Test fun replacesMalformedOrUnfinishedUtf8AtEndOfOutput() {
        val decoder = TerminalUtf8Decoder()
        assertEquals("\uFFFD", decoder.decode(byteArrayOf(0xED.toByte()), 1, true))
    }
}
