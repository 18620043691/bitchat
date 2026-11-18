package com.bitchat.droid

import android.content.Context
import android.os.Build
import java.io.File
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale

internal enum class DiagnosticCategory(val label: String) {
    BLUETOOTH("Bluetooth operation failed"),
    MEDIA("Media operation failed"),
    PERMISSION("Permission was unavailable"),
    UPDATE("Update check or download failed"),
    APP("App operation failed")
}

internal data class DiagnosticEvent(val at: Long, val category: DiagnosticCategory)

internal object DiagnosticEventLog {
    private const val CAPACITY = 20
    private val entries = ArrayDeque<DiagnosticEvent>()

    @Synchronized
    fun record(category: DiagnosticCategory, at: Long = System.currentTimeMillis()) {
        entries.addLast(DiagnosticEvent(at, category))
        while (entries.size > CAPACITY) entries.removeFirst()
    }

    @Synchronized
    fun snapshot(): List<DiagnosticEvent> = entries.toList()
}

internal object DiagnosticDescriptionRedactor {
    private val patterns = listOf(
        Regex("(?is)-----BEGIN [^-]*PRIVATE KEY-----.*?-----END [^-]*PRIVATE KEY-----"),
        Regex("(?i)\\bBearer\\s+[A-Za-z0-9._~+/-]+=*"),
        Regex("(?i)\\b(?:token|secret|password|api[_-]?key)\\s*[:=]\\s*[^\\s,;]+"),
        Regex("\\b(?:[0-9a-fA-F]{2}:){5}[0-9a-fA-F]{2}\\b"),
        Regex("\\b[0-9a-fA-F]{8}-[0-9a-fA-F-]{27,}\\b"),
        Regex("\\b[0-9a-fA-F]{8,}\\b"),
        Regex("\\b[A-Z0-9._%+-]+@[A-Z0-9.-]+\\.[A-Z]{2,}\\b", RegexOption.IGNORE_CASE)
    )

    fun redact(description: String): String {
        var result = description.take(2_000)
        patterns.forEach { result = it.replace(result, "[redacted]") }
        return result.filter { it == '\n' || it == '\t' || it >= ' ' }
    }
}

internal class DiagnosticReportWriter(private val context: Context) {
    fun create(description: String, recentEvents: List<DiagnosticEvent> = DiagnosticEventLog.snapshot()): File {
        val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
        @Suppress("DEPRECATION")
        val versionCode = if (Build.VERSION.SDK_INT >= 28) packageInfo.longVersionCode else packageInfo.versionCode.toLong()
        val versionName = packageInfo.versionName ?: "unknown"
        val timestamp = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
            timeZone = java.util.TimeZone.getTimeZone("UTC")
        }.format(Date())
        val eventTime = SimpleDateFormat("HH:mm:ss'Z'", Locale.US).apply {
            timeZone = java.util.TimeZone.getTimeZone("UTC")
        }
        val errors = recentEvents.takeLast(20).joinToString("\n") { event ->
            "- ${eventTime.format(Date(event.at))}: ${event.category.label}"
        }.ifBlank { "- No recent categorized app errors were recorded." }
        val safeDescription = DiagnosticDescriptionRedactor.redact(description).ifBlank { "(No description provided.)" }
        val model = listOf(Build.MANUFACTURER, Build.MODEL)
            .filter { it.isNotBlank() }
            .distinct()
            .joinToString(" ")
            .take(100)
        val report = """
            bitchat diagnostic report
            Created: $timestamp
            App: $versionName ($versionCode)
            Android: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})
            Device model: ${model.ifBlank { "unknown" }}

            User description (secrets and identifiers redacted):
            $safeDescription

            Recent categorized errors (message content and raw system logs are excluded):
            $errors

            Privacy: This file was created on-device by your request. It was not uploaded.
            App chat history is never read. Your description is manually supplied, common secrets and identifiers are redacted; review it before sharing.
        """.trimIndent()

        val directory = File(context.cacheDir, "feedback").apply { mkdirs() }
        return File(directory, "bitchat-diagnostics-${System.currentTimeMillis()}.txt").apply {
            writeText(report, Charsets.UTF_8)
        }
    }
}
