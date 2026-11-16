package com.aegis.vpn.config

import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

/**
 * Repairs text that was decoded with the wrong charset (typical "кракозябры"):
 * UTF-8 bytes read as windows-1251, CP866 or ISO-8859-1.
 *
 * Names coming from subscription panels are often double-decoded: the UTF-8
 * bytes of "🇫🇷 Франция" read as CP1251. Re-encoding every character back to its
 * CP1251 byte and decoding the sequence as UTF-8 restores the Russian text.
 * Bytes that no longer have a source (emoji flags) are dropped instead of
 * aborting the whole repair, so "[flag] Франция" becomes "Франция".
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

        for (charset in charsets) {
            val candidate = transcode(value, charset) ?: continue
            val cleaned = clean(candidate)
            if (cleaned.isBlank()) continue
            val cleanedScore = score(cleaned)
            if (cleanedScore < bestScore) {
                best = cleaned
                bestScore = cleanedScore
            }
        }
        val repaired = clean(best).trim()
        return if (repaired.isNotBlank()) repaired else value
    }

    /**
     * Maps the string back to bytes through [charset], dropping characters that
     * have no single-byte representation, then decodes the bytes as UTF-8.
     */
    private fun transcode(value: String, charset: Charset): String? = runCatching {
        val encoder = charset.newEncoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        val out = ArrayList<Byte>(value.length)
        var i = 0
        while (i < value.length) {
            val cp = value.codePointAt(i)
            i += Character.charCount(cp)
            val single = String(Character.toChars(cp))
            val encoded = runCatching {
                val buf = encoder.encode(CharBuffer.wrap(single))
                ByteArray(buf.remaining()).also { buf.get(it) }
            }.getOrNull()
            if (encoded != null && encoded.size == 1) {
                out.add(encoded[0])
            } else if (cp < 0x80) {
                out.add(cp.toByte())
            }
        }
        String(out.toByteArray(), StandardCharsets.UTF_8.also { })
    }.getOrNull()

    private fun clean(value: String): String {
        val out = StringBuilder(value.length)
        var i = 0
        while (i < value.length) {
            val c = value[i]
            when {
                Character.isHighSurrogate(c) -> {
                    if (i + 1 < value.length && Character.isLowSurrogate(value[i + 1])) {
                        out.append(c).append(value[i + 1])
                        i += 2
                        continue
                    }
                    i++
                }
                Character.isLowSurrogate(c) -> i++
                c == '\uFFFD' -> i++
                else -> {
                    out.append(c)
                    i++
                }
            }
        }
        return out.toString()
            .replace(Regex("[\\uFE00-\\uFE0F]"), "")
            .replace(Regex("\\s{2,}"), " ")
            .replace(Regex("^[\\s·•\\-–—]+"), "")
            .replace(Regex("[\\s·•\\-–—]+$"), "")
    }

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
