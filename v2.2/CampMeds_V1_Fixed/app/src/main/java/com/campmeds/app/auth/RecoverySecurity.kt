package com.campmeds.app.auth

import java.security.MessageDigest
import java.text.Normalizer
import java.util.Base64
import java.util.Locale
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/** Answers use independent random salts and PBKDF2-HMAC-SHA256; only hashes enter the encrypted DB.
 * Questions are knowledge-based, not a substitute for device security. Choose private, unpredictable answers.
 */
object RecoverySecurity {
    val questions = linkedMapOf(
        "childhood_friend" to "What was the nickname of a childhood friend?",
        "first_trip" to "Where did you take your first memorable trip?",
        "family_phrase" to "What private phrase does your family use?",
        "favorite_teacher" to "What was the surname of a memorable teacher?",
        "first_pet" to "What was the name of your first pet?",
        "childhood_place" to "What was your favorite childhood place?",
        "private_memory" to "What word reminds you of a private happy memory?",
        "first_job" to "What was the name of your first workplace?"
    )
    fun normalize(answer: String): String = Normalizer.normalize(answer, Normalizer.Form.NFKC)
        .trim().lowercase(Locale.ROOT).replace(Regex("\\s+"), " ")
    fun validate(answers: Map<String, String>) {
        require(answers.size == 3 && answers.keys.all { it in questions }) { "Choose three different questions" }
        require(answers.values.all { normalize(it).length in 4..200 }) { "Use answers of 4–200 characters" }
    }
    fun hash(answer: String, salt: String): String {
        val spec = PBEKeySpec(normalize(answer).toCharArray(), Base64.getDecoder().decode(salt), 600_000, 256)
        return try { Base64.getEncoder().encodeToString(SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded) }
        finally { spec.clearPassword() }
    }
    fun verify(answer: String, salt: String, expected: String): Boolean = MessageDigest.isEqual(
        Base64.getDecoder().decode(hash(answer, salt)), Base64.getDecoder().decode(expected))
}
