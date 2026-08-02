/*
 * Copyright 2026 CRooot Contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.chloemlla.crooot

import com.juanma0511.rootdetector.model.DetectionCategory
import com.juanma0511.rootdetector.model.DetectionItem
import com.juanma0511.rootdetector.model.HwScanResult
import com.juanma0511.rootdetector.model.ScanResult
import com.juanma0511.rootdetector.model.Severity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CRoootScanResultTest {

    private fun rootedResult(): ScanResult = ScanResult(
        items = listOf(
            DetectionItem(
                id = "su_binary",
                name = "su binary",
                description = "Found su binary in /system/xbin/su",
                category = DetectionCategory.SU_BINARIES,
                severity = Severity.HIGH,
                detected = true,
            ),
        ),
        scanDurationMs = 500,
    )

    private fun cleanResult(): ScanResult = ScanResult(
        items = emptyList(),
        scanDurationMs = 500,
    )

    @Test
    fun `isRooted delegates to kkndRoot`() {
        val result = CRoootScanResult(
            kkndRoot = rootedResult(),
            kkndHardware = null,
            duckReports = emptyMap(),
            durationMs = 1000,
        )
        assertTrue(result.isRooted)
        assertTrue(result.isSuspicious)
    }

    @Test
    fun `isRooted false when kkndRoot reports none`() {
        val result = CRoootScanResult(
            kkndRoot = cleanResult(),
            kkndHardware = null,
            duckReports = emptyMap(),
            durationMs = 500,
        )
        assertFalse(result.isRooted)
        assertFalse(result.isSuspicious)
    }

    @Test
    fun `summary includes duration and duck report count`() {
        val result = CRoootScanResult(
            kkndRoot = cleanResult(),
            kkndHardware = null,
            duckReports = mapOf("tee" to Any(), "selinux" to Any()),
            durationMs = 2500,
        )
        val summary = result.summary()
        assertTrue(summary.contains("2500ms"))
        assertTrue(summary.contains("2/16"))
        assertFalse(summary.contains("ROOTED"))
    }

    @Test
    fun `summary includes ROOTED when rooted`() {
        val result = CRoootScanResult(
            kkndRoot = rootedResult(),
            kkndHardware = null,
            duckReports = emptyMap(),
            durationMs = 3000,
        )
        val summary = result.summary()
        assertTrue(summary.contains("ROOTED"))
        assertTrue(summary.contains("suspicious"))
    }

    @Test
    fun `summary includes hardware status when present`() {
        val result = CRoootScanResult(
            kkndRoot = cleanResult(),
            kkndHardware = HwScanResult(items = emptyList(), scanDurationMs = 500),
            duckReports = emptyMap(),
            durationMs = 1500,
        )
        val summary = result.summary()
        assertTrue(summary.contains("hardware=true"))
    }

    @Test
    fun `empty duck reports when includeDuckFeatures is false`() {
        val result = CRoootScanResult(
            kkndRoot = cleanResult(),
            kkndHardware = null,
            duckReports = emptyMap(),
            durationMs = 200,
        )
        assertTrue(result.duckReports.isEmpty())
        assertEquals(0, result.duckReports.size)
    }
}