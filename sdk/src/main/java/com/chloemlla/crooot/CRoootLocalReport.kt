package com.chloemlla.crooot

/** Stable report status for consumers. UNKNOWN and NOT_RUN are never clean signals. */
enum class CRoootReportStatus {
    PASS,
    INFO,
    WARN,
    FAIL,
    UNKNOWN,
    NOT_RUN,
    ERROR,
}

enum class CRoootFindingSeverity {
    INFO,
    LOW,
    MEDIUM,
    HIGH,
    CRITICAL,
}

enum class CRoootEvidencePrivacy {
    PUBLIC,
    DEVICE_SENSITIVE,
    APP_SENSITIVE,
    SECRET,
}

enum class CRoootConfidence {
    LOW,
    MEDIUM,
    HIGH,
}

enum class CRoootEvidenceSource {
    KKND,
    DUCK,
    SDK,
}

data class CRoootEvidence(
    val key: String,
    val value: String,
    val privacy: CRoootEvidencePrivacy = CRoootEvidencePrivacy.PUBLIC,
)

data class CRoootFinding(
    val id: String,
    val detectorId: String,
    val category: String,
    val severity: CRoootFindingSeverity,
    val status: CRoootReportStatus,
    val title: String,
    val summary: String,
    val evidence: List<CRoootEvidence> = emptyList(),
    val recommendation: String? = null,
    val confidence: CRoootConfidence = CRoootConfidence.MEDIUM,
    val source: CRoootEvidenceSource = CRoootEvidenceSource.SDK,
)

data class CRoootDetectorSummary(
    val detectorId: String,
    val title: String,
    val status: CRoootReportStatus,
    val findingCount: Int,
    val highSeverityCount: Int,
    val warningCount: Int,
    val executed: Boolean,
    val reportType: String? = null,
    val errorMessage: String? = null,
)

data class CRoootScanEnvironment(
    val androidApiLevel: Int,
    val abi: String,
    val packageVisibility: String? = null,
    val nativeLibrariesAvailable: Boolean? = null,
)

data class CRoootLocalReport(
    val schemaVersion: Int,
    val sdkVersion: String,
    val reportId: String,
    val startedAtMillis: Long,
    val durationMillis: Long,
    val profile: CRoootScanProfile,
    val overallStatus: CRoootReportStatus,
    val rooted: Boolean?,
    val suspicious: Boolean?,
    val complete: Boolean,
    val detectorSummaries: List<CRoootDetectorSummary>,
    val findings: List<CRoootFinding>,
    val environment: CRoootScanEnvironment,
    val limitations: List<String>,
)

/** Scan presets used by [CRoootReportOptions]. Existing [CRoootSdk.scan] behavior is unchanged. */
enum class CRoootScanProfile {
    QUICK,
    STANDARD,
    FULL,
    PRIVACY_MINIMAL,
}

data class CRoootReportOptions(
    val profile: CRoootScanProfile = CRoootScanProfile.FULL,
    val scanOptions: CRoootScanOptions? = null,
    val includeSensitiveEvidence: Boolean = false,
    val includeEnvironment: Boolean = true,
) {
    internal fun effectiveScanOptions(): CRoootScanOptions = scanOptions ?: when (profile) {
        CRoootScanProfile.QUICK -> CRoootScanOptions(
            includeHardware = false,
            includeDuckFeatures = false,
        )
        CRoootScanProfile.STANDARD -> CRoootScanOptions(
            includeHardware = true,
            includeDuckFeatures = true,
            includeDeviceInfo = false,
            includeDangerousApps = false,
        )
        CRoootScanProfile.FULL -> CRoootScanOptions()
        CRoootScanProfile.PRIVACY_MINIMAL -> CRoootScanOptions(
            includeHardware = true,
            includeDuckFeatures = true,
            includeCustomRom = false,
            includeDangerousApps = false,
            includeDeviceInfo = false,
            includeLsposed = false,
        )
    }
}

sealed interface CRoootScanEvent {
    data class Started(val profile: CRoootScanProfile) : CRoootScanEvent
    data class Completed(val report: CRoootLocalReport) : CRoootScanEvent
    data class Failed(val error: Throwable) : CRoootScanEvent
}
