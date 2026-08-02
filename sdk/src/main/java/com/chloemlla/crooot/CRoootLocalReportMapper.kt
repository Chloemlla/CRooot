package com.chloemlla.crooot

import android.os.Build
import com.juanma0511.rootdetector.model.CheckStatus
import com.juanma0511.rootdetector.model.DetectionItem
import com.juanma0511.rootdetector.model.HwCheckItem
import com.juanma0511.rootdetector.model.Severity
import java.lang.reflect.Field
import java.lang.reflect.Modifier
import java.util.UUID

/** Converts SDK-internal scan data into the stable, privacy-aware consumer report model. */
internal object CRoootLocalReportMapper {
    private val duckKeys = listOf(
        "bootloader", "customRom", "dangerousApps", "deviceInfo", "kernel", "lsposed",
        "memory", "mount", "nativeRoot", "playIntegrityFix", "selinux", "su",
        "systemProperties", "tee", "virtualization", "zygisk",
    )

    private val allowedDuckFields = setOf(
        "stage", "state", "verdict", "tier", "headline", "summary", "collapsedSummary",
        "trustSummary", "failureMessage", "errorMessage", "nativeAvailable", "packageVisibility",
        "paradoxDetected", "rootDetected", "kernelSuDetected", "aPatchDetected", "magiskDetected",
        "susfsDetected", "hasDangerSignals", "hasWarningSignals", "hasIndicators", "fullyClean",
        "dangerFindingCount", "dangerSignalCount", "warningSignalCount", "modifiedFunctionCount",
        "hitCount", "classHitCount", "nativeStrongHitCount", "heuristicHitCount", "tamperScore",
        "evidenceCount",
    )

    fun map(result: CRoootScanResult, options: CRoootReportOptions, startedAtMillis: Long): CRoootLocalReport {
        val findings = buildList {
            result.kkndRoot.items.forEach { add(rootFinding(it, options.includeSensitiveEvidence)) }
            result.kkndHardware?.items?.forEach { add(hardwareFinding(it, options.includeSensitiveEvidence)) }
            duckKeys.forEach { key ->
                result.duckReports[key]?.let { add(duckFinding(key, it, options.includeSensitiveEvidence)) }
            }
        }
        val summaries = buildList {
            add(rootSummary(result.kkndRoot.items))
            add(hardwareSummary(result.kkndHardware))
            duckKeys.forEach { key -> add(duckSummary(key, result.duckReports[key], result.duckReports.containsKey(key))) }
        }
        val limitations = buildList {
            if (result.kkndHardware == null) add("KKND hardware checks were not run.")
            if (result.duckReports.size < duckKeys.size) add("${duckKeys.size - result.duckReports.size} Duck detector(s) were not run.")
            if (result.kkndHardware?.items?.any { it.status == CheckStatus.UNKNOWN } == true) {
                add("Some hardware checks returned UNKNOWN and must not be treated as PASS.")
            }
            if (result.duckReports.values.any { it == null }) add("At least one requested Duck detector returned no report.")
            add("CRooot reports heuristic evidence; a clean result is not proof that a device is malware-free.")
        }.distinct()
        return CRoootLocalReport(
            schemaVersion = 1,
            sdkVersion = CRoootSdk.SDK_VERSION,
            reportId = UUID.randomUUID().toString(),
            startedAtMillis = startedAtMillis,
            durationMillis = result.durationMs,
            profile = options.profile,
            overallStatus = overallStatus(result, summaries),
            rooted = result.isRooted,
            suspicious = result.isSuspicious,
            complete = true,
            detectorSummaries = summaries,
            findings = findings,
            environment = if (options.includeEnvironment) {
                environment()
            } else {
                CRoootScanEnvironment(
                    androidApiLevel = 0,
                    abi = "redacted",
                )
            },
            limitations = limitations,
        )
    }

    private fun rootFinding(item: DetectionItem, includeSensitiveEvidence: Boolean) = CRoootFinding(
        id = "kknd.root.${item.id}", detectorId = "kkndRoot", category = item.category.name,
        severity = if (item.severity == Severity.HIGH) CRoootFindingSeverity.HIGH else CRoootFindingSeverity.MEDIUM,
        status = if (item.detected) {
            if (item.severity == Severity.HIGH) CRoootReportStatus.FAIL else CRoootReportStatus.WARN
        } else CRoootReportStatus.PASS,
        title = item.name, summary = item.description,
        evidence = listOfNotNull(item.detail?.let {
            CRoootEvidence("detail", redact(it, includeSensitiveEvidence), CRoootEvidencePrivacy.DEVICE_SENSITIVE)
        }),
        recommendation = if (item.detected) "Review this signal together with the other CRooot findings." else null,
        confidence = if (item.severity == Severity.HIGH) CRoootConfidence.HIGH else CRoootConfidence.MEDIUM,
        source = CRoootEvidenceSource.KKND,
    )

    private fun hardwareFinding(item: HwCheckItem, includeSensitiveEvidence: Boolean) = CRoootFinding(
        id = "kknd.hardware.${item.id}", detectorId = "kkndHardware", category = item.group.name,
        severity = when (item.status) {
            CheckStatus.FAIL -> CRoootFindingSeverity.HIGH
            CheckStatus.WARN -> CRoootFindingSeverity.MEDIUM
            else -> CRoootFindingSeverity.INFO
        },
        status = when (item.status) {
            CheckStatus.PASS -> CRoootReportStatus.PASS
            CheckStatus.WARN -> CRoootReportStatus.WARN
            CheckStatus.FAIL -> CRoootReportStatus.FAIL
            CheckStatus.UNKNOWN -> CRoootReportStatus.UNKNOWN
        },
        title = item.name, summary = item.description,
        evidence = buildList {
            add(CRoootEvidence("value", redact(item.value, includeSensitiveEvidence), CRoootEvidencePrivacy.DEVICE_SENSITIVE))
            item.expected?.let { add(CRoootEvidence("expected", redact(it, includeSensitiveEvidence), CRoootEvidencePrivacy.DEVICE_SENSITIVE)) }
            item.detail?.let { add(CRoootEvidence("detail", redact(it, includeSensitiveEvidence), CRoootEvidencePrivacy.DEVICE_SENSITIVE)) }
        },
        recommendation = if (item.status == CheckStatus.WARN || item.status == CheckStatus.FAIL) {
            "Review the hardware evidence before making a trust decision."
        } else null,
        confidence = if (item.status == CheckStatus.FAIL) CRoootConfidence.HIGH else CRoootConfidence.MEDIUM,
        source = CRoootEvidenceSource.KKND,
    )

    private fun duckFinding(key: String, report: Any, includeSensitiveEvidence: Boolean): CRoootFinding {
        val fields = selectedDuckFields(report)
        val status = duckStatus(fields)
        return CRoootFinding(
            id = "duck.$key.report", detectorId = key, category = "DUCK",
            severity = when (status) {
                CRoootReportStatus.FAIL, CRoootReportStatus.ERROR -> CRoootFindingSeverity.HIGH
                CRoootReportStatus.WARN -> CRoootFindingSeverity.MEDIUM
                else -> CRoootFindingSeverity.INFO
            }, status = status,
            title = "$key detector report",
            summary = fields["headline"] ?: fields["summary"] ?: "${report.javaClass.simpleName} is available.",
            evidence = fields.filterKeys { it != "headline" && it != "summary" }.map { (field, value) ->
                CRoootEvidence(field, redact(value, includeSensitiveEvidence), duckPrivacy(field))
            },
            confidence = CRoootConfidence.MEDIUM, source = CRoootEvidenceSource.DUCK,
        )
    }

    private fun rootSummary(items: List<DetectionItem>) = CRoootDetectorSummary(
        detectorId = "kkndRoot", title = "KKND root detection",
        status = when {
            items.any { it.detected && it.severity == Severity.HIGH } -> CRoootReportStatus.FAIL
            items.any { it.detected } -> CRoootReportStatus.WARN
            else -> CRoootReportStatus.PASS
        }, findingCount = items.size,
        highSeverityCount = items.count { it.detected && it.severity == Severity.HIGH },
        warningCount = items.count { it.detected && it.severity == Severity.WARNING },
        executed = true, reportType = "ScanResult",
    )

    private fun hardwareSummary(result: com.juanma0511.rootdetector.model.HwScanResult?): CRoootDetectorSummary {
        if (result == null) return CRoootDetectorSummary(
            "kkndHardware", "KKND hardware integrity", CRoootReportStatus.NOT_RUN, 0, 0, 0, false,
        )
        return CRoootDetectorSummary(
            detectorId = "kkndHardware", title = "KKND hardware integrity",
            status = when {
                result.failCount > 0 -> CRoootReportStatus.FAIL
                result.warnCount > 0 -> CRoootReportStatus.WARN
                result.items.any { it.status == CheckStatus.UNKNOWN } -> CRoootReportStatus.UNKNOWN
                else -> CRoootReportStatus.PASS
            }, findingCount = result.items.size, highSeverityCount = result.failCount,
            warningCount = result.warnCount, executed = true, reportType = "HwScanResult",
        )
    }

    private fun duckSummary(key: String, report: Any?, present: Boolean): CRoootDetectorSummary {
        if (!present) return CRoootDetectorSummary(key, "$key detector", CRoootReportStatus.NOT_RUN, 0, 0, 0, false)
        if (report == null) return CRoootDetectorSummary(key, "$key detector", CRoootReportStatus.UNKNOWN, 0, 0, 0, true)
        val fields = selectedDuckFields(report)
        val status = duckStatus(fields)
        return CRoootDetectorSummary(
            detectorId = key, title = "$key detector", status = status, findingCount = 1,
            highSeverityCount = 0, warningCount = if (status == CRoootReportStatus.WARN) 1 else 0,
            executed = true, reportType = report.javaClass.simpleName,
            errorMessage = fields["errorMessage"] ?: fields["failureMessage"],
        )
    }

    private fun overallStatus(result: CRoootScanResult, summaries: List<CRoootDetectorSummary>) = when {
        result.isRooted -> CRoootReportStatus.FAIL
        result.kkndHardware?.failCount?.let { it > 0 } == true -> CRoootReportStatus.FAIL
        result.isSuspicious -> CRoootReportStatus.WARN
        summaries.any { it.status == CRoootReportStatus.ERROR } -> CRoootReportStatus.ERROR
        summaries.any { it.status == CRoootReportStatus.UNKNOWN || it.status == CRoootReportStatus.NOT_RUN } -> CRoootReportStatus.UNKNOWN
        else -> CRoootReportStatus.PASS
    }

    private fun environment() = CRoootScanEnvironment(
        androidApiLevel = Build.VERSION.SDK_INT,
        abi = Build.SUPPORTED_ABIS.firstOrNull().orEmpty().ifBlank { "unknown" },
    )

    private fun duckStatus(fields: Map<String, String>) = when {
        !fields["failureMessage"].isNullOrBlank() || !fields["errorMessage"].isNullOrBlank() -> CRoootReportStatus.ERROR
        fields["stage"]?.uppercase() in setOf("FAILED", "ERROR") -> CRoootReportStatus.ERROR
        fields["stage"]?.uppercase() in setOf("LOADING", "UNKNOWN") -> CRoootReportStatus.UNKNOWN
        else -> CRoootReportStatus.INFO
    }

    private fun selectedDuckFields(report: Any): Map<String, String> = buildMap {
        allInstanceFields(report.javaClass).forEach { field ->
            if (field.name !in allowedDuckFields) return@forEach
            val value = readField(field, report) ?: return@forEach
            if (value is String && value.isBlank()) return@forEach
            put(field.name, value.toString().take(MAX_DUCK_VALUE_LENGTH))
        }
    }

    private fun duckPrivacy(field: String) = when {
        field.contains("token", true) || field.contains("secret", true) -> CRoootEvidencePrivacy.SECRET
        field.contains("path", true) || field.contains("property", true) -> CRoootEvidencePrivacy.DEVICE_SENSITIVE
        else -> CRoootEvidencePrivacy.PUBLIC
    }

    private fun redact(value: String, includeSensitive: Boolean): String {
        if (includeSensitive) return value
        return value
            .replace(Regex("(?i)(token|secret|password|apikey|api_key)=\\S+"), "$1=<redacted>")
            .replace(Regex("/data/user/\\d+/[^/]+"), "<app-data>")
            .replace(Regex("/data/data/[^/]+"), "<app-data>")
            .replace(Regex("/data/app/[^/]+"), "<app-install>")
            .replace(Regex("0x[0-9a-f]{6,}", RegexOption.IGNORE_CASE), "<address>")
    }

    private fun readField(field: Field, target: Any): Any? = runCatching {
        field.isAccessible = true
        field.get(target)
    }.getOrNull()

    private fun allInstanceFields(type: Class<*>): List<Field> = buildList {
        var current: Class<*>? = type
        while (current != null && current != Any::class.java) {
            current.declaredFields.filter { !Modifier.isStatic(it.modifiers) && !it.isSynthetic }.forEach(::add)
            current = current.superclass
        }
    }

    private const val MAX_DUCK_VALUE_LENGTH = 600
}
