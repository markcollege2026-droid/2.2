package com.campmeds.app.repository

import com.campmeds.app.auth.PinHasher
import androidx.room.withTransaction
import com.campmeds.app.data.AppDatabase
import com.campmeds.app.data.entity.*
import com.campmeds.app.domain.AdministrationTimeRules
import com.campmeds.app.domain.MedicationIdentity
import com.campmeds.app.domain.PlannedDose
import com.campmeds.app.domain.PrepackagedGroups
import com.campmeds.app.domain.distinctDoses
import com.campmeds.app.domain.splitFullName
import com.campmeds.app.export.XlsxExporter
import com.campmeds.app.network.NdcApiClient
import com.campmeds.app.network.NdcLookupResult
import kotlinx.coroutines.flow.Flow
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.util.UUID

/** What removing/retiming a named administration time would affect (shown before the administrator confirms). */
data class AdministrationTimeUsage(
    /** Current (non-archived) medications whose schedule references the entry. */
    val medications: List<Medication>,
    /** How many scheduled doses across those medications reference it. */
    val doses: Int
)

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
        // Safety net for the prepackaged-group rules: the form validates first and shows the message, so
        // reaching this means a caller bypassed the check. Refusing is the only safe outcome — a group may
        // never span patients.
        val validationError = PrepackagedGroups.validate(updated, db.medicationDao().getCurrentMedications())
        require(validationError == null) { validationError ?: "" }

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
     * Looks up an NDC via the existing openFDA integration. Caches nothing itself — the caller (Add/Edit
     * Medication screen) persists the confirmed result onto the Medication entity, which is what makes
     * every subsequent read fully offline (spec section 7).
     *
     * The cache is keyed on the value as entered, so a medication saved from a successful lookup answers
     * later lookups of the same typed value without a request.
     */
    suspend fun lookupNdc(ndc: String): NdcLookupResult {
        val cached = ndc.trim().takeIf { it.isNotEmpty() }?.let { db.medicationDao().getCachedByNdc(it) }
        if (cached != null) {
            return NdcLookupResult.Found(
                ndc = cached.ndc,
                matchedNdc = cached.ndc,
                name = cached.name,
                strength = cached.strength,
                form = cached.form,
                isPackage = cached.ndc.count { it == '-' } == 2
            )
        }
        return ndcApiClient.lookup(ndc)
    }

    /** The medications of one prepackaged group (current records only). */
    suspend fun getPrepackagedGroupMembers(groupId: String): List<Medication> =
        db.medicationDao().getByPrepackagedGroup(groupId)

    // ---------------- Named administration times ----------------

    fun observeAdministrationTimes(): Flow<List<AdministrationTime>> = db.administrationTimeDao().observeAll()

    suspend fun getAdministrationTimes(): List<AdministrationTime> = db.administrationTimeDao().getAll()

    /** Current medications of every patient — what a settings change can affect, and what the forms list. */
    suspend fun getCurrentMedications(): List<Medication> = db.medicationDao().getCurrentMedications()

    /** How many current medications (and doses) are scheduled from this entry right now. */
    suspend fun administrationTimeUsage(id: String): AdministrationTimeUsage {
        val affected = AdministrationTimeRules.using(db.medicationDao().getCurrentMedications(), id)
        return AdministrationTimeUsage(
            medications = affected,
            doses = AdministrationTimeRules.usageCount(affected, id)
        )
    }

    /**
     * Creates an entry, or renames/re-times an existing one. Throws [IllegalArgumentException] for a blank
     * or duplicate name — the settings screen checks first so the message can be shown next to the field.
     *
     * A time change is applied here, in the same transaction, to the current schedules that reference the
     * entry (see [AdministrationTimeRules.retime]): "Breakfast 08:00 -> 08:30" moves those doses to 08:30.
     * Past administration records are never touched — a DoseLog stores the time its dose was scheduled for,
     * so history keeps saying what was true when it happened. A rename never changes any schedule.
     */
    suspend fun saveAdministrationTime(
        existing: AdministrationTime?,
        name: String,
        time: LocalTime
    ): AdministrationTime {
        val others = db.administrationTimeDao().getAll().filter { it.id != existing?.id }
        AdministrationTimeRules.validateName(name, others)?.let { throw IllegalArgumentException(it) }

        val entry = (existing ?: AdministrationTime(name = name.trim(), time = time))
            .copy(name = name.trim(), time = time)

        db.withTransaction {
            db.administrationTimeDao().upsert(entry)
            if (existing != null && existing.time != time) {
                AdministrationTimeRules.using(db.medicationDao().getCurrentMedications(), entry.id).forEach {
                    db.medicationDao().upsert(AdministrationTimeRules.retime(it, entry.id, time))
                }
            }
        }
        return entry
    }

    /**
     * Removes an entry. Medications that were scheduled from it keep their current times and simply become
     * custom times ([AdministrationTimeRules.detach]) — removing a name can never delete or move a dose, and
     * nothing is re-timed to another entry.
     */
    suspend fun deleteAdministrationTime(id: String) {
        db.withTransaction {
            AdministrationTimeRules.using(db.medicationDao().getCurrentMedications(), id).forEach {
                db.medicationDao().upsert(AdministrationTimeRules.detach(it, id))
            }
            db.administrationTimeDao().getById(id)?.let { db.administrationTimeDao().delete(it) }
        }
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

    /**
     * ONE action for a whole prepackaged group: records GIVEN for every planned dose, in a single
     * transaction, as one DoseLog per medication dose (never one generic group record).
     *
     * Nothing is recorded for a dose that already has an outcome: the check happens inside the transaction,
     * so an accidental second tap — or a press on a list that was already refreshed — cannot create a
     * duplicate record or overwrite a decision that was already made (Held/Refused, or a given dose).
     * Doses scheduled for later today are simply not in [doses] and are therefore never marked as given.
     *
     * Returns how many doses were actually recorded.
     */
    suspend fun recordGroupAdministration(doses: List<PlannedDose>, loggedByUserId: String): Int {
        val planned = doses.distinctDoses()
        if (planned.isEmpty()) return 0
        return db.withTransaction {
            val now = LocalDateTime.now()
            var recorded = 0
            for (dose in planned) {
                val existing = db.doseLogDao().findExisting(dose.medicationId, dose.scheduledFor.toString())
                if (existing != null) continue
                db.doseLogDao().upsert(
                    DoseLog(
                        medicationId = dose.medicationId,
                        scheduledFor = dose.scheduledFor,
                        status = DoseStatus.GIVEN,
                        loggedAt = now,
                        loggedByUserId = loggedByUserId,
                        wasOverride = dose.wasOverride
                    )
                )
                recorded++
            }
            recorded
        }
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
