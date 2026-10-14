package com.campmeds.app.spreadsheet

import java.util.Locale

/**
 * The spreadsheet columns CampMeds understands, in the order the CampMeds template writes them.
 *
 * [aliases] are normalised header spellings (lower case, letters and digits only) that are recognised
 * automatically. Anything the automatic mapping cannot place reliably goes to the column-mapping screen.
 */
enum class Field(val header: String, val aliases: Set<String>) {
    PATIENT_ID("Patient ID", setOf("patientid", "patientcampmedsid", "patientrecordid")),
    MEDICATION_ID("Medication ID", setOf("medicationid", "medicationrecordid", "medid")),
    FIRST_NAME("First Name", setOf("firstname", "first", "givenname")),
    LAST_NAME("Last Name", setOf("lastname", "last", "surname", "familyname")),
    DATE_OF_BIRTH("Date of Birth", setOf("dateofbirth", "dob", "birthdate", "birthday")),
    NOTES("Notes", setOf("notes", "allergies", "patientnotes")),
    CONDITIONS("Conditions", setOf("conditions", "medicalconditions")),
    MEDICATION("Medication", setOf("medication", "medicationname", "medname", "drug", "drugname")),
    NDC("NDC", setOf("ndc")),
    STRENGTH("Strength", setOf("strength")),
    FORM("Form", setOf("form", "dosageform")),
    DOSAGE_INSTRUCTIONS("Dosage Instructions", setOf("dosageinstructions", "instructions", "directions", "dosage")),
    SCHEDULE_TIMES("Schedule Times", setOf("scheduletimes", "schedule", "times", "administrationtimes")),
    START_DATE("Start Date", setOf("startdate", "start")),
    END_DATE("End Date", setOf("enddate", "end")),
    BOTTLE_EXPIRATION("Bottle Expiration", setOf("bottleexpiration", "expiration", "expirationdate", "expires")),
    PRN("PRN", setOf("prn", "asneeded")),
    PURPOSE("Purpose", setOf("purpose", "reason", "indication"));

    /** Patient-detail columns (one value per patient, repeated on every medication row of that patient). */
    val isPatientDetail: Boolean
        get() = this == FIRST_NAME || this == LAST_NAME || this == DATE_OF_BIRTH || this == NOTES || this == CONDITIONS

    companion object {
        /** Optional fields that may be explicitly emptied in an UPDATE import (see [CLEAR_TOKEN]). */
        val CLEARABLE: Set<Field> = setOf(
            LAST_NAME, DATE_OF_BIRTH, NOTES, CONDITIONS, END_DATE, BOTTLE_EXPIRATION, PURPOSE
        )

        /** The columns that must be mapped before a file can be read in each mode. */
        fun required(mode: ImportMode): Set<Field> = when (mode) {
            ImportMode.NEW_RECORDS -> setOf(FIRST_NAME)
            ImportMode.UPDATE_EXISTING -> setOf(PATIENT_ID, MEDICATION_ID)
        }
    }
}

/** Typing this into an optional cell of an UPDATE import is the only way to empty an existing value. */
const val CLEAR_TOKEN = "[CLEAR]"

/** Upper limits that keep a phone-sized import bounded. */
object ImportLimits {
    const val MAX_FILE_BYTES = 10L * 1024 * 1024
    const val MAX_ROWS = 5000
    const val MAX_COLUMNS = 40
}

/** One spreadsheet row. [rowNumber] is the 1-based Excel row, used only for messages. */
data class RawRow(val rowNumber: Int, val cells: List<String>) {
    fun value(column: Int?): String = column?.let { cells.getOrNull(it) }.orEmpty().trim()
}

/** The first worksheet of a file: header names as written, and the data rows below them. */
data class RawSheet(val fileName: String, val headers: List<String>, val rows: List<RawRow>)

/** Result of matching headers to [Field]s. [columns] maps each recognised field to its column index. */
data class HeaderMapping(
    val columns: Map<Field, Int>,
    val missingRequired: List<Field>,
    val duplicateHeaders: List<String>,
    val unrecognizedHeaders: List<String>
) {
    /** The automatic mapping can be trusted only if nothing required is missing and nothing is ambiguous. */
    val needsReview: Boolean get() = missingRequired.isNotEmpty() || duplicateHeaders.isNotEmpty()
}

object HeaderMapper {

    fun normalize(header: String): String = header.lowercase(Locale.ROOT).filter { it.isLetterOrDigit() }

    fun suggest(headers: List<String>, mode: ImportMode): HeaderMapping {
        val columns = LinkedHashMap<Field, Int>()
        val duplicates = mutableListOf<String>()
        val matched = mutableSetOf<Int>()
        headers.forEachIndexed { index, header ->
            val key = normalize(header)
            if (key.isEmpty()) return@forEachIndexed
            val field = Field.values().firstOrNull { key == normalize(it.header) || key in it.aliases }
                ?: return@forEachIndexed
            matched += index
            if (field in columns) duplicates += header.trim() else columns[field] = index
        }
        val unrecognized = headers.withIndex()
            .filter { (index, header) -> normalize(header).isNotEmpty() && index !in matched }
            .map { it.value.trim() }
        return HeaderMapping(
            columns = columns,
            missingRequired = Field.required(mode).filter { it !in columns }.sortedBy { it.ordinal },
            duplicateHeaders = duplicates,
            unrecognizedHeaders = unrecognized
        )
    }
}
