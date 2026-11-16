package com.aegis.vpn.config

import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets

/** Small dependency-free decoder which accepts both standard and URL-safe Base64. */
object Base64Codec {
    private const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"

    fun decodeToText(value: String): String = String(decode(value), StandardCharsets.UTF_8)

    fun decode(value: String): ByteArray {
        val clean = value.trim()
            .replace('-', '+')
            .replace('_', '/')
            .filterNot { it.isWhitespace() || it == '=' }
        if (clean.isEmpty()) return ByteArray(0)

        val output = ByteArrayOutputStream(clean.length * 3 / 4 + 3)
        var buffer = 0
        var bits = 0
        for (char in clean) {
            val index = ALPHABET.indexOf(char)
            if (index < 0) throw IllegalArgumentException("Некорректная Base64-строка")
            buffer = (buffer shl 6) or index
            bits += 6
            if (bits >= 8) {
                bits -= 8
                output.write((buffer shr bits) and 0xFF)
            }
        }
        return output.toByteArray()
    }
}
