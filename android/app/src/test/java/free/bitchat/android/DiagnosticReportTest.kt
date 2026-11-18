package com.bitchat.droid

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticReportTest {
    @Test
    fun descriptionRedactsSecretsAndSensitiveIdentifiers() {
        val text = "token=abc123 peer 01234567-89ab-cdef-0123-456789abcdef " +
            "-----BEGIN PRIVATE KEY-----secret-----END PRIVATE KEY-----"

        val safe = DiagnosticDescriptionRedactor.redact(text)

        assertFalse(safe.contains("abc123"))
        assertFalse(safe.contains("01234567-89ab"))
        assertFalse(safe.contains("secret"))
        assertTrue(safe.contains("[redacted]"))
    }

    @Test
    fun recentErrorsAreBoundedAndStoreCategoriesOnly() {
        repeat(30) { DiagnosticEventLog.record(DiagnosticCategory.BLUETOOTH, at = it.toLong()) }

        val events = DiagnosticEventLog.snapshot()

        assertTrue(events.size <= 20)
        assertTrue(events.all { it.category == DiagnosticCategory.BLUETOOTH })
    }
}
