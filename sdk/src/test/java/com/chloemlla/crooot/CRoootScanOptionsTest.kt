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

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CRoootScanOptionsTest {

    @Test
    fun `default options include all features`() {
        val options = CRoootScanOptions()
        assertTrue(options.includeHardware)
        assertTrue(options.includeDuckFeatures)
        assertEquals(16, options.duckFeatureKeys().size)
    }

    @Test
    fun `master switch disables all duck features`() {
        val options = CRoootScanOptions(
            includeDuckFeatures = false,
            includeBootloader = true,
            includeTee = true,
        )
        assertTrue(options.duckFeatureKeys().isEmpty())
    }

    @Test
    fun `individual flags filter duck features`() {
        val options = CRoootScanOptions(
            includeBootloader = false,
            includeTee = false,
            includeZygisk = false,
        )
        val keys = options.duckFeatureKeys()
        assertEquals(13, keys.size)
        assertFalse(keys.contains("bootloader"))
        assertFalse(keys.contains("tee"))
        assertFalse(keys.contains("zygisk"))
        assertTrue(keys.contains("customRom"))
        assertTrue(keys.contains("kernel"))
        assertTrue(keys.contains("selinux"))
        assertTrue(keys.contains("virtualization"))
    }

    @Test
    fun `only specific features enabled`() {
        val options = CRoootScanOptions(
            includeDuckFeatures = true,
            includeBootloader = false,
            includeCustomRom = false,
            includeDangerousApps = false,
            includeDeviceInfo = false,
            includeKernel = false,
            includeLsposed = false,
            includeMemory = false,
            includeMount = false,
            includeNativeRoot = false,
            includePlayIntegrityFix = false,
            includeSelinux = false,
            includeSu = false,
            includeSystemProperties = false,
            includeTee = true,
            includeVirtualization = false,
            includeZygisk = false,
        )
        val keys = options.duckFeatureKeys()
        assertEquals(1, keys.size)
        assertEquals("tee", keys.first())
    }

    @Test
    fun `includeHardware independent of duck features`() {
        val options = CRoootScanOptions(includeHardware = false)
        assertFalse(options.includeHardware)
        assertTrue(options.includeDuckFeatures)
        assertEquals(16, options.duckFeatureKeys().size)
    }
}