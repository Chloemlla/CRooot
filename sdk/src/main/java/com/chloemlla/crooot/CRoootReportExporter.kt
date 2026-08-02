package com.chloemlla.crooot

/** Stable local serialization helpers. No network or implicit persistence is performed. */
object CRoootReportExporter {
    fun toText(report: CRoootLocalReport): String = buildString {
        appendLine("CRooot local security report")
        appendLine("Schema       : ${report.schemaVersion}")
        appendLine("SDK version  : ${report.sdkVersion}")
        appendLine("Report ID    : ${report.reportId}")
        appendLine("Started      : ${report.startedAtMillis}")
        appendLine("Duration     : ${report.durationMillis} ms")
        appendLine("Profile      : ${report.profile}")
        appendLine("Overall      : ${report.overallStatus}")
        appendLine("Rooted       : ${report.rooted}")
        appendLine("Suspicious   : ${report.suspicious}")
        appendLine("Complete     : ${report.complete}")
        appendLine()
        appendLine("Detector summaries")
        report.detectorSummaries.forEach { detector ->
            appendLine(
                "- ${detector.detectorId}: ${detector.status} " +
                    "findings=${detector.findingCount}, high=${detector.highSeverityCount}, " +
                    "warnings=${detector.warningCount}, executed=${detector.executed}" +
                    (detector.errorMessage?.let { ", error=$it" } ?: ""),
            )
        }
        appendLine()
        appendLine("Findings")
        report.findings.forEach { finding ->
            appendLine("[${finding.status}/${finding.severity}] ${finding.detectorId}/${finding.id}")
            appendLine("  ${finding.title}: ${finding.summary}")
            finding.evidence.forEach { evidence ->
                appendLine("  ${evidence.key}: ${evidence.value} (${evidence.privacy})")
            }
            finding.recommendation?.let { appendLine("  Recommendation: $it") }
        }
        appendLine()
        appendLine("Environment")
        appendLine("- Android API: ${report.environment.androidApiLevel}")
        appendLine("- ABI: ${report.environment.abi}")
        report.environment.packageVisibility?.let { appendLine("- Package visibility: $it") }
        report.environment.nativeLibrariesAvailable?.let { appendLine("- Native libraries: $it") }
        appendLine()
        appendLine("Limitations")
        report.limitations.forEach { appendLine("- $it") }
    }

    /** JSON is generated from the stable DTO only; raw detector objects are never serialized. */
    fun toJson(report: CRoootLocalReport): String = jsonObject(
        listOf(
            "schemaVersion" to report.schemaVersion,
            "sdkVersion" to report.sdkVersion,
            "reportId" to report.reportId,
            "startedAtMillis" to report.startedAtMillis,
            "durationMillis" to report.durationMillis,
            "profile" to report.profile.name,
            "overallStatus" to report.overallStatus.name,
            "rooted" to report.rooted,
            "suspicious" to report.suspicious,
            "complete" to report.complete,
            "detectorSummaries" to report.detectorSummaries.map { summary ->
                jsonObject(
                    listOf(
                        "detectorId" to summary.detectorId,
                        "title" to summary.title,
                        "status" to summary.status.name,
                        "findingCount" to summary.findingCount,
                        "highSeverityCount" to summary.highSeverityCount,
                        "warningCount" to summary.warningCount,
                        "executed" to summary.executed,
                        "reportType" to summary.reportType,
                        "errorMessage" to summary.errorMessage,
                    ),
                )
            },
            "findings" to report.findings.map { finding ->
                jsonObject(
                    listOf(
                        "id" to finding.id,
                        "detectorId" to finding.detectorId,
                        "category" to finding.category,
                        "severity" to finding.severity.name,
                        "status" to finding.status.name,
                        "title" to finding.title,
                        "summary" to finding.summary,
                        "recommendation" to finding.recommendation,
                        "confidence" to finding.confidence.name,
                        "source" to finding.source.name,
                        "evidence" to finding.evidence.map { evidence ->
                            jsonObject(
                                listOf(
                                    "key" to evidence.key,
                                    "value" to evidence.value,
                                    "privacy" to evidence.privacy.name,
                                ),
                            )
                        },
                    ),
                )
            },
            "environment" to jsonObject(
                listOf(
                    "androidApiLevel" to report.environment.androidApiLevel,
                    "abi" to report.environment.abi,
                    "packageVisibility" to report.environment.packageVisibility,
                    "nativeLibrariesAvailable" to report.environment.nativeLibrariesAvailable,
                ),
            ),
            "limitations" to report.limitations,
        ),
    )

    /** HTML is intentionally a static escaped document with no scripts or network resources. */
    fun toHtml(report: CRoootLocalReport): String {
        val text = toText(report).escapeHtml()
        return """
            <!doctype html>
            <html><head><meta charset="utf-8"><title>CRooot local report</title>
            <style>body{font-family:system-ui,sans-serif;max-width:1000px;margin:2rem auto;padding:0 1rem}pre{white-space:pre-wrap;background:#f5f5f5;padding:1rem;border-radius:8px}</style>
            </head><body><h1>CRooot local security report</h1><pre>$text</pre></body></html>
        """.trimIndent()
    }

    private fun jsonObject(fields: List<Pair<String, Any?>>): String =
        fields.joinToString(prefix = "{", postfix = "}", separator = ",") { (name, value) ->
            "${quote(name)}:${jsonValue(value)}"
        }

    private fun jsonValue(value: Any?): String = when (value) {
        null -> "null"
        is String -> quote(value)
        is Number, is Boolean -> value.toString()
        is List<*> -> value.joinToString(prefix = "[", postfix = "]", separator = ",") { jsonValue(it) }
        else -> quote(value.toString())
    }

    private fun quote(value: String): String = buildString {
        append('"')
        value.forEach { character ->
            when (character) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                '\b' -> append("\\b")
                '' -> append("\\f")
                else -> if (character.code < 0x20) {
                    append("\\u%04x".format(character.code))
                } else {
                    append(character)
                }
            }
        }
        append('"')
    }

    private fun String.escapeHtml(): String = replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&#39;")
}
