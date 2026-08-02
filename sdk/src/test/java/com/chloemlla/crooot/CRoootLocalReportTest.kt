package com.chloemlla.crooot

import com.juanma0511.rootdetector.model.CheckStatus
import com.juanma0511.rootdetector.model.DetectionCategory
import com.juanma0511.rootdetector.model.DetectionItem
import com.juanma0511.rootdetector.model.HwCheckItem
import com.juanma0511.rootdetector.model.HwGroup
import com.juanma0511.rootdetector.model.Severity
import com.juanma0511.rootdetector.model.HwScanResult
import com.juanma0511.rootdetector.model.ScanResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CRoootLocalReportTest {
    @Test
    fun `local report maps root and hardware evidence without raw reports`() {
        val result = CRoootScanResult(
            kkndRoot = ScanResult(
                items = listOf(
                    DetectionItem(
                        id = "su",
                        name = "su binary",
                        description = "A root binary was found.",
                        category = DetectionCategory.SU_BINARIES,
                        severity = Severity.HIGH,
                        detected = true,
                        detail = "/system/xbin/su",
                    ),
                ),
                scanDurationMs = 20,
            ),
            kkndHardware = HwScanResult(
                items = listOf(
                    HwCheckItem(
                        id = "boot",
                        name = "Verified boot",
                        description = "Boot state check",
                        group = HwGroup.BOOT,
                        status = CheckStatus.UNKNOWN,
                        value = "unknown",
                    ),
                ),
                scanDurationMs = 25,
            ),
            duckReports = mapOf("tee" to null),
            durationMs = 30,
        )

        val report = CRoootLocalReportMapper.map(
            result = result,
            options = CRoootReportOptions(profile = CRoootScanProfile.STANDARD),
            startedAtMillis = 100L,
        )

        assertEquals(CRoootReportStatus.FAIL, report.overallStatus)
        assertTrue(report.findings.any { it.id == "kknd.root.su" })
        assertTrue(report.findings.any { it.status == CRoootReportStatus.UNKNOWN })
        assertTrue(report.detectorSummaries.any { it.detectorId == "tee" && it.status == CRoootReportStatus.UNKNOWN })
        assertTrue(report.limitations.any { it.contains("UNKNOWN") })
    }

    @Test
    fun `exporters serialize stable report fields`() {
        val report = CRoootLocalReport(
            schemaVersion = 1,
            sdkVersion = "0.1.0",
            reportId = "report",
            startedAtMillis = 1L,
            durationMillis = 2L,
            profile = CRoootScanProfile.QUICK,
            overallStatus = CRoootReportStatus.PASS,
            rooted = false,
            suspicious = false,
            complete = true,
            detectorSummaries = emptyList(),
            findings = emptyList(),
            environment = CRoootScanEnvironment(35, "arm64-v8a"),
            limitations = listOf("a limitation"),
        )

        val json = CRoootReportExporter.toJson(report)
        val html = CRoootReportExporter.toHtml(report)
        assertTrue(json.contains("\"schemaVersion\":1"))
        assertTrue(json.contains("\"overallStatus\":\"PASS\""))
        assertTrue(html.contains("CRooot local security report"))
        assertTrue(CRoootReportExporter.toText(report).contains("a limitation"))
    }
}
