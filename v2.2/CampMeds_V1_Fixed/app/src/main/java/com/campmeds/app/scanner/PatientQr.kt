package com.campmeds.app.scanner

/**
 * Patient QR payload. It carries the patient's stable database id (a UUID that never changes, even if
 * the patient is renamed) behind a prefix, so it can never be confused with a medication label, whose
 * payload is the bare Medication.qrCode UUID (those printed labels keep working unchanged).
 */
object PatientQr {
    const val PREFIX = "campmeds:patient:"

    fun encode(patientId: String): String = PREFIX + patientId

    /** Returns the patient id, or null if [scanned] is not a patient QR payload. */
    fun parse(scanned: String): String? {
        val text = scanned.trim()
        if (!text.startsWith(PREFIX)) return null
        return text.removePrefix(PREFIX).trim().ifEmpty { null }
    }
}
