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

package com.eltavine.duckdetector.features.tee.data.soter

import com.eltavine.duckdetector.features.tee.domain.TeeSoterState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SoterDamageEvaluatorTest {

    private val evaluator = SoterDamageEvaluator()

    @Test
    fun `all services reachable returns available`() {
        val state = evaluator.evaluate(
            serviceReachable = true,
            keyPrepared = true,
            signSessionAvailable = true,
            errorMessage = null,
        )
        assertTrue(state.available)
        assertFalse(state.damaged)
        assertTrue(state.summary.startsWith("Soter checks succeeded"))
    }

    @Test
    fun `service not reachable returns unavailable`() {
        val state = evaluator.evaluate(
            serviceReachable = false,
            keyPrepared = false,
            signSessionAvailable = false,
            errorMessage = null,
        )
        assertFalse(state.available)
        assertFalse(state.damaged)
        assertTrue(state.summary.contains("not reachable"))
    }

    @Test
    fun `service reachable but key not prepared indicates damage`() {
        val state = evaluator.evaluate(
            serviceReachable = true,
            keyPrepared = false,
            signSessionAvailable = false,
            errorMessage = null,
        )
        assertFalse(state.available)
        assertTrue(state.damaged)
        assertTrue(state.summary.contains("key preparation failed"))
    }

    @Test
    fun `abnormal environment detected`() {
        val state = evaluator.evaluate(
            serviceReachable = false,
            keyPrepared = false,
            signSessionAvailable = false,
            errorMessage = null,
            abnormalEnvironment = true,
        )
        assertFalse(state.available)
        assertFalse(state.damaged)
        assertTrue(state.summary.contains("Abnormal Soter environment"))
    }

    @Test
    fun `error message with soter hint`() {
        val state = evaluator.evaluate(
            serviceReachable = true,
            keyPrepared = false,
            signSessionAvailable = false,
            errorMessage = "soter_error: timeout",
        )
        assertFalse(state.available)
        assertTrue(state.damaged)
        assertTrue(state.summary.contains("soter_error: timeout"))
    }

    @Test
    fun `error message without soter hint gets wrapped`() {
        val state = evaluator.evaluate(
            serviceReachable = true,
            keyPrepared = false,
            signSessionAvailable = false,
            errorMessage = "timeout",
        )
        assertFalse(state.available)
        assertTrue(state.damaged)
        assertTrue(state.summary.contains("Soter check: timeout"))
    }
}