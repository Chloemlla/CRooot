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
 * `checkRootPackages` and `checkPackageManagerAnomalies` report every hit in `rootPackages` as a
 * HIGH-severity root manager, so a package that does not require root must not live in that list.
 */
class HardcodedSignalsTest {
    @Test
    fun nonRootPatchersStayOutOfTheRootManagerList() {
        // LSPatch is the non-root Xposed variant. It was listed in both `rootPackages` (HIGH) and
        // `patchedApps` (WARNING), which made every device with LSPatch installed report as rooted.
        assertFalse(
            "LSPatch must not be treated as a root manager",
            HardcodedSignals.rootPackages.contains("org.lsposed.lspatch"),
        )
        assertTrue(
            "LSPatch must still be reported as a patched app",
            HardcodedSignals.patchedApps.contains("org.lsposed.lspatch"),
        )
    }

    @Test
    fun rootManagerListKeepsRealRootManagers() {
        assertTrue(HardcodedSignals.rootPackages.contains("com.topjohnwu.magisk"))
        assertTrue(HardcodedSignals.rootPackages.contains("me.weishu.kernelsu"))
    }
}
