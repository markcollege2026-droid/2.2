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
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import com.campmeds.app.auth.Session
import com.campmeds.app.auth.RecoverySecurity
import com.campmeds.app.domain.isActiveOn
import com.campmeds.app.domain.localDays
import com.campmeds.app.domain.MedicationChanges
import com.campmeds.app.export.SheetNames
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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

    suspend fun createUser(name: String, role: Role, pin: String,
        recovery: Map<String, String> = emptyMap()): User {
        require(name.isNotBlank())
        if (recovery.isNotEmpty()) RecoverySecurity.validate(recovery)
        val salt = PinHasher.newSalt()
        val user = User(name = name, role = role, pinHash = PinHasher.hash(pin, salt), pinSalt = salt)
        val secured = if (recovery.isEmpty()) user else withContext(Dispatchers.Default) {
            val salts = recovery.keys.map { PinHasher.newSalt() }
            user.copy(recoveryQuestionIds = recovery.keys.toList(), recoverySalts = salts,
                recoveryHashes = recovery.values.mapIndexed { i, answer -> RecoverySecurity.hash(answer, salts[i]) })
        }
        db.userDao().upsert(secured)
        return secured
    }

    fun observeUsers(): Flow<List<User>> = db.userDao().observeAll()

    /** Enrollment for existing accounts requires their current PIN; no recovery state is ever fabricated. */
    suspend fun enrollRecovery(currentPin: String, answers: Map<String, String>): Boolean {
        val id = Session.currentUser.value?.id ?: return false
        RecoverySecurity.validate(answers)
        return withContext(Dispatchers.Default) { db.withTransaction {
            val user = db.userDao().getById(id) ?: return@withTransaction false
            if (!PinHasher.verify(currentPin, user.pinSalt, user.pinHash)) return@withTransaction false
            val salts = answers.keys.map { PinHasher.newSalt() }
            db.userDao().upsert(user.copy(recoveryQuestionIds = answers.keys.toList(), recoverySalts = salts,
                recoveryHashes = answers.values.mapIndexed { i, a -> RecoverySecurity.hash(a, salts[i]) },
                recoveryFailures = 0, recoveryLockedUntil = 0))
            true
        } }
    }

    /** Local recovery uses the existing PIN hasher for the replacement PIN, never changes roles or history.
     * Failure counters and the reset are atomic, persistent and cannot be bypassed by reopening the form.
     */
    suspend fun recoverAccount(userId: String, answers: Map<String, String>, newPin: String): Boolean =
        withContext(Dispatchers.Default) { db.withTransaction {
            val user = db.userDao().getById(userId) ?: return@withTransaction false
            val now = System.currentTimeMillis()
            if (user.recoveryLockedUntil > now || user.recoveryQuestionIds.size != 3 ||
                user.recoveryHashes.size != 3 || user.recoverySalts.size != 3) return@withTransaction false
            if (!newPin.matches(Regex("[0-9]{4,8}"))) return@withTransaction false
            // Evaluate all answers, not just up to the first wrong one.
            val matches = user.recoveryQuestionIds.mapIndexed { i, q ->
                RecoverySecurity.verify(answers[q].orEmpty(), user.recoverySalts[i], user.recoveryHashes[i])
            }.all { it }
            if (!matches) {
                val failures = (user.recoveryFailures + 1).coerceAtMost(100)
                db.userDao().upsert(user.copy(recoveryFailures = failures,
                    recoveryLockedUntil = if (failures >= 5) now + 30 * 60 * 1000L else 0))
                return@withTransaction false
            }
            // PIN-only login must remain unambiguous; never allow recovery to select another user's PIN.
            if (db.userDao().getAll().any { it.id != user.id && PinHasher.verify(newPin, it.pinSalt, it.pinHash) })
                return@withTransaction false
            val salt = PinHasher.newSalt()
            db.userDao().upsert(user.copy(pinSalt = salt, pinHash = PinHasher.hash(newPin, salt),
                recoveryFailures = 0, recoveryLockedUntil = 0))
            true
        } }

    fun observeSettings(): Flow<CampSettings> = db.campSettingsDao().observe().map { it ?: CampSettings() }
    suspend fun saveSettings(settings: CampSettings) {
        check(Session.hasAtLeast(Role.ADMIN)) { "Administrator access required" }
        require(listOf(settings.bunkLabel, settings.counselorLabel, settings.divisionLabel).all { it.isNotBlank() })
        (settings.startDates + settings.endDates).forEach { LocalDate.parse(it) }
        db.campSettingsDao().save(settings.copy(id = 1, startDates = settings.startDates.distinct().sorted(),
            endDates = settings.endDates.distinct().sorted()))
    }

    // ---------------- Patients ----------------

    fun observePatients(): Flow<List<Patient>> = combine(db.patientDao().observeIncludingArchived(), localDays()) {
        patients, day -> patients.filter { it.isActiveOn(day) }
    }

    fun observeArchive(): Flow<List<Patient>> = combine(db.patientDao().observeIncludingArchived(), localDays()) {
        patients, day -> patients.filterNot { it.isActiveOn(day) }
    }

    fun searchPatients(query: String): Flow<List<Patient>> = observePatients().map { patients ->
        patients.filter { it.name.contains(query, ignoreCase = true) }
    }

    suspend fun getPatient(id: String): Patient? = db.patientDao().getById(id)

    fun observePatient(id: String): Flow<Patient?> = db.patientDao().observeById(id)

    /** Creates or updates a patient. `name` is always re-derived from first/last name. */
    suspend fun savePatient(patient: Patient) {
        require(patient.startDate == null || patient.endDate == null || patient.startDate <= patient.endDate) {
            "End date cannot precede start date"
        }
        db.patientDao().upsert(normalizePatient(patient))
    }

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

    suspend fun saveMedication(medication: Medication) {
        saveMedicationEdit(db.medicationDao().getById(medication.id), medication)
    }

    fun observeMedicationChanges(id: String) = db.importHistoryDao().observeChanges(id)

    suspend fun discontinueMedication(id: String, effectiveDate: LocalDate) {
        check(Session.hasAtLeast(Role.ADMIN)) { "Administrator access required" }
        db.withTransaction {
            val med = requireNotNull(db.medicationDao().getById(id))
            require(med.discontinuedOn == null) { "Already discontinued" }
            require(effectiveDate >= LocalDate.now() && effectiveDate >= med.startDate)
            require(med.endDate == null || effectiveDate <= med.endDate)
            saveMedicationEdit(med, med.copy(discontinuedOn = effectiveDate))
        }
    }

    fun observeArchivedMedicationsForPatient(patientId: String): Flow<List<Medication>> =
        db.medicationDao().observeArchivedForPatient(patientId)

    /** Edits keep the same ID, QR and sheet. Old/new values are appended atomically to history. */
    suspend fun saveMedicationEdit(existing: Medication?, updated: Medication): Medication = db.withTransaction {
        val old = db.medicationDao().getById(existing?.id ?: updated.id)
        if (old != null) require(existing == old) { "Medication changed. Reopen the form before saving." }
        val validationError = PrepackagedGroups.validate(updated, db.medicationDao().getCurrentMedications())
        require(validationError == null) { validationError ?: "" }
        require(updated.endDate == null || updated.endDate >= updated.startDate)
        val actor = Session.currentUser.value
        val used = db.medicationDao().getAllIncludingArchived().mapNotNull { it.sheetName?.lowercase() }.toMutableSet()
        val saved = updated.copy(
            id = old?.id ?: updated.id, patientId = old?.patientId ?: updated.patientId,
            qrCode = old?.qrCode ?: updated.qrCode,
            sheetName = old?.sheetName ?: SheetNames.unique(SheetNames.baseName(
                db.patientDao().getById(updated.patientId)?.name.orEmpty(), old?.name ?: updated.name), used),
            createdByUserId = if (old != null) old.createdByUserId else actor?.id,
            createdByName = if (old != null) old.createdByName else actor?.name,
            createdAt = if (old != null) old.createdAt else LocalDateTime.now()
        )
        if (old != null) db.importHistoryDao().insertChanges(MedicationChanges.between(old, saved, actor?.id))
        db.medicationDao().upsert(saved)
        syncGroupName(saved)
        saved
    }

    /** Keep group labels consistent, recording every affected medication's previous value. */
    private suspend fun syncGroupName(medication: Medication) {
        val groupId = medication.prepackagedGroupId ?: return
        if (!medication.isGroupAdministered) return
        db.medicationDao().getByPrepackagedGroup(groupId).filter {
            it.id != medication.id && it.patientId == medication.patientId
        }.forEach { old ->
            val updated = old.copy(prepackagedGroupName = medication.prepackagedGroupName)
            db.importHistoryDao().insertChanges(MedicationChanges.between(old, updated, Session.currentUser.value?.id))
            db.medicationDao().upsert(updated)
        }
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
                    val updated = AdministrationTimeRules.retime(it, entry.id, time)
                    db.importHistoryDao().insertChanges(MedicationChanges.between(it, updated, Session.currentUser.value?.id))
                    db.medicationDao().upsert(updated)
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
                val updated = AdministrationTimeRules.detach(it, id)
                    db.importHistoryDao().insertChanges(MedicationChanges.between(it, updated, Session.currentUser.value?.id))
                    db.medicationDao().upsert(updated)
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

    /** First-write wins, inside the transaction: double taps/scans never overwrite an outcome. */
    suspend fun recordDose(
        medicationId: String,
        scheduledFor: LocalDateTime,
        status: DoseStatus,
        loggedByUserId: String,
        wasOverride: Boolean = false,
        heldReason: String? = null,
        expectedMedication: Medication? = null
    ): Boolean = db.withTransaction {
        if (db.doseLogDao().findExisting(medicationId, scheduledFor.toString()) != null) return@withTransaction false
        val med = db.medicationDao().getById(medicationId) ?: return@withTransaction false
        val patient = db.patientDao().getById(med.patientId) ?: return@withTransaction false
        if (expectedMedication != null && (med != expectedMedication || scheduledFor.toLocalDate() != LocalDate.now())) return@withTransaction false
        if (!med.isActiveOn(scheduledFor.toLocalDate()) || !patient.isActiveOn(scheduledFor.toLocalDate()) ||
            !med.isActiveOn(LocalDate.now()) || !patient.isActiveOn(LocalDate.now())) return@withTransaction false
        db.doseLogDao().upsert(DoseLog(medicationId = medicationId, scheduledFor = scheduledFor,
            status = status, loggedAt = LocalDateTime.now(), loggedByUserId = loggedByUserId,
            wasOverride = wasOverride, heldReason = if (status == DoseStatus.HELD) heldReason?.trim()?.ifBlank { null } else null))
        true
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
                val med = db.medicationDao().getById(dose.medicationId) ?: continue
                val patient = db.patientDao().getById(med.patientId) ?: continue
                if (!med.isActiveOn(now.toLocalDate()) || !patient.isActiveOn(now.toLocalDate())) continue
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
    suspend fun loadExportData(): XlsxExporter.ExportData = db.withTransaction {
        val patientsById = db.patientDao().getAllIncludingArchived().associateBy { it.id }
        val medications = db.medicationDao().getAllIncludingArchived()
        val logs = db.doseLogDao().getAll()
        val logsByMedication = logs.groupBy { it.medicationId }

        val sheets = medications.map { med ->
            XlsxExporter.MedicationSheet(
                patientName = patientsById[med.patientId]?.name.orEmpty(),
                medication = med,
                doseLogs = logsByMedication[med.id].orEmpty(),
                changes = db.importHistoryDao().changesForRecord(med.id)
            )
        }.sortedWith(
            compareBy({ it.patientName.lowercase() }, { it.medication.name.lowercase() }, { it.medication.startDate })
        )
        val knownMedicationIds = medications.map { it.id }.toSet()
        XlsxExporter.ExportData(
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
