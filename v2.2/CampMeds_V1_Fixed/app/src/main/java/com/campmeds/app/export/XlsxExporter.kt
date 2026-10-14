package com.campmeds.app.export

import android.content.Context
import android.net.Uri
import com.campmeds.app.data.entity.DoseLog
import com.campmeds.app.data.entity.Medication
import com.campmeds.app.data.entity.User
import org.apache.poi.ss.usermodel.CellStyle
import org.apache.poi.ss.usermodel.IndexedColors
import org.apache.poi.xssf.usermodel.XSSFWorkbook
import java.io.OutputStream
import java.time.format.DateTimeFormatter

/**
 * Generates one XLSX workbook with ONE WORKSHEET PER MEDICATION, rows = that medication's DoseLog entries.
 * Sheet name: "FirstName LastName MedicationName" (see [SheetNames]). Every row carries a dedicated
 * "Patient Name" column, so a sheet never relies on its name alone to identify the patient.
 *
 * Medications are never deleted or overwritten (renamed medications become a new record), so a retired or
 * deleted medication keeps its own sheet with its full history, and a renamed one gets a new sheet next to it.
 *
 * No data leaves the device except via this explicit, user-initiated export (spec section 7).
 */
class XlsxExporter {

    private val dtFmt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

    /** One medication, its patient's name, and all of its administration records. */
    data class MedicationSheet(
        val patientName: String,
        val medication: Medication,
        val doseLogs: List<DoseLog>,
        val changes: List<com.campmeds.app.data.entity.ChangeHistoryEntry> = emptyList()
    )

    data class ExportData(
        val sheets: List<MedicationSheet>,
        val usersById: Map<String, User>,
        /** History rows whose medication record no longer exists (should never happen); exported rather than lost. */
        val unlinkedLogs: List<DoseLog> = emptyList()
    )

    companion object {
        val COLUMNS = listOf(
            "Patient Name", "Medication", "Strength", "Form", "NDC", "Scheduled For",
            "Status", "Logged At", "Logged By", "Override?",
            // Appended (never inserted) so existing column positions stay the same. These identify the records
            // for reporting and for the spreadsheet import; the report itself is still not an update template.
            "Patient ID", "Medication ID", "Held Reason", "PRN?", "Medication Created By", "Medication Created At"
        )
    }

    fun export(context: Context, destination: Uri, data: ExportData) {
        XSSFWorkbook().use { workbook ->
            val exportedAt = java.time.OffsetDateTime.now().toString()
            workbook.properties.coreProperties.setDescription("CampMeds export copy. Exported at $exportedAt. Source records remain on the device.")
            workbook.properties.customProperties.addProperty("ExportedAt", exportedAt)
            val headerStyle = headerStyle(workbook)
            val usedNames = HashSet<String>()

            data.sheets.forEach { entry ->
                val med = entry.medication
                val sheetName = SheetNames.unique(med.sheetName ?: SheetNames.baseName(entry.patientName, med.name), usedNames)
                val sheet = workbook.createSheet(sheetName)
                writeHeader(sheet, headerStyle)

                entry.doseLogs.sortedBy { it.scheduledFor }.forEachIndexed { index, log ->
                    writeRow(sheet, index + 1, entry.patientName, med, log, data.usersById)
                }
                var rowIndex = entry.doseLogs.size + 3
                fun information(label: String, value: String) {
                    sheet.createRow(rowIndex++).apply { createCell(0).setCellValue(label); createCell(1).setCellValue(value) }
                }
                information("Exported at", exportedAt)
                information("Patient", entry.patientName)
                information("Medication", med.name)
                information("PRN", if (med.isPrn) "Yes" else "No")
                information("Medication created by", med.createdByName ?: "Unknown (legacy record)")
                information("Medication created at", med.createdAt?.toString() ?: "Unknown")
                information("Prescribed dates", "${med.startDate} — ${med.endDate ?: "Open"}")
                information("Effective end date", med.effectiveEndDate?.toString().orEmpty())
                information("Discontinued at", med.discontinuedAt?.toString().orEmpty())
                information("Change history", "Date | Field | Previous value | New value | Changed by")
                entry.changes.forEach { change ->
                    sheet.createRow(rowIndex++).apply {
                        listOf(change.changedAt.toString(), change.field, change.oldValue.orEmpty(), change.newValue.orEmpty(),
                            data.usersById[change.actorUserId]?.name ?: change.actorUserId ?: "Unknown").forEachIndexed { i, v -> createCell(i).setCellValue(v) }
                    }
                }
                setWidths(sheet)
            }

            if (data.unlinkedLogs.isNotEmpty()) {
                val sheet = workbook.createSheet(SheetNames.unique("Unlinked records", usedNames))
                writeHeader(sheet, headerStyle)
                data.unlinkedLogs.sortedBy { it.scheduledFor }.forEachIndexed { index, log ->
                    writeRow(sheet, index + 1, "", null, log, data.usersById)
                }
                var rowIndex = entry.doseLogs.size + 3
                fun information(label: String, value: String) {
                    sheet.createRow(rowIndex++).apply { createCell(0).setCellValue(label); createCell(1).setCellValue(value) }
                }
                information("Exported at", exportedAt)
                information("Patient", entry.patientName)
                information("Medication", med.name)
                information("PRN", if (med.isPrn) "Yes" else "No")
                information("Medication created by", med.createdByName ?: "Unknown (legacy record)")
                information("Medication created at", med.createdAt?.toString() ?: "Unknown")
                information("Prescribed dates", "${med.startDate} — ${med.endDate ?: "Open"}")
                information("Effective end date", med.effectiveEndDate?.toString().orEmpty())
                information("Discontinued at", med.discontinuedAt?.toString().orEmpty())
                information("Change history", "Date | Field | Previous value | New value | Changed by")
                entry.changes.forEach { change ->
                    sheet.createRow(rowIndex++).apply {
                        listOf(change.changedAt.toString(), change.field, change.oldValue.orEmpty(), change.newValue.orEmpty(),
                            data.usersById[change.actorUserId]?.name ?: change.actorUserId ?: "Unknown").forEachIndexed { i, v -> createCell(i).setCellValue(v) }
                    }
                }
                setWidths(sheet)
            }

            if (workbook.numberOfSheets == 0) {
                workbook.createSheet("No data")
            }

            val outputStream: OutputStream? = context.contentResolver.openOutputStream(destination)
            requireNotNull(outputStream) { "Unable to open export destination" }.use { workbook.write(it) }
        }
    }

    private fun writeHeader(sheet: org.apache.poi.ss.usermodel.Sheet, style: CellStyle) {
        val header = sheet.createRow(0)
        COLUMNS.forEachIndexed { i, title ->
            header.createCell(i).apply {
                setCellValue(title)
                cellStyle = style
            }
        }
    }

    private fun writeRow(
        sheet: org.apache.poi.ss.usermodel.Sheet,
        rowIndex: Int,
        patientName: String,
        med: Medication?,
        log: DoseLog,
        usersById: Map<String, User>
    ) {
        val row = sheet.createRow(rowIndex)
        val user = log.loggedByUserId?.let { usersById[it] }
        row.createCell(0).setCellValue(patientName)
        row.createCell(1).setCellValue(med?.name ?: "Unknown")
        row.createCell(2).setCellValue(med?.strength ?: "")
        row.createCell(3).setCellValue(med?.form ?: "")
        row.createCell(4).setCellValue(med?.ndc ?: "")
        row.createCell(5).setCellValue(log.scheduledFor.format(dtFmt))
        row.createCell(6).setCellValue(log.status.name)
        row.createCell(7).setCellValue(log.loggedAt.format(dtFmt))
        row.createCell(8).setCellValue(user?.name ?: "Unknown")
        row.createCell(9).setCellValue(if (log.wasOverride) "YES" else "")
        row.createCell(10).setCellValue(med?.patientId ?: "")
        row.createCell(11).setCellValue(med?.id ?: "")
        row.createCell(12).setCellValue(log.heldReason.orEmpty())
        row.createCell(13).setCellValue(if (med?.isPrn == true) "YES" else "NO")
        row.createCell(14).setCellValue(med?.createdByName ?: "Unknown (legacy record)")
        row.createCell(15).setCellValue(med?.createdAt?.toString().orEmpty())
    }

    // autoSizeColumn() needs java.awt font metrics, which do not exist on Android (it throws at runtime).
    // Fixed widths instead (units are 1/256 of a character).
    private fun setWidths(sheet: org.apache.poi.ss.usermodel.Sheet) {
        val widths = intArrayOf(24, 28, 16, 14, 16, 20, 12, 20, 20, 10, 38, 38, 32, 10, 24, 24)
        for (i in COLUMNS.indices) sheet.setColumnWidth(i, widths[i] * 256)
    }

    private fun headerStyle(workbook: XSSFWorkbook): CellStyle {
        val font = workbook.createFont().apply { bold = true }
        return workbook.createCellStyle().apply {
            setFont(font)
            fillForegroundColor = IndexedColors.GREY_25_PERCENT.index
            fillPattern = org.apache.poi.ss.usermodel.FillPatternType.SOLID_FOREGROUND
        }
    }
}
