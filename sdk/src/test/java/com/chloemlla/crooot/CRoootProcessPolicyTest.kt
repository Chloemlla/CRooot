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

import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class CRoootProcessPolicyTest {
    private val packageName = "com.example.host"

    @Test
    fun `main process is allowed for each Android user`() {
        for (uid in listOf(10_123, 110_123, 1_010_123)) {
            CRoootProcessPolicy.requireAllowed(packageName, packageName, uid)
        }
    }

    @Test
    fun `custom host process remains allowed`() {
        for (uid in listOf(10_123, 110_123)) {
            CRoootProcessPolicy.requireAllowed(packageName, "$packageName:security", uid)
        }
    }

    @Test
    fun `both detector owned regular helpers are rejected for each user`() {
        for (suffix in listOf("virtualization_probe", "zygisk_fd_detector")) {
            for (uid in listOf(10_123, 110_123)) {
                assertRejected("$packageName:$suffix", uid, "detector-owned")
            }
        }
    }

    @Test
    fun `helper ownership uses exact host package and full process name`() {
        CRoootProcessPolicy.requireAllowed(packageName, "com.example.other:virtualization_probe", 10_123)
        CRoootProcessPolicy.requireAllowed(packageName, "$packageName:virtualization_probe_extra", 10_123)
        CRoootProcessPolicy.requireAllowed(packageName, "$packageName:security:zygisk_fd_detector", 10_123)
    }

    @Test
    fun `regular and app zygote isolated UID boundaries are rejected for each user`() {
        for (userOffset in listOf(0, 100_000, 1_000_000)) {
            for (appId in listOf(90_000, 98_999, 99_000, 99_999)) {
                assertRejected(packageName, userOffset + appId, "isolated")
                assertRejected("$packageName:security", userOffset + appId, "isolated")
            }
        }
    }

    @Test
    fun `UID modulo does not reject adjacent non isolated user IDs`() {
        for (userOffset in listOf(0, 100_000, 1_000_000)) {
            CRoootProcessPolicy.requireAllowed(packageName, packageName, userOffset + 89_999)
            CRoootProcessPolicy.requireAllowed(packageName, packageName, userOffset + 100_000)
        }
    }

    @Test
    fun `unknown process identity fails closed`() {
        for (name in listOf(null, "", " ", "\t\n")) {
            assertRejected(name, 10_123, "known")
        }
        assertRejected(packageName, -1, "known")
        assertThrows(IllegalStateException::class.java) {
            CRoootProcessPolicy.requireAllowed("", packageName, 10_123)
        }
    }

    private fun assertRejected(name: String?, uid: Int, reason: String) {
        val failure = assertThrows(IllegalStateException::class.java) {
            CRoootProcessPolicy.requireAllowed(packageName, name, uid)
        }
        assertTrue(failure.message.orEmpty().contains(reason))
    }
}
