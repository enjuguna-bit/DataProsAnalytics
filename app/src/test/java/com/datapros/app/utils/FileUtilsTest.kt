package com.datapros.app.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FileUtilsTest {

    @Test
    fun buildImportFileName_keepsKnownCsvExtension() {
        val name = FileUtils.buildImportFileName(
            rawName = "Cemes - Loans Due - 2026-06-13T070934.689.csv",
            mimeType = "text/csv",
            timestamp = 123L
        )

        assertEquals("Cemes - Loans Due - 2026-06-13T070934.689_123.csv", name)
    }

    @Test
    fun buildImportFileName_infersCsvWhenDisplayNameHasNoExtension() {
        val name = FileUtils.buildImportFileName(
            rawName = "Cemes - Loans Due - 2026-06-13T070934.689",
            mimeType = "text/csv",
            timestamp = 123L
        )

        assertEquals("Cemes - Loans Due - 2026-06-13T070934.689_123.csv", name)
    }

    @Test
    fun buildImportFileName_infersSpreadsheetExtensionFromMimeType() {
        val name = FileUtils.buildImportFileName(
            rawName = "Sod CR per portfolio (2)",
            mimeType = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
            timestamp = 123L
        )

        assertEquals("Sod CR per portfolio (2)_123.xlsx", name)
    }

    @Test
    fun supportedImportMimeTypes_includeCsvAndExcel() {
        val mimeTypes = FileUtils.supportedImportMimeTypes()

        assertTrue(mimeTypes.contains("text/csv"))
        assertTrue(mimeTypes.contains("application/vnd.ms-excel"))
        assertTrue(mimeTypes.contains("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
    }
}
