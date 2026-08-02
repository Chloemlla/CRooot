package com.chloemlla.crooot

import android.os.Build
import com.juanma0511.rootdetector.model.CheckStatus
import com.juanma0511.rootdetector.model.DetectionItem
import com.juanma0511.rootdetector.model.HwCheckItem
import com.juanma0511.rootdetector.model.Severity
import java.lang.reflect.Array
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.UUID

/**
 * Converts the legacy result into the stable third-party report boundary.
 * Reflection is intentionally isolated here because Duck reports are exposed as Any?.
 */
object CRoootLocalReportMapper {
    private const val MAX_NESTED_ITEMS = 24
    private const val MAX_NESTED_DEPTH = 3
    private const val MAX_DUCK_VALUE_LENGTH = 600

    private val duckKeys = listOf(
        "bootloader", "customRom", "dangerousApps", "deviceInfo", "kernel", "lsposed",
        "memory", "mount", "nativeRoot", "playIntegrityFix", "selinux", "su",
        "systemProperties", "tee", "virtualization", "zygisk",
    )

    /** Top-level scalar properties accepted into the compatibility report. */
    private val allowedDuckFields = setOf(
        "stage", "state", "verdict", "tier", "headline", "summary", "collapsedSummary",
        "trustSummary", "failureMessage", "errorMessage", "nativeAvailable", "packageVisibility",
        "paradoxDetected", "rootDetected", "kernelSuDetected", "aPatchDetected", "magiskDetected",
        "susfsDetected", "hasDangerSignals", "hasWarningSignals", "hasIndicators", "fullyClean",
        "dangerFindingCount", "dangerSignalCount", "warningSignalCount", "modifiedFunctionCount",
        "hitCount", "classHitCount", "nativeStrongHitCount", "heuristicHitCount", "tamperScore",
        "evidenceCount", "detectedCount", "hiddenCount", "hardFindingCount", "infoFindingCount",
        "dangerSignalCount", "warningSignalCount", "hasRootIndicators", "bootloaderUnlocked",
        "warningFindings", "dangerFindings", "directFindings", "strongHitCount",
    )

    /** Collection properties are expanded into bounded, structured finding evidence. */
    private val nestedDuckFields = setOf(
        "findings", "signals", "methods", "issues", "impacts", "sections", "certificates",
        "propertyFindings", "buildFindings", "modificationFindings", "packageFindings",
        "serviceFindings", "reflectionFindings", "platformFileFindings", "policyFindings",
        "overlayFindings", "symbolFindings", "suBinaries", "suspiciousProcesses",
        "dangerFindings", "warningFindings", "dangerSignals", "warningSignals",
        "propertySignals", "consistencySignals", "nativeSignals", "infoSignals", "dangerSignals", "warningSignals",
    )

    fun map(
        result: CRoootScanResult,
        options: CRoootReportOptions = CRoootReportOptions(),
        startedAtMillis: Long = System.currentTimeMillis() - result.durationMs,
    ): CRoootLocalReport {
        val findings = buildList {
            result.kkndRoot.items.forEach { add(rootFinding(it, options.includeSensitiveEvidence)) }
            result.kkndHardware?.items?.forEach { add(hardwareFinding(it, options.includeSensitiveEvidence)) }
            duckKeys.forEach { key ->
                result.duckReports[key]?.let {
                    addAll(duckFindings(key, it, options.includeSensitiveEvidence))
                }
            }
        }
        val summaries = buildList {
            add(rootSummary(result.kkndRoot.items))
            add(hardwareSummary(result.kkndHardware))
            duckKeys.forEach { key ->
                add(duckSummary(key, result.duckReports[key], result.duckReports.containsKey(key)))
            }
        }
        val limitations = buildList {
            if (result.kkndHardware == null) add("KKND hardware checks were not run.")
            if (result.duckReports.size < duckKeys.size) {
                add("${duckKeys.size - result.duckReports.size} Duck detector(s) were not run.")
            }
            if (result.kkndHardware?.items?.any { it.status == CheckStatus.UNKNOWN } == true) {
                add("Some hardware checks returned UNKNOWN and must not be treated as PASS.")
            }
            if (result.duckReports.values.any { it == null }) {
                add("At least one requested Duck detector returned no report.")
            }
            if (result.duckReports.values.filterNotNull().any { selectedDuckFields(it).isEmpty() }) {
                add("At least one Duck report has no fields supported by this report schema.")
            }
            if (!options.includeEnvironment) add("Environment metadata was intentionally omitted.")
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
                environment(result)
            } else {
                CRoootScanEnvironment(androidApiLevel = 0, abi = "redacted")
            },
            limitations = limitations,
        )
    }

    private fun rootFinding(item: DetectionItem, includeSensitiveEvidence: Boolean) = CRoootFinding(
        id = "kknd.root.${item.id}",
        detectorId = "kkndRoot",
        category = item.category.name,
        severity = if (item.severity == Severity.HIGH) {
            CRoootFindingSeverity.HIGH
        } else {
            CRoootFindingSeverity.MEDIUM
        },
        status = if (item.detected) {
            if (item.severity == Severity.HIGH) CRoootReportStatus.FAIL else CRoootReportStatus.WARN
        } else {
            CRoootReportStatus.PASS
        },
        title = redact(item.name, includeSensitiveEvidence),
        summary = redact(item.description, includeSensitiveEvidence),
        evidence = listOfNotNull(item.detail?.let {
            evidence("detail", it, CRoootEvidencePrivacy.DEVICE_SENSITIVE, includeSensitiveEvidence)
        }),
        recommendation = if (item.detected) "Review this signal together with the other CRooot findings." else null,
        confidence = if (item.severity == Severity.HIGH) CRoootConfidence.HIGH else CRoootConfidence.MEDIUM,
        source = CRoootEvidenceSource.KKND,
    )

    private fun hardwareFinding(item: HwCheckItem, includeSensitiveEvidence: Boolean) = CRoootFinding(
        id = "kknd.hardware.${item.id}",
        detectorId = "kkndHardware",
        category = item.group.name,
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
        title = redact(item.name, includeSensitiveEvidence),
        summary = redact(item.description, includeSensitiveEvidence),
        evidence = buildList {
            add(evidence("value", item.value, CRoootEvidencePrivacy.DEVICE_SENSITIVE, includeSensitiveEvidence))
            item.expected?.let {
                add(evidence("expected", it, CRoootEvidencePrivacy.DEVICE_SENSITIVE, includeSensitiveEvidence))
            }
            item.detail?.let {
                add(evidence("detail", it, CRoootEvidencePrivacy.DEVICE_SENSITIVE, includeSensitiveEvidence))
            }
        },
        recommendation = if (item.status == CheckStatus.WARN || item.status == CheckStatus.FAIL) {
            "Review the hardware evidence before making a trust decision."
        } else null,
        confidence = if (item.status == CheckStatus.FAIL) CRoootConfidence.HIGH else CRoootConfidence.MEDIUM,
        source = CRoootEvidenceSource.KKND,
    )

    private fun duckFindings(key: String, report: Any, includeSensitiveEvidence: Boolean): List<CRoootFinding> {
        val fields = selectedDuckFields(report)
        val status = duckStatus(fields)
        val result = mutableListOf(
            CRoootFinding(
                id = "duck.$key.report",
                detectorId = key,
                category = "DUCK",
                severity = duckSeverity(status),
                status = status,
                title = redact("$key detector report", includeSensitiveEvidence),
                summary = redact(
                    fields["headline"]?.asText()
                        ?: fields["summary"]?.asText()
                        ?: "${report.javaClass.simpleName} is available.",
                    includeSensitiveEvidence,
                ),
                evidence = fields
                    .filterKeys { it != "headline" && it != "summary" }
                    .map { (field, value) ->
                        evidence(field, value, duckPrivacy(field), includeSensitiveEvidence)
                    },
                confidence = duckConfidence(status),
                source = CRoootEvidenceSource.DUCK,
            ),
        )
        nestedDuckFields.forEach { property ->
            val nested = readProperty(report, property) ?: return@forEach
            appendNestedEvidence(
                result = result,
                detectorId = key,
                property = property,
                value = nested,
                includeSensitiveEvidence = includeSensitiveEvidence,
            )
        }
        return result
    }

    private fun appendNestedEvidence(
        result: MutableList<CRoootFinding>,
        detectorId: String,
        property: String,
        value: Any?,
        includeSensitiveEvidence: Boolean,
    ) {
        val items = value?.let { boundedItems(it) }
        if (items == null) {
            result += nestedFinding(detectorId, property, value, includeSensitiveEvidence)
            return
        }
        items.forEachIndexed { index, item ->
            result += nestedFinding(detectorId, "$property[$index]", item, includeSensitiveEvidence)
        }
        if (value != null && collectionSize(value) > MAX_NESTED_ITEMS) {
            result += nestedFinding(
                detectorId,
                "$property.more",
                "${collectionSize(value) - MAX_NESTED_ITEMS} additional items omitted",
                includeSensitiveEvidence,
            )
        }
    }

    private fun nestedFinding(
        detectorId: String,
        property: String,
        value: Any?,
        includeSensitiveEvidence: Boolean,
    ): CRoootFinding {
        val detailStatus = nestedStatus(property, value)
        return CRoootFinding(
            id = "duck.$detectorId.$property",
            detectorId = detectorId,
            category = "DUCK_DETAIL",
            severity = duckSeverity(detailStatus),
            status = detailStatus,
            title = property,
            summary = if (detailStatus == CRoootReportStatus.INFO) "Structured detector detail" else "Detector signal detail",
            evidence = flattenEvidence(
                key = property,
                value = value,
                depth = 0,
                includeSensitiveEvidence = includeSensitiveEvidence,
            ),
            source = CRoootEvidenceSource.DUCK,
        )
    }

    private fun nestedStatus(property: String, value: Any?): CRoootReportStatus {
        val text = value.asText()?.uppercase().orEmpty()
        return when {
            text.contains("DANGER") || text.contains("FAIL") || text.contains("TAMPER") -> CRoootReportStatus.FAIL
            text.contains("WARN") || text.contains("SUSPIC") -> CRoootReportStatus.WARN
            property.contains("error", true) -> CRoootReportStatus.ERROR
            else -> CRoootReportStatus.INFO
        }
    }

    private fun rootSummary(items: List<DetectionItem>) = CRoootDetectorSummary(
        detectorId = "kkndRoot",
        title = "KKND root detection",
        status = when {
            items.any { it.detected && it.severity == Severity.HIGH } -> CRoootReportStatus.FAIL
            items.any { it.detected } -> CRoootReportStatus.WARN
            else -> CRoootReportStatus.PASS
        },
        findingCount = items.size,
        highSeverityCount = items.count { it.detected && it.severity == Severity.HIGH },
        warningCount = items.count { it.detected && it.severity == Severity.WARNING },
        executed = true,
        reportType = "ScanResult",
    )

    private fun hardwareSummary(result: com.juanma0511.rootdetector.model.HwScanResult?): CRoootDetectorSummary {
        if (result == null) {
            return CRoootDetectorSummary(
                detectorId = "kkndHardware",
                title = "KKND hardware integrity",
                status = CRoootReportStatus.NOT_RUN,
                findingCount = 0,
                highSeverityCount = 0,
                warningCount = 0,
                executed = false,
            )
        }
        return CRoootDetectorSummary(
            detectorId = "kkndHardware",
            title = "KKND hardware integrity",
            status = when {
                result.failCount > 0 -> CRoootReportStatus.FAIL
                result.warnCount > 0 -> CRoootReportStatus.WARN
                result.items.any { it.status == CheckStatus.UNKNOWN } -> CRoootReportStatus.UNKNOWN
                else -> CRoootReportStatus.PASS
            },
            findingCount = result.items.size,
            highSeverityCount = result.failCount,
            warningCount = result.warnCount,
            executed = true,
            reportType = "HwScanResult",
        )
    }

    private fun duckSummary(key: String, report: Any?, present: Boolean): CRoootDetectorSummary {
        if (!present) {
            return CRoootDetectorSummary(key, "$key detector", CRoootReportStatus.NOT_RUN, 0, 0, 0, false)
        }
        if (report == null) {
            return CRoootDetectorSummary(key, "$key detector", CRoootReportStatus.UNKNOWN, 0, 0, 0, true)
        }
        val selected = selectedDuckFields(report)
        val nestedFindingCount = nestedDuckFields.sumOf { property ->
            (readProperty(report, property)?.let { value -> collectionSize(value) } ?: 0).coerceAtMost(MAX_NESTED_ITEMS)
        }
        val baseFindingCount = 1 + nestedFindingCount
        return CRoootDetectorSummary(
            detectorId = key,
            title = "$key detector",
            status = status,
            findingCount = baseFindingCount,
            highSeverityCount = if (duckSeverity(status) == CRoootFindingSeverity.HIGH) 1 else 0,
            warningCount = if (status == CRoootReportStatus.WARN) 1 else 0,
            executed = true,
            reportType = report.javaClass.simpleName,
            errorMessage = fields["errorMessage"]?.asText()?.let { redact(it, false) }
                ?: fields["failureMessage"]?.asText()?.let { redact(it, false) },
        )
    }

    private fun overallStatus(
        result: CRoootScanResult,
        summaries: List<CRoootDetectorSummary>,
    ): CRoootReportStatus = when {
        result.isRooted || result.kkndHardware?.failCount?.let { it > 0 } == true -> CRoootReportStatus.FAIL
        summaries.any { it.status == CRoootReportStatus.ERROR } -> CRoootReportStatus.ERROR
        summaries.any { it.status == CRoootReportStatus.UNKNOWN || it.status == CRoootReportStatus.NOT_RUN } -> CRoootReportStatus.UNKNOWN
        result.isSuspicious || summaries.any { it.status == CRoootReportStatus.WARN } -> CRoootReportStatus.WARN
        else -> CRoootReportStatus.PASS
    }

    private fun environment(result: CRoootScanResult): CRoootScanEnvironment {
        val duckReports = result.duckReports.values.filterNotNull()
        val packageVisibilities = duckReports
            .mapNotNull { readProperty(it, "packageVisibility")?.toString() }
        val packageVisibility = packageVisibilities.firstOrNull { it.equals("RESTRICTED", true) }
            ?: packageVisibilities.firstOrNull()
        val nativeValues = duckReports.mapNotNull { readProperty(it, "nativeAvailable") as? Boolean }
        val nativeAvailable = when {
            nativeValues.any { !it } -> false
            nativeValues.any() -> true
            else -> null
        }
        return CRoootScanEnvironment(
            androidApiLevel = Build.VERSION.SDK_INT,
            abi = Build.SUPPORTED_ABIS.firstOrNull().orEmpty().ifBlank { "unknown" },
            packageVisibility = packageVisibility,
            nativeLibrariesAvailable = nativeAvailable,
        )
    }

    private fun duckStatus(fields: Map<String, Any?>): CRoootReportStatus {
        val stage = fields["stage"].asText()?.uppercase()
        if (!fields["failureMessage"].asText().isNullOrBlank() || !fields["errorMessage"].asText().isNullOrBlank()) {
            return CRoootReportStatus.ERROR
        }
        if (stage in setOf("FAILED", "ERROR")) return CRoootReportStatus.ERROR
        if (stage in setOf("LOADING", "UNKNOWN")) return CRoootReportStatus.UNKNOWN
        if (fields.booleanTrue("rootDetected", "kernelSuDetected", "aPatchDetected", "magiskDetected", "susfsDetected")) {
            return CRoootReportStatus.FAIL
        }
        if (fields.booleanTrue("hasDangerSignals", "hasIndicators")) {
            return CRoootReportStatus.FAIL
        }
        if (fields.numberPositive("dangerFindingCount", "dangerSignalCount", "nativeStrongHitCount", "detectedCount", "hardFindingCount")) {
            return CRoootReportStatus.FAIL
        }
        if (fields.booleanTrue("hasWarningSignals", "hasRootIndicators", "bootloaderUnlocked")) return CRoootReportStatus.WARN
        if (fields.numberPositive("warningSignalCount")) return CRoootReportStatus.WARN
        val suspiciousApps = fields.numberPositive("detectedCount", "hiddenCount")
        if (suspiciousApps) return CRoootReportStatus.WARN
        if (fields["stage"].asText()?.uppercase() == "READY" && fields.size <= 1) return CRoootReportStatus.UNKNOWN
        val verdict = fields["verdict"].asText()?.uppercase()
        if (verdict in setOf("FAILED", "BROKEN", "TAMPERED", "INCONSISTENT")) return CRoootReportStatus.FAIL
        if (verdict in setOf("WARNING", "SUSPICIOUS", "INCONCLUSIVE")) return CRoootReportStatus.WARN
        if (verdict in setOf("CONSISTENT", "CLEAN")) return CRoootReportStatus.PASS
        if (fields.booleanTrue("fullyClean")) return CRoootReportStatus.PASS
        return if (stage == "READY") CRoootReportStatus.INFO else CRoootReportStatus.UNKNOWN
    }

    private fun duckSeverity(status: CRoootReportStatus) = when (status) {
        CRoootReportStatus.FAIL, CRoootReportStatus.ERROR -> CRoootFindingSeverity.HIGH
        CRoootReportStatus.WARN -> CRoootFindingSeverity.MEDIUM
        else -> CRoootFindingSeverity.INFO
    }

    private fun duckConfidence(status: CRoootReportStatus) = when (status) {
        CRoootReportStatus.FAIL -> CRoootConfidence.HIGH
        CRoootReportStatus.WARN -> CRoootConfidence.MEDIUM
        else -> CRoootConfidence.LOW
    }

    private fun selectedDuckFields(report: Any): Map<String, Any?> = buildMap {
        allowedDuckFields.forEach { property ->
            val value = readProperty(report, property) ?: return@forEach
            if (value is String && value.isBlank()) return@forEach
            put(property, value)
        }
    }

    private fun flattenEvidence(
        key: String,
        value: Any?,
        depth: Int,
        includeSensitiveEvidence: Boolean,
    ): List<CRoootEvidence> {
        if (value == null) return listOf(evidence(key, "null", CRoootEvidencePrivacy.PUBLIC, includeSensitiveEvidence))
        if (depth >= MAX_NESTED_DEPTH) {
            return listOf(evidence(key, "<max depth>", CRoootEvidencePrivacy.PUBLIC, includeSensitiveEvidence))
        }
        value.asText()?.let {
            return listOf(evidence(key, it, duckPrivacy(key), includeSensitiveEvidence))
        }
        boundedItems(value)?.let { items ->
            return items.flatMapIndexed { index, item ->
                flattenEvidence("$key[$index]", item, depth + 1, includeSensitiveEvidence)
            }
        }
        val properties = allInstanceFields(value.javaClass).mapNotNull { field ->
            val fieldValue = readField(field, value) ?: return@mapNotNull null
            field.name to fieldValue
        }
        if (properties.isEmpty()) {
            return listOf(evidence(key, value.javaClass.simpleName, CRoootEvidencePrivacy.PUBLIC, includeSensitiveEvidence))
        }
        return properties.take(MAX_NESTED_ITEMS).flatMap { (name, nested) ->
            flattenEvidence("$key.$name", nested, depth + 1, includeSensitiveEvidence)
        }
    }

    private fun evidence(
        key: String,
        value: String,
        privacy: CRoootEvidencePrivacy,
        includeSensitiveEvidence: Boolean,
    ) = CRoootEvidence(key, redact(value, includeSensitiveEvidence), privacy)

    private fun duckPrivacy(field: String) = when {
        field.contains("token", true) || field.contains("secret", true) || field.contains("password", true) -> CRoootEvidencePrivacy.SECRET
        field.contains("path", true) || field.contains("property", true) || field.contains("package", true) -> CRoootEvidencePrivacy.DEVICE_SENSITIVE
        else -> CRoootEvidencePrivacy.PUBLIC
    }

    /** Core secrets are always redacted; the flag only permits additional diagnostic detail. */
    private fun redact(value: String, includeSensitive: Boolean): String {
        val secretSafe = value
            .replace(Regex("(?i)(token|secret|password|apikey|api_key)=\\S+"), "$1=<redacted>")
            .replace(Regex("(?i)(token|secret|password|apikey|api_key)\\s*[:=]\\s*\\S+"), "$1=<redacted>")
        if (includeSensitive) return secretSafe.take(MAX_DUCK_VALUE_LENGTH)
        return secretSafe
            .replace(Regex("/data/user/\\d+/[^/]+"), "<app-data>")
            .replace(Regex("/data/data/[^/]+"), "<app-data>")
            .replace(Regex("/data/app/[^/]+"), "<app-install>")
            .replace(Regex("0x[0-9a-f]{6,}", RegexOption.IGNORE_CASE), "<address>")
            .take(MAX_DUCK_VALUE_LENGTH)
    }

    private fun readProperty(target: Any, property: String): Any? {
        val suffix = property.replaceFirstChar { it.uppercase() }
        val getter = allMethods(target.javaClass).firstOrNull {
            it.parameterTypes.isEmpty() && (it.name == "get$suffix" || it.name == "is$suffix")
        }
        return runCatching {
            getter?.let {
                it.isAccessible = true
                return@runCatching it.invoke(target)
            }
            allInstanceFields(target.javaClass).firstOrNull { it.name == property }?.let {
                readField(it, target)
            }
        }.getOrNull()
    }

    private fun allMethods(type: Class<*>): List<Method> = buildList {
        var current: Class<*>? = type
        while (current != null && current != Any::class.java) {
            current.declaredMethods.filter { !Modifier.isStatic(it.modifiers) }.forEach(::add)
            current = current.superclass
        }
    }

    private fun readField(field: Field, target: Any): Any? = runCatching {
        field.isAccessible = true
        field.get(target)
    }.getOrNull()

    private fun allInstanceFields(type: Class<*>): List<Field> = buildList {
        var current: Class<*>? = type
        while (current != null && current != Any::class.java) {
            current.declaredFields
                .filter { !Modifier.isStatic(it.modifiers) && !it.isSynthetic }
                .forEach(::add)
            current = current.superclass
        }
    }

    private fun boundedItems(value: Any): List<Any?>? = when (value) {
        is Iterable<*> -> value.take(MAX_NESTED_ITEMS)
        is Map<*, *> -> value.entries.take(MAX_NESTED_ITEMS)
        else -> if (value.javaClass.isArray) {
            (0 until minOf(Array.getLength(value), MAX_NESTED_ITEMS)).map { Array.get(value, it) }
        } else null
    }

    private fun collectionSize(value: Any): Int = when (value) {
        is Collection<*> -> value.size
        is Map<*, *> -> value.size
        else -> if (value.javaClass.isArray) Array.getLength(value) else 0
    }

    private fun Any?.asText(): String? = when (this) {
        null -> null
        is String, is Number, is Boolean, is Char -> toString()
        is Enum<*> -> name
        is Map.Entry<*, *> -> "${key?.toString().orEmpty()}=${value?.toString().orEmpty()}"
        else -> null
    }

    private fun Map<String, Any?>.booleanTrue(vararg names: String): Boolean = names.any { this[it] == true }

    private fun Map<String, Any?>.numberPositive(vararg names: String): Boolean = names.any {
        (this[it] as? Number)?.toInt()?.let { value -> value > 0 } == true
    }
}
