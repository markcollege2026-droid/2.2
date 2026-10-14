package com.campmeds.app.export

import com.campmeds.app.data.entity.*
import com.campmeds.app.domain.MedicationChanges
import org.apache.poi.xssf.usermodel.XSSFWorkbook
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.time.*

class ExportSnapshotTest {
    @Test fun repeatedExportsAreCopiesWithStableSheetsHeldReasonsAndAudit() {
        val date = LocalDate.of(2026, 7, 15)
        val original = Medication(patientId = "p", ndc = "", name = "Old", strength = "1", form = "tablet",
            dosageInstructions = "1", scheduleTimes = listOf(LocalTime.of(8, 0)), startDate = date,
            sheetName = "Camper Old", createdByUserId = "u", createdByName = "Nurse", createdAt = date.atStartOfDay(), isPrn = true)
        val edited = original.copy(name = "New", discontinuedOn = date.plusDays(1))
        val log = DoseLog(medicationId = original.id, scheduledFor = date.atTime(8, 0), status = DoseStatus.HELD,
            loggedAt = date.atTime(8, 3), loggedByUserId = null, heldReason = "Camper away")
        val data = XlsxExporter.ExportData(listOf(XlsxExporter.MedicationSheet("Camper", edited, listOf(log),
            MedicationChanges.between(original, edited, "u", date.atTime(9, 0)))), emptyMap())
        val stamp = OffsetDateTime.parse("2026-07-15T12:30:00+03:00")
        repeat(2) {
            val bytes = ByteArrayOutputStream().also { XlsxExporter().write(it, data, stamp) }.toByteArray()
            XSSFWorkbook(bytes.inputStream()).use { wb ->
                assertEquals(1, wb.numberOfSheets)
                val sheet = wb.getSheet("Camper Old")
                assertNotNull(sheet)
                assertEquals("HELD", sheet.getRow(1).getCell(6).stringCellValue)
                assertEquals("Camper away", sheet.getRow(1).getCell(13).stringCellValue)
                assertEquals("Nurse", sheet.getRow(1).getCell(14).stringCellValue)
                assertTrue(wb.properties.customProperties.getProperty("CampMeds export date/time").lpwstr.contains("+03:00"))
                assertEquals(1, sheet.count { row -> row.getCell(6)?.stringCellValue == "HELD" })
                assertTrue(sheet.any { row -> row.getCell(2)?.stringCellValue == "Old" && row.getCell(3)?.stringCellValue == "New" })
            }
        }
        assertEquals(1, data.sheets.single().doseLogs.size)
        assertEquals(log, data.sheets.single().doseLogs.single())
    }
}
