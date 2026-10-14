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
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.campmeds.app.auth.Session
import com.campmeds.app.auth.SecurityQuestions
import com.campmeds.app.domain.isActiveOn
import com.campmeds.app.domain.medicationChanges
import com.campmeds.app.export.SheetNames
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

    suspend fun createUser(name: String, role: Role, pin: String, recovery: Map<String, String> = emptyMap()): User = withContext(Dispatchers.Default) {
        if (db.userDao().getAll().isNotEmpty()) require(Session.hasAtLeast(Role.ADMIN)) { "Admin access required" }
        require(SecurityQuestions.validPin(pin)) { "PIN must contain 4–8 digits" }
        val salt = PinHasher.newSalt()
        val user = withRecovery(User(name = name, role = role, pinHash = PinHasher.hash(pin, salt), pinSalt = salt), recovery)
        db.userDao().upsert(user)
        user
    }

    private fun withRecovery(user: User, answers: Map<String, String>): User {
        if (answers.isEmpty()) return user // Existing accounts remain valid; no invented answers.
        require(answers.size == 3 && answers.keys.all { it in SecurityQuestions.available } && answers.values.all { SecurityQuestions.normalize(it).length >= 3 })
        val salt = PinHasher.newSalt()
        return user.copy(recoveryQuestions = answers.keys.joinToString("|"), recoverySalt = salt,
            recoveryHashes = answers.map { (q, a) -> SecurityQuestions.hash(a, salt, q) }.joinToString("|"),
            recoveryFailures = 0, recoveryLockedUntil = 0)
    }

    /** Existing users can enroll only after re-entering their own PIN in an authenticated session. */
    suspend fun enrollRecovery(pin: String, answers: Map<String, String>): Boolean = withContext(Dispatchers.Default) {
        db.withTransaction {
            val user = Session.currentUser.value?.id?.let { db.userDao().getById(it) } ?: return@withTransaction false
            if (!PinHasher.verify(pin, user.pinSalt, user.pinHash)) return@withTransaction false
            require(answers.size == 3)
            db.userDao().upsert(withRecovery(user, answers))
            true
        }
    }

    /** Answers and a new PIN are checked together: no reusable reset token or unauthenticated reset API. */
    suspend fun recoverAccount(userId: String, answers: Map<String, String>, newPin: String): Boolean = withContext(Dispatchers.Default) {
        db.withTransaction {
            val user = db.userDao().getById(userId) ?: return@withTransaction false
            val now = System.currentTimeMillis()
            if (now < user.recoveryLockedUntil || !SecurityQuestions.validPin(newPin)) return@withTransaction false
            val questions = user.recoveryQuestions?.split("|") ?: return@withTransaction false
            val hashes = user.recoveryHashes?.split("|") ?: return@withTransaction false
            val salt = user.recoverySalt ?: return@withTransaction false
            if (questions.size != 3 || hashes.size != 3) return@withTransaction false
            val checks = questions.mapIndexed { i, q -> SecurityQuestions.verify(answers[q].orEmpty(), salt, q, hashes[i]) }
            if (!checks.all { it }) {
                val failures = (user.recoveryFailures + 1).coerceAtMost(30)
                // Persist on every attempt. Restarting the app cannot bypass the cooldown.
                val seconds = if (failures >= 5) 3600L else 30L * failures
                db.userDao().upsert(user.copy(recoveryFailures = failures, recoveryLockedUntil = now + seconds * 1000))
                return@withTransaction false
            }
            val pinSalt = PinHasher.newSalt()
            db.userDao().upsert(user.copy(pinSalt = pinSalt, pinHash = PinHasher.hash(newPin, pinSalt), recoveryFailures = 0, recoveryLockedUntil = 0))
            true
        }
    }

    fun observeUsers(): Flow<List<User>> = db.userDao().observeAll()

    // ---------------- Patients ----------------

    // Re-evaluate local date while visible and after restart/resume, including timezone/clock changes.
    private val days: Flow<LocalDate> = flow {
        while (true) { emit(LocalDate.now()); delay(1000) }
    }.distinctUntilChanged()

    fun observePatients(): Flow<List<Patient>> = combine(db.patientDao().observeIncludingArchived(), days) { patients, day ->
        patients.filter { it.isActiveOn(day) }
    }

    fun observeArchive(): Flow<List<Patient>> = combine(db.patientDao().observeIncludingArchived(), days) { patients, day ->
        patients.filterNot { it.isActiveOn(day) }
    }

    fun searchPatients(query: String): Flow<List<Patient>> = observePatients().map { rows -> rows.filter { it.name.contains(query, true) } }

    fun observeSettings(): Flow<Map<String, String>> = db.campSettingDao().observeAll().map { rows -> rows.associate { it.key to it.value } }

    suspend fun saveSetting(key: String, value: String) {
        require(Session.hasAtLeast(Role.ADMIN)) { "Admin access required" }
        require(key in setOf("bunkLabel", "counselorLabel", "divisionLabel", "startPresets", "endPresets"))
        if (key.endsWith("Presets")) value.lineSequence().filter { it.isNotBlank() }.forEach { LocalDate.parse(it.trim()) }
        else require(value.isNotBlank() && value.length <= 60)
        db.campSettingDao().upsert(CampSetting(key, value))
    }

    suspend fun getPatient(id: String): Patient? = db.patientDao().getById(id)

    fun observePatient(id: String): Flow<Patient?> = db.patientDao().observeById(id)

    /** Creates or updates a patient. `name` is always re-derived from first/last name. */
    suspend fun savePatient(patient: Patient) {
        require(patient.startDate == null || patient.endDate == null || patient.endDate >= patient.startDate)
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

    fun observeArchivedMedicationsForPatient(patientId: String): Flow<List<Medication>> =
        db.medicationDao().observeArchivedForPatient(patientId)

    /** Updates in place, freezing sheet identity and recording old/new values atomically. */
    suspend fun saveMedicationEdit(existing: Medication?, updated: Medication): Medication = db.withTransaction {
        val old = db.medicationDao().getById(updated.id)
        require(existing == null || old != null) { "Medication no longer exists" }
        val validationError = PrepackagedGroups.validate(updated, db.medicationDao().getCurrentMedications())
        require(validationError == null) { validationError ?: "" }
        val actor = Session.currentUser.value
        val saved = if (old == null) prepareMedicationCreation(db, updated, actor) else updated.copy(
            id = old.id, patientId = old.patientId, qrCode = old.qrCode, sheetName = old.sheetName,
            createdByUserId = old.createdByUserId, createdByName = old.createdByName, createdAt = old.createdAt,
            effectiveEndDate = old.effectiveEndDate, discontinuedAt = old.discontinuedAt)
        persistMedicationChange(old, saved)
        val groupId = saved.prepackagedGroupId
        if (saved.isGroupAdministered && groupId != null) {
            db.medicationDao().getByPrepackagedGroup(groupId).filter { it.id != saved.id }.forEach { member ->
                persistMedicationChange(member, member.copy(prepackagedGroupName = saved.prepackagedGroupName))
            }
        }
        saved
    }

    private suspend fun persistMedicationChange(old: Medication?, updated: Medication) {
        if (old != null) db.importHistoryDao().insertChanges(medicationChanges(old, updated, Session.currentUser.value?.id))
        db.medicationDao().upsert(updated)
    }

    suspend fun medicationHistory(id: String): List<ChangeHistoryEntry> = db.importHistoryDao().changesForRecord(id)

    /** Stop immediately; the original prescribed end date and all DoseLogs remain unchanged. */
    suspend fun discontinueMedication(id: String) {
        require(Session.hasAtLeast(Role.ADMIN))
        db.withTransaction {
            val med = db.medicationDao().getById(id) ?: return@withTransaction
            if (med.discontinuedAt != null) return@withTransaction
            persistMedicationChange(med, med.copy(effectiveEndDate = LocalDate.now(), discontinuedAt = LocalDateTime.now()))
        }
    }

    /** "Deletes" a medication by archiving it. Its dose history is untouched. */
    suspend fun archiveMedication(medicationId: String) = db.withTransaction {
        db.medicationDao().getById(medicationId)?.let { persistMedicationChange(it, it.copy(isArchived = true)) }
    }

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
                    persistMedicationChange(it, AdministrationTimeRules.retime(it, entry.id, time))
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
                persistMedicationChange(it, AdministrationTimeRules.detach(it, id))
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

    /** Atomic check-and-insert. Existing outcomes are immutable, including Held and Refused. */
    suspend fun recordDose(
        medicationId: String,
        scheduledFor: LocalDateTime,
        status: DoseStatus,
        loggedByUserId: String,
        wasOverride: Boolean = false,
        heldReason: String? = null
    ): Boolean = db.withTransaction {
        if (db.doseLogDao().findExisting(medicationId, scheduledFor.toString()) != null) return@withTransaction false
        if (!canRecord(medicationId, scheduledFor)) return@withTransaction false
        db.doseLogDao().upsert(DoseLog(medicationId = medicationId, scheduledFor = scheduledFor,
            status = status, loggedAt = LocalDateTime.now(), loggedByUserId = loggedByUserId,
            wasOverride = wasOverride, heldReason = if (status == DoseStatus.HELD) heldReason?.trim()?.ifBlank { null } else null))
        true
    }

    private suspend fun canRecord(id: String, scheduled: LocalDateTime): Boolean {
        val med = db.medicationDao().getById(id) ?: return false
        val patient = db.patientDao().getById(med.patientId) ?: return false
        val day = scheduled.toLocalDate()
        return !med.isArchived && med.discontinuedAt == null && patient.isActiveOn(day) &&
            patient.isActiveOn(LocalDate.now()) && day >= med.startDate && (med.endDate == null || day <= med.endDate)
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
                if (existing != null || !canRecord(dose.medicationId, dose.scheduledFor)) continue
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

/** Used by both manual entry and spreadsheet creation, inside their transaction. */
internal suspend fun prepareMedicationCreation(db: AppDatabase, med: Medication, actor: User?): Medication {
    val used = db.medicationDao().getAllIncludingArchived().mapNotNull { it.sheetName?.lowercase() }.toHashSet()
    val patientName = db.patientDao().getById(med.patientId)?.name.orEmpty()
    return med.copy(createdByUserId = actor?.id, createdByName = actor?.name,
        createdAt = LocalDateTime.now(), sheetName = SheetNames.unique(SheetNames.baseName(patientName, med.name), used))
}
