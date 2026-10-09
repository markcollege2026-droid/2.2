package com.campmeds.app.repository

import com.campmeds.app.auth.PinHasher
import androidx.room.withTransaction
import com.campmeds.app.data.AppDatabase
import com.campmeds.app.data.entity.*
import com.campmeds.app.domain.MedicationIdentity
import com.campmeds.app.domain.splitFullName
import com.campmeds.app.export.XlsxExporter
import com.campmeds.app.network.NdcApiClient
import com.campmeds.app.network.NdcLookupResult
import kotlinx.coroutines.flow.Flow
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.util.UUID

class CampMedsRepository(
    private val db: AppDatabase,
    private val ndcApiClient: NdcApiClient = NdcApiClient()
) {
    // ---------------- Auth ----------------

    suspend fun login(pin: String): User? {
        val users = db.userDao().getAll()
        return users.firstOrNull { PinHasher.verify(pin, it.pinSalt, it.pinHash) }
    }

    suspend fun createUser(name: String, role: Role, pin: String): User {
        val salt = PinHasher.newSalt()
        val user = User(name = name, role = role, pinHash = PinHasher.hash(pin, salt), pinSalt = salt)
        db.userDao().upsert(user)
        return user
    }

    fun observeUsers(): Flow<List<User>> = db.userDao().observeAll()

    // ---------------- Patients ----------------

    fun observePatients(): Flow<List<Patient>> = db.patientDao().observeAll()

    fun searchPatients(query: String): Flow<List<Patient>> = db.patientDao().search(query)

    suspend fun getPatient(id: String): Patient? = db.patientDao().getById(id)

    fun observePatient(id: String): Flow<Patient?> = db.patientDao().observeById(id)

    /** Creates or updates a patient. `name` is always re-derived from first/last name. */
    suspend fun savePatient(patient: Patient) = db.patientDao().upsert(normalizePatient(patient))

    /**
     * "Deletes" a patient: the patient and their medications are archived, never removed, so every
     * administration record (and the names needed to export it) survives.
     */
    suspend fun archivePatient(patientId: String) {
        db.withTransaction {
            db.medicationDao().archiveAllForPatient(patientId)
            db.patientDao().archive(patientId)
        }
    }

    // ---------------- Medications ----------------

    fun observeMedicationsForPatient(patientId: String): Flow<List<Medication>> =
        db.medicationDao().observeForPatient(patientId)

    suspend fun getMedication(id: String): Medication? = db.medicationDao().getById(id)

    suspend fun getMedicationByQr(qrCode: String): Medication? = db.medicationDao().getByQrCode(qrCode)

    suspend fun saveMedication(medication: Medication) = db.medicationDao().upsert(medication)

    fun observeArchivedMedicationsForPatient(patientId: String): Flow<List<Medication>> =
        db.medicationDao().observeArchivedForPatient(patientId)

    /**
     * Saves an add/edit from the medication form.
     *  - new medication                      -> inserted
     *  - edit that changes the medication's identity (name / NDC / strength / form)
     *                                        -> old record archived (history + sheet kept), NEW record
     *                                           with a new id and QR code inserted, in one transaction
     *  - any other edit                      -> updated in place
     * Returns the record that is now the current one.
     */
    suspend fun saveMedicationEdit(existing: Medication?, updated: Medication): Medication {
        if (existing == null) {
            db.medicationDao().upsert(updated)
            return updated
        }
        if (MedicationIdentity.changed(existing, updated)) {
            val replacement = updated.copy(
                id = UUID.randomUUID().toString(),
                qrCode = UUID.randomUUID().toString(),
                isArchived = false
            )
            db.withTransaction {
                db.medicationDao().archive(existing.id)
                db.medicationDao().upsert(replacement)
            }
            return replacement
        }
        db.medicationDao().upsert(updated)
        return updated
    }

    /** "Deletes" a medication by archiving it. Its dose history is untouched. */
    suspend fun archiveMedication(medicationId: String) = db.medicationDao().archive(medicationId)

    /**
     * Looks up an NDC via openFDA. Caches nothing itself — the caller (Add/Edit Medication
     * screen) persists the confirmed result onto the Medication entity, which is what makes
     * every subsequent read fully offline (spec section 7).
     */
    suspend fun lookupNdc(ndc: String): NdcLookupResult {
        val cached = ndc.trim().takeIf { it.isNotEmpty() }?.let { db.medicationDao().getCachedByNdc(it) }
        if (cached != null) {
            return NdcLookupResult.Found(cached.ndc, cached.name, cached.strength, cached.form)
        }
        return ndcApiClient.lookup(ndc)
    }

    /** All medications active (by date range) for a given day, across all patients. */
    suspend fun getActiveMedications(day: LocalDate): List<Medication> =
        db.medicationDao().getActiveOn(day.toString())

    fun observeActiveMedications(day: LocalDate): Flow<List<Medication>> =
        db.medicationDao().observeActiveOn(day.toString())

    // ---------------- Dose logs ----------------

    fun observeDoseLogsForDay(day: LocalDate): Flow<List<DoseLog>> {
        val start = day.atStartOfDay()
        val end = day.atTime(LocalTime.MAX)
        return db.doseLogDao().observeForDay(start.toString(), end.toString())
    }

    fun observeHistoryForPatient(patientId: String): Flow<List<DoseLog>> =
        db.doseLogDao().observeHistoryForPatient(patientId)

    suspend fun getHistoryForPatientOnce(patientId: String): List<DoseLog> =
        db.doseLogDao().getHistoryForPatientOnce(patientId)

    /**
     * Records (or overwrites) a dose outcome for a specific scheduled time.
     * Per spec section 8, edits simply overwrite — no correction/edit trail in this version.
     */
    suspend fun recordDose(
        medicationId: String,
        scheduledFor: LocalDateTime,
        status: DoseStatus,
        loggedByUserId: String,
        wasOverride: Boolean = false
    ) {
        val existing = db.doseLogDao().findExisting(medicationId, scheduledFor.toString())
        val entry = (existing ?: DoseLog(
            medicationId = medicationId,
            scheduledFor = scheduledFor,
            status = status,
            loggedAt = LocalDateTime.now(),
            loggedByUserId = loggedByUserId,
            wasOverride = wasOverride
        )).copy(
            status = status,
            loggedAt = LocalDateTime.now(),
            loggedByUserId = loggedByUserId,
            wasOverride = wasOverride
        )
        db.doseLogDao().upsert(entry)
    }

    // ---------------- Export ----------------

    /**
     * Everything the export needs: one entry per medication (archived ones and those of archived
     * patients included), each with that medication's complete history and its patient's name.
     */
    suspend fun loadExportData(): XlsxExporter.ExportData {
        val patientsById = db.patientDao().getAllIncludingArchived().associateBy { it.id }
        val medications = db.medicationDao().getAllIncludingArchived()
        val logs = db.doseLogDao().getAll()
        val logsByMedication = logs.groupBy { it.medicationId }

        val sheets = medications.map { med ->
            XlsxExporter.MedicationSheet(
                patientName = patientsById[med.patientId]?.name.orEmpty(),
                medication = med,
                doseLogs = logsByMedication[med.id].orEmpty()
            )
        }.sortedWith(
            compareBy({ it.patientName.lowercase() }, { it.medication.name.lowercase() }, { it.medication.startDate })
        )
        val knownMedicationIds = medications.map { it.id }.toSet()
        return XlsxExporter.ExportData(
            sheets = sheets,
            usersById = db.userDao().getAll().associateBy { it.id },
            unlinkedLogs = logs.filter { it.medicationId !in knownMedicationIds }
        )
    }

    companion object {
        @Volatile private var instance: CampMedsRepository? = null

        fun getInstance(db: AppDatabase): CampMedsRepository =
            instance ?: synchronized(this) {
                instance ?: CampMedsRepository(db).also { instance = it }
            }
    }
}

/** Keeps first/last name and the derived full `name` consistent; trims free text; blank -> null. */
internal fun normalizePatient(p: Patient): Patient {
    var first = p.firstName.trim()
    var last = p.lastName.trim()
    if (first.isEmpty() && last.isEmpty()) {
        val (f, l) = splitFullName(p.name) // legacy callers that only know a single name
        first = f
        last = l
    }
    return p.copy(
        firstName = first,
        lastName = last,
        name = listOf(first, last).filter { it.isNotEmpty() }.joinToString(" "),
        notes = p.notes?.trim()?.ifBlank { null },
        conditions = p.conditions?.trim()?.ifBlank { null }
    )
}
