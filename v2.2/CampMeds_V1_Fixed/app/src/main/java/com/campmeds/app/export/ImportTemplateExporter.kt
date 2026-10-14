package com.campmeds.app.export

import android.content.Context
import android.net.Uri
import com.campmeds.app.data.entity.Medication
import com.campmeds.app.data.entity.Patient
import com.campmeds.app.spreadsheet.CLEAR_TOKEN
import com.campmeds.app.spreadsheet.Field
import com.campmeds.app.domain.AdministrationTimeRules
import org.apache.poi.ss.usermodel.CellStyle
import org.apache.poi.ss.usermodel.FillPatternType
import org.apache.poi.ss.usermodel.IndexedColors
import org.apache.poi.ss.usermodel.Sheet
import org.apache.poi.xssf.usermodel.XSSFCellStyle
import org.apache.poi.xssf.usermodel.XSSFWorkbook
import java.io.IOException

/**
 * The import-capable template: one row per active medication (and one row for each active patient without
 * medications), with the permanent Patient ID and Medication ID. Row order is irrelevant on re-import.
 *
 * This is deliberately separate from the medication report in [XlsxExporter]: a report is for reading and
 * is not an update template, while this file is what the "Update existing records" workflow expects.
 */
object ImportTemplateExporter {

    const val SHEET_NAME = "Import"

    fun export(context: Context, destination: Uri, patients: List<Patient>, medications: List<Medication>) {
        XSSFWorkbook().use { workbook ->
            workbook.properties.customProperties.addProperty("CampMeds export date/time", java.time.OffsetDateTime.now().toString())
            workbook.properties.customProperties.addProperty("Export mode", "Copy — original data retained")
            val headerStyle = headerStyle(workbook)
            val sheet = workbook.createSheet(SHEET_NAME)
            val header = sheet.createRow(0)
            Field.values().forEachIndexed { index, field ->
                header.createCell(index).apply {
                    setCellValue(field.header)
                    cellStyle = headerStyle
                }
            }

            val medsByPatient = medications.filter { !it.isArchived }.groupBy { it.patientId }
            var rowIndex = 1
            patients.filter { !it.isArchived }.sortedBy { it.name.lowercase() }.forEach { patient ->
                val meds = medsByPatient[patient.id].orEmpty().sortedBy { it.name.lowercase() }
                if (meds.isEmpty()) {
                    writeRow(sheet, rowIndex++, patient, null)
                } else {
                    meds.forEach { writeRow(sheet, rowIndex++, patient, it) }
                }
            }
            Field.values().indices.forEach { sheet.setColumnWidth(it, 22 * 256) }

            writeInstructions(workbook)

            val output = context.contentResolver.openOutputStream(destination)
                ?: throw IOException("The file could not be created.")
            output.use { workbook.write(it) }
        }
    }

    private fun writeRow(sheet: Sheet, rowIndex: Int, patient: Patient, med: Medication?) {
        val row = sheet.createRow(rowIndex)
        val values = mapOf(
            Field.PATIENT_ID to patient.id,
            Field.MEDICATION_ID to med?.id.orEmpty(),
            Field.FIRST_NAME to patient.firstName,
            Field.LAST_NAME to patient.lastName,
            Field.DATE_OF_BIRTH to patient.dob?.toString().orEmpty(),
            Field.NOTES to patient.notes.orEmpty(),
            Field.CONDITIONS to patient.conditions.orEmpty(),
            Field.MEDICATION to med?.name.orEmpty(),
            Field.NDC to med?.ndc.orEmpty(),
            Field.STRENGTH to med?.strength.orEmpty(),
            Field.FORM to med?.form.orEmpty(),
            Field.DOSAGE_INSTRUCTIONS to med?.dosageInstructions.orEmpty(),
            Field.SCHEDULE_TIMES to med?.let { m ->
                m.scheduleTimes.joinToString(", ") { it.format(AdministrationTimeRules.HH_MM) }
            }.orEmpty(),
            Field.START_DATE to med?.startDate?.toString().orEmpty(),
            Field.END_DATE to med?.endDate?.toString().orEmpty(),
            Field.BOTTLE_EXPIRATION to med?.bottleExpiration?.toString().orEmpty(),
            Field.PURPOSE to med?.purpose.orEmpty(),
            Field.PRN to med?.let { if (it.isPrn) "Yes" else "No" }.orEmpty()
        )
        Field.values().forEachIndexed { index, field ->
            val value = values[field].orEmpty()
            if (value.isNotEmpty()) row.createCell(index).setCellValue(value)
        }
    }

    private fun writeInstructions(workbook: XSSFWorkbook) {
        val sheet = workbook.createSheet("Instructions")
        val lines = listOf(
            "CampMeds spreadsheet template",
            "Do not edit the Patient ID or Medication ID columns. They identify the records. Row order does not matter.",
            "To UPDATE existing records, use CampMeds > Settings > Import spreadsheet > Update existing records.",
            "To ADD new patients or medications, use Import new records and clear the ID cells on those rows.",
            "A blank cell means no change. Nothing is deleted because a row is missing from the sheet.",
            "To empty an optional value (Date of Birth, Last Name, Notes, Conditions, End Date, Bottle Expiration, Purpose), type $CLEAR_TOKEN in that cell. Update only.",
            "Dates: YYYY-MM-DD (for example 2026-07-15). Times: HH:MM, separated by commas (for example 08:00, 20:00).",
            "A new medication needs Medication, Dosage Instructions, Schedule Times, Start Date and PRN (Yes or No)."
        )
        lines.forEachIndexed { index, text -> sheet.createRow(index).createCell(0).setCellValue(text) }
        sheet.setColumnWidth(0, 120 * 256)
    }

    private fun headerStyle(workbook: XSSFWorkbook): XSSFCellStyle {
        val font = workbook.createFont().apply { bold = true }
        return workbook.createCellStyle().apply {
            setFont(font)
            fillForegroundColor = IndexedColors.GREY_25_PERCENT.index
            fillPattern = FillPatternType.SOLID_FOREGROUND
        }
    }
}
