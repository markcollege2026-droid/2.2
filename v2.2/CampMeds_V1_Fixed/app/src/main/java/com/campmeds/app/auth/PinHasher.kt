package com.campmeds.app.auth

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/**
 * Local PIN hashing (spec: "local PIN login, not a network account"). PINs are never stored or
 * compared in plaintext — each user gets a random per-user salt, hashed with PBKDF2-style
 * repeated SHA-256 stretching to slow down brute force on a lost/stolen device.
 */
object PinHasher {

    private const val ITERATIONS = 20_000

    fun newSalt(): String {
        val bytes = ByteArray(16)
        SecureRandom().nextBytes(bytes)
        return Base64.getEncoder().encodeToString(bytes)
    }

    fun hash(pin: String, saltBase64: String): String {
        val salt = Base64.getDecoder().decode(saltBase64)
        var digest = MessageDigest.getInstance("SHA-256").apply {
            update(salt)
            update(pin.toByteArray(Charsets.UTF_8))
        }.digest()

        repeat(ITERATIONS - 1) {
            digest = MessageDigest.getInstance("SHA-256").digest(digest)
        }
        return Base64.getEncoder().encodeToString(digest)
    }

    fun verify(pin: String, saltBase64: String, expectedHash: String): Boolean =
        hash(pin, saltBase64) == expectedHash
}
