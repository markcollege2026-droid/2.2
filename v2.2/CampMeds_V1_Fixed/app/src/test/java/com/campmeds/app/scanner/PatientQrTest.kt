package com.campmeds.app.scanner

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PatientQrTest {
    @Test fun roundTripsTheStablePatientId() {
        val id = "3f2b8c1e-aaaa-bbbb-cccc-1234567890ab"
        assertEquals(id, PatientQr.parse(PatientQr.encode(id)))
    }
    @Test fun medicationLabelsAndJunkAreNotPatientCodes() {
        assertNull(PatientQr.parse("3f2b8c1e-aaaa-bbbb-cccc-1234567890ab")) // bare medication QR payload
        assertNull(PatientQr.parse("campmeds:patient:"))
        assertNull(PatientQr.parse("hello"))
    }
}
