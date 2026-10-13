package com.campmeds.app.spreadsheet

import android.content.Context
import android.net.Uri
import org.apache.poi.ss.usermodel.Cell
import org.apache.poi.ss.usermodel.CellType
import org.apache.poi.ss.usermodel.DateUtil
import org.apache.poi.xssf.usermodel.XSSFWorkbook
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.math.BigDecimal

/**
 * Reads the first worksheet of an .xlsx file on the device (Apache POI, already a dependency for export).
 * Nothing is sent anywhere. Limits: [ImportLimits]. Dates are converted to ISO yyyy-MM-dd so that Excel's
 * display formats cannot change their meaning. Formulas contribute their stored result.
 */
object XlsxSpreadsheetReader {

    class ReadException(message: String) : Exception(message)

    fun read(context: Context, uri: Uri, fileName: String): RawSheet {
        val bytes = context.contentResolver.openInputStream(uri)?.use { readLimited(it) }
            ?: throw ReadException("The file could not be opened.")
        if (bytes.size < 4 || bytes[0] != 'P'.code.toByte() || bytes[1] != 'K'.code.toByte()) {
            throw ReadException("This file is not an Excel .xlsx workbook.")
        }
        XSSFWorkbook(ByteArrayInputStream(bytes)).use { workbook ->
            if (workbook.numberOfSheets == 0) throw ReadException("The workbook has no worksheets.")
            val sheet = workbook.getSheetAt(0)

            val allRows = mutableListOf<RawRow>()
            for (index in 0..sheet.lastRowNum) {
                val row = sheet.getRow(index) ?: continue
                val width = row.lastCellNum.toInt().coerceAtLeast(0).coerceAtMost(ImportLimits.MAX_COLUMNS)
                val cells = (0 until width).map { cellText(row.getCell(it)) }
                if (cells.any { it.isNotBlank() }) allRows += RawRow(index + 1, cells)
            }
            if (allRows.isEmpty()) throw ReadException("The first worksheet is empty.")

            val header = allRows.first()
            val headers = header.cells.map { it.trim() }.dropLastWhile { it.isEmpty() }
            val data = allRows.drop(1)
            if (data.size > ImportLimits.MAX_ROWS) {
                throw ReadException("The sheet has more than ${ImportLimits.MAX_ROWS} rows. Split it into smaller files.")
            }
            return RawSheet(fileName = fileName, headers = headers, rows = data)
        }
    }

    private fun readLimited(input: InputStream): ByteArray {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(16 * 1024)
        var total = 0L
        while (true) {
            val n = input.read(buffer)
            if (n < 0) break
            total += n
            if (total > ImportLimits.MAX_FILE_BYTES) {
                throw ReadException("The file is larger than ${ImportLimits.MAX_FILE_BYTES / (1024 * 1024)} MB.")
            }
            out.write(buffer, 0, n)
        }
        return out.toByteArray()
    }

    private fun cellText(cell: Cell?): String {
        if (cell == null) return ""
        return when (cell.cellType) {
            CellType.STRING -> cell.stringCellValue.trim()
            CellType.NUMERIC -> numericText(cell)
            CellType.BOOLEAN -> cell.booleanCellValue.toString().uppercase()
            CellType.FORMULA -> when (cell.cachedFormulaResultType) {
                CellType.STRING -> cell.stringCellValue.trim()
                CellType.NUMERIC -> numericText(cell)
                CellType.BOOLEAN -> cell.booleanCellValue.toString().uppercase()
                else -> ""
            }
            else -> ""
        }
    }

    private fun numericText(cell: Cell): String =
        if (DateUtil.isCellDateFormatted(cell)) {
            cell.localDateTimeCellValue.toLocalDate().toString()
        } else {
            BigDecimal.valueOf(cell.numericCellValue).stripTrailingZeros().toPlainString()
        }
}
