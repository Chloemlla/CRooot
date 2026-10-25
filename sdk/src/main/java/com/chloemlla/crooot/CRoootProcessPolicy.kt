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

/** Pure process boundary; no Android access during JVM class initialization. */
internal object CRoootProcessPolicy {
    private const val PER_USER_UID_RANGE = 100_000
    // Android app-zygote isolated IDs (90000..98999) and regular isolated IDs (99000..99999).
    private val ISOLATED_APP_IDS = 90_000..99_999

    fun requireAllowed(packageName: String, processName: String?, uid: Int) {
        check(packageName.isNotBlank() && !processName.isNullOrBlank() && uid >= 0) {
            "CRooot scan requires a known package, process name and UID."
        }
        check(uid % PER_USER_UID_RANGE !in ISOLATED_APP_IDS) {
            "CRooot scan is not allowed in an isolated UID process."
        }
        check(
            processName != "$packageName:virtualization_probe" &&
                processName != "$packageName:zygisk_fd_detector",
        ) {
            "CRooot scan is not allowed in a detector-owned helper process."
        }
    }
}
