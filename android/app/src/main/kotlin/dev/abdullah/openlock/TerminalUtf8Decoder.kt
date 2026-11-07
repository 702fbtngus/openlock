package dev.abdullah.openlock

import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction

/** Retains partial UTF-8 sequences between nonblocking pipe reads. */
class TerminalUtf8Decoder {
    private val decoder = Charsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPLACE)
        .onUnmappableCharacter(CodingErrorAction.REPLACE)
    private val bytes = ByteBuffer.allocate(8192)
    private val characters = CharBuffer.allocate(8192)

    fun decode(chunk: ByteArray, size: Int, endOfInput: Boolean = false): String {
        bytes.put(chunk, 0, size)
        bytes.flip()
        decoder.decode(bytes, characters, endOfInput)
        bytes.compact()
        if (endOfInput) decoder.flush(characters)
        characters.flip()
        val text = characters.toString()
        characters.clear()
        return text
    }
}
