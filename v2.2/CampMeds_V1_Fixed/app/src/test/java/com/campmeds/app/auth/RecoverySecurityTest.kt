package com.campmeds.app.auth

import org.junit.Assert.*
import org.junit.Test

class RecoverySecurityTest {
    @Test fun saltedHashesNormalizeAndRejectWrongAnswers() {
        val salt1 = PinHasher.newSalt()
        val salt2 = PinHasher.newSalt()
        val hash = RecoverySecurity.hash("  Private   Memory  ", salt1)
        assertNotEquals("Private Memory", hash)
        assertNotEquals(hash, RecoverySecurity.hash("private memory", salt2))
        assertTrue(RecoverySecurity.verify("PRIVATE memory", salt1, hash))
        assertFalse(RecoverySecurity.verify("incorrect", salt1, hash))
    }
    @Test(expected = IllegalArgumentException::class) fun rejectsUnknownQuestion() {
        RecoverySecurity.validate(mapOf("unknown" to "answer", "first_pet" to "answer", "first_job" to "answer"))
    }
    @Test(expected = IllegalArgumentException::class) fun requiresThreeQuestions() {
        RecoverySecurity.validate(mapOf("first_pet" to "answer"))
    }
}
