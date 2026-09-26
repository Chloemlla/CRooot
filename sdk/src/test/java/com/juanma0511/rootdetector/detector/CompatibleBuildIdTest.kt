/*
 * Copyright 2026 Duck Apps Contributor
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

package com.juanma0511.rootdetector.detector

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `crossPartitionFingerprintMismatch` compares the build id of `ro.build.fingerprint` against
 * `ro.system.build.fingerprint` and reports a HIGH-severity finding when they disagree.
 */
class CompatibleBuildIdTest {
    @Test
    fun stockVivoVendorSuffixIsNotAMismatch() {
        // Observed on a stock vivo PD2426 (Android 16, ro.build.type=user, bootloader locked):
        // ro.build.fingerprint carries the per-shipment suffix, ro.system.build.fingerprint does not.
        assertTrue(
            compatibleBuildId("BP2A.250605.031.A3_V000L1", "BP2A.250605.031.A3"),
        )
        assertTrue(
            compatibleBuildId("BP2A.250605.031.A3", "BP2A.250605.031.A3_V000L1"),
        )
    }

    @Test
    fun identicalAndPrefixBuildIdsMatch() {
        assertTrue(compatibleBuildId("BP2A.250605.031.A3", "BP2A.250605.031.A3"))
        assertTrue(compatibleBuildId("BP2A.250605.031.A3", "BP2A.250605"))
    }

    @Test
    fun unrelatedBuildIdsStillMismatch() {
        assertFalse(compatibleBuildId("BP2A.250605.031.A3", "AP3A.240905.015.A2"))
        assertFalse(compatibleBuildId("TQ3A.230901.001", "UP1A.231005.007"))
    }
}
