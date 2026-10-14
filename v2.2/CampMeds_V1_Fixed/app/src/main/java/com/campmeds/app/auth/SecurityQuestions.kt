package com.campmeds.app.auth

import java.security.MessageDigest
import java.text.Normalizer
import java.util.Base64
import java.util.Locale
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

object SecurityQuestions {
    val available = linkedMapOf(
        "teacher" to "What was the surname of a memorable teacher?",
        "place" to "What place did you visit on a memorable childhood trip?",
        "book" to "What was a favorite book as a child?",
        "friend" to "What was the first name of a childhood friend?",
        "meal" to "What was a favorite meal growing up?",
        "street" to "What street did you live on as a child?",
        "hobby" to "What was your first hobby?",
        "pet" to "What was the name of your first pet?"
    )
    fun normalize(answer: String): String = Normalizer.normalize(answer, Normalizer.Form.NFKC)
        .trim().lowercase(Locale.ROOT).replace(Regex("\\s+"), " ")
    fun hash(answer: String, salt: String, question: String): String {
        val spec = PBEKeySpec(normalize(answer).toCharArray(),
            Base64.getDecoder().decode(salt) + question.toByteArray(Charsets.UTF_8), 210_000, 256)
        return try { Base64.getEncoder().encodeToString(SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded) }
        finally { spec.clearPassword() }
    }
    fun verify(answer: String, salt: String, question: String, expected: String): Boolean =
        MessageDigest.isEqual(hash(answer, salt, question).toByteArray(), expected.toByteArray())
    fun validPin(pin: String) = pin.length in 4..8 && pin.all { it in '0'..'9' }
}
