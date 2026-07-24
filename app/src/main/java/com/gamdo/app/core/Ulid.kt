package com.gamdo.app.core

import java.security.SecureRandom

/**
 * Minimal ULID generator — 26-char Crockford base32, lexicographically sortable
 * (48-bit timestamp + 80-bit randomness). Used for row ids like `cap_<ULID>`.
 */
object Ulid {
    private const val ENCODING = "0123456789ABCDEFGHJKMNPQRSTVWXYZ" // Crockford base32
    private val random = SecureRandom()

    fun generate(time: Long = System.currentTimeMillis()): String {
        val chars = CharArray(26)
        var t = time
        // 10 chars of timestamp (most significant first)
        for (i in 9 downTo 0) {
            chars[i] = ENCODING[(t and 0x1F).toInt()]
            t = t shr 5
        }
        // 16 chars of randomness
        for (i in 10 until 26) {
            chars[i] = ENCODING[random.nextInt(32)]
        }
        return String(chars)
    }
}
