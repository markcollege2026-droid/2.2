package com.campmeds.app.auth

import org.junit.Assert.*
import org.junit.Test

class SecurityQuestionsTest {
    @Test fun saltedStretchedAnswersAndNormalization() {
        val salt = PinHasher.newSalt()
        val hash = SecurityQuestions.hash("  Maple   Street ", salt, "street")
        assertNotEquals("maple street", hash)
        assertTrue(SecurityQuestions.verify("MAPLE STREET", salt, "street", hash))
        assertFalse(SecurityQuestions.verify("wrong", salt, "street", hash))
        assertNotEquals(hash, SecurityQuestions.hash("maple street", PinHasher.newSalt(), "street"))
        assertNotEquals(hash, SecurityQuestions.hash("maple street", salt, "pet"))
    }
    @Test fun validRecoveryPin() {
        assertTrue(SecurityQuestions.validPin("1234"))
        assertFalse(SecurityQuestions.validPin("123"))
        assertFalse(SecurityQuestions.validPin("abcd"))
    }
}
