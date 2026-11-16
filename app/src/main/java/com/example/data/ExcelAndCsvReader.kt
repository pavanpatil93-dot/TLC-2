package com.example.data

import android.content.Context
import android.net.Uri
import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.InputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream

data class ParsedWorksheet(
    val sheetName: String,
    val rows: List<List<String>>
)

object ExcelAndCsvReader {

    /**
     * Reads an Excel (.xlsx, .xls) or CSV/TSV file from a Content Uri or Stream,
     * supporting multiple worksheets from one Excel workbook.
     */
    fun readWorkbook(context: Context, uri: Uri, fileName: String): List<ParsedWorksheet> {
        val lowerName = fileName.lowercase()
        return if (lowerName.endsWith(".xlsx")) {
            try {
                readXlsxWorkbook(context, uri)
            } catch (e: Exception) {
                // Fallback to text parsing if zip fails
                readTextAsWorksheet(context, uri, fileName)
            }
        } else {
            // CSV / TSV / plain text spreadsheet
            readTextAsWorksheet(context, uri, fileName)
        }
    }

    private fun readTextAsWorksheet(context: Context, uri: Uri, fileName: String): List<ParsedWorksheet> {
        val inputStream = context.contentResolver.openInputStream(uri) ?: return emptyList()
        val text = inputStream.bufferedReader().use { it.readText() }
        val lines = text.lines().map { it.trim() }.filter { it.isNotBlank() }
        if (lines.isEmpty()) return emptyList()

        val delimiter = if (lines.any { it.contains("\t") }) "\t" else ","
        val parsedRows = lines.map { line ->
            parseDelimitedLine(line, delimiter)
        }

        val sheetTitle = fileName.substringBeforeLast('.').ifBlank { "Master Inventory" }
        return listOf(ParsedWorksheet(sheetName = sheetTitle, rows = parsedRows))
    }

    private fun parseDelimitedLine(line: String, delimiter: String): List<String> {
        if (delimiter == "\t") {
            return line.split("\t").map { it.trim().removeSurrounding("\"") }
        }
        // Comma separated with quote support
        val tokens = mutableListOf<String>()
        val sb = StringBuilder()
        var inQuotes = false
        for (i in line.indices) {
            val c = line[i]
            when {
                c == '\"' -> inQuotes = !inQuotes
                c == ',' && !inQuotes -> {
                    tokens.add(sb.toString().trim().removeSurrounding("\""))
                    sb.clear()
                }
                else -> sb.append(c)
            }
        }
        tokens.add(sb.toString().trim().removeSurrounding("\""))
        return tokens
    }

    /**
     * Native XLSX parser using ZipInputStream and XmlPullParser.
     * Extracts all worksheets, sheet names from workbook.xml, shared strings, and row values.
     */
    private fun readXlsxWorkbook(context: Context, uri: Uri): List<ParsedWorksheet> {
        val sharedStrings = mutableListOf<String>()
        val sheetNames = mutableListOf<String>()
        val sheetXmlContents = mutableMapOf<Int, String>()

        // Pass 1: Read workbook.xml for sheet names and sharedStrings.xml
        context.contentResolver.openInputStream(uri)?.use { rawStream ->
            ZipInputStream(rawStream).use { zip ->
                var entry: ZipEntry? = zip.nextEntry
                while (entry != null) {
                    val name = entry.name
                    when {
                        name.equals("xl/sharedStrings.xml", ignoreCase = true) -> {
                            val content = zip.bufferedReader().readText()
                            sharedStrings.addAll(parseSharedStrings(content))
                        }
                        name.equals("xl/workbook.xml", ignoreCase = true) -> {
                            val content = zip.bufferedReader().readText()
                            sheetNames.addAll(parseWorkbookSheetNames(content))
                        }
                        name.matches("xl/worksheets/sheet[0-9]+\\.xml".toRegex()) -> {
                            val index = name.filter { it.isDigit() }.toIntOrNull() ?: 1
                            sheetXmlContents[index] = zip.bufferedReader().readText()
                        }
                    }
                    zip.closeEntry()
                    entry = zip.nextEntry
                }
            }
        }

        val resultSheets = mutableListOf<ParsedWorksheet>()
        val sortedIndices = sheetXmlContents.keys.sorted()

        sortedIndices.forEachIndexed { i, sheetIndex ->
            val sheetName = sheetNames.getOrNull(i) ?: "Sheet $sheetIndex"
            val xml = sheetXmlContents[sheetIndex] ?: ""
            val rows = parseSheetXmlRows(xml, sharedStrings)
            if (rows.isNotEmpty()) {
                resultSheets.add(ParsedWorksheet(sheetName = sheetName, rows = rows))
            }
        }

        // If no worksheets were parsed, return a fallback empty list
        return if (resultSheets.isNotEmpty()) resultSheets else readTextAsWorksheet(context, uri, "Excel_Workbook.xlsx")
    }

    private fun parseSharedStrings(xmlContent: String): List<String> {
        val strings = mutableListOf<String>()
        val parser = Xml.newPullParser()
        parser.setInput(xmlContent.reader())

        var eventType = parser.eventType
        var currentText = StringBuilder()
        var insideT = false

        while (eventType != XmlPullParser.END_DOCUMENT) {
            when (eventType) {
                XmlPullParser.START_TAG -> {
                    if (parser.name.equals("t", ignoreCase = true)) {
                        insideT = true
                    } else if (parser.name.equals("si", ignoreCase = true)) {
                        currentText.clear()
                    }
                }
                XmlPullParser.TEXT -> {
                    if (insideT) {
                        currentText.append(parser.text)
                    }
                }
                XmlPullParser.END_TAG -> {
                    if (parser.name.equals("t", ignoreCase = true)) {
                        insideT = false
                    } else if (parser.name.equals("si", ignoreCase = true)) {
                        strings.add(currentText.toString())
                    }
                }
            }
            eventType = parser.next()
        }
        return strings
    }

    private fun parseWorkbookSheetNames(xmlContent: String): List<String> {
        val names = mutableListOf<String>()
        val parser = Xml.newPullParser()
        parser.setInput(xmlContent.reader())

        var eventType = parser.eventType
        while (eventType != XmlPullParser.END_DOCUMENT) {
            if (eventType == XmlPullParser.START_TAG && parser.name.equals("sheet", ignoreCase = true)) {
                val nameAttr = parser.getAttributeValue(null, "name")
                if (!nameAttr.isNullOrBlank()) {
                    names.add(nameAttr)
                }
            }
            eventType = parser.next()
        }
        return names
    }

    private fun parseSheetXmlRows(xmlContent: String, sharedStrings: List<String>): List<List<String>> {
        val rows = mutableListOf<List<String>>()
        val parser = Xml.newPullParser()
        parser.setInput(xmlContent.reader())

        var eventType = parser.eventType
        var currentRow = mutableListOf<String>()
        var currentCellValue = StringBuilder()
        var currentCellRef = ""
        var isStringCell = false
        var isInlineString = false
        var insideV = false
        var insideT = false

        while (eventType != XmlPullParser.END_DOCUMENT) {
            when (eventType) {
                XmlPullParser.START_TAG -> {
                    val tagName = parser.name
                    when {
                        tagName.equals("row", ignoreCase = true) -> {
                            currentRow = mutableListOf()
                        }
                        tagName.equals("c", ignoreCase = true) -> {
                            currentCellValue.clear()
                            currentCellRef = parser.getAttributeValue(null, "r") ?: ""
                            val type = parser.getAttributeValue(null, "t")
                            isStringCell = type == "s"
                            isInlineString = type == "inlineStr"
                        }
                        tagName.equals("v", ignoreCase = true) -> {
                            insideV = true
                        }
                        tagName.equals("t", ignoreCase = true) -> {
                            if (isInlineString) insideT = true
                        }
                    }
                }
                XmlPullParser.TEXT -> {
                    if (insideV || insideT) {
                        currentCellValue.append(parser.text)
                    }
                }
                XmlPullParser.END_TAG -> {
                    val tagName = parser.name
                    when {
                        tagName.equals("v", ignoreCase = true) -> {
                            insideV = false
                        }
                        tagName.equals("t", ignoreCase = true) -> {
                            insideT = false
                        }
                        tagName.equals("c", ignoreCase = true) -> {
                            val rawVal = currentCellValue.toString()
                            val cellText = if (isStringCell) {
                                val sIndex = rawVal.toIntOrNull()
                                if (sIndex != null && sIndex in sharedStrings.indices) {
                                    sharedStrings[sIndex]
                                } else rawVal
                            } else {
                                rawVal
                            }

                            // Align with column position from cell reference (e.g. "B5" -> index 1)
                            val targetCol = cellRefToColIndex(currentCellRef)
                            if (targetCol >= 0) {
                                while (currentRow.size < targetCol) {
                                    currentRow.add("")
                                }
                                currentRow.add(cellText)
                            } else {
                                currentRow.add(cellText)
                            }
                        }
                        tagName.equals("row", ignoreCase = true) -> {
                            if (currentRow.any { it.isNotBlank() }) {
                                rows.add(currentRow)
                            }
                        }
                    }
                }
            }
            eventType = parser.next()
        }
        return rows
    }

    private fun cellRefToColIndex(cellRef: String): Int {
        val colLetters = cellRef.filter { it.isLetter() }.uppercase()
        if (colLetters.isEmpty()) return -1
        var col = 0
        for (char in colLetters) {
            col = col * 26 + (char - 'A' + 1)
        }
        return col - 1
    }
}

