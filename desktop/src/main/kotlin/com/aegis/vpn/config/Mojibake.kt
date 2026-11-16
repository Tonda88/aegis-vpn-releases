package com.aegis.vpn.config

import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

/**
 * Repairs text that was decoded with the wrong charset (typical "кракозябры"):
 * UTF-8 bytes read as windows-1251, CP866 or ISO-8859-1.
 */
object Mojibake {
    private val charsets = listOf(
        Charset.forName("windows-1251"),
        Charset.forName("IBM866"),
        StandardCharsets.ISO_8859_1
    )

    private val markers = listOf(
        "Ð", "Ñ", "â€", "ï¿½",
        "Рџ", "СЂ", "рџ", "Р’", "СЃ", "С‚",
        "╨", "╤", "╬", "╧", "тЖТ", "\uFFFD"
    )

    fun repair(value: String): String {
        if (value.isBlank()) return value
        var best = value
        var bestScore = score(value)
        if (bestScore == 0) return value

        for (charset in charsets) {
            val candidate = transcode(value, charset) ?: continue
            if (candidate.isBlank() || candidate.contains('\uFFFD')) continue
            val candidateScore = score(candidate)
            if (candidateScore < bestScore) {
                best = candidate
                bestScore = candidateScore
            }
        }
        return best
    }

    private fun transcode(value: String, charset: Charset): String? = runCatching {
        val encoder = charset.newEncoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        val encoded = encoder.encode(CharBuffer.wrap(value))
        val bytes = ByteArray(encoded.remaining()).also { encoded.get(it) }

        val decoder = StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        decoder.decode(ByteBuffer.wrap(bytes)).toString()
    }.getOrNull()

    private fun score(value: String): Int {
        var result = 0
        for (marker in markers) {
            var index = value.indexOf(marker)
            while (index >= 0) {
                result += 10
                index = value.indexOf(marker, index + marker.length)
            }
        }
        result += value.count { it.code in 0x80..0x9F } * 3
        return result
    }
}
