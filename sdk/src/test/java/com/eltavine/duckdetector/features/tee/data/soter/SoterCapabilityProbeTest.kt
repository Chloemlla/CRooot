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

import com.tencent.soter.core.model.SoterCoreResult
import com.tencent.soter.soterserver.SoterSessionResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for the SoterCapabilityProbe safety fix.
 *
 * These tests verify that the probe does NOT call removeAppGlobalSecureKey()
 * when the ASK pre-existed (i.e., it belongs to the host application).
 */
class SoterCapabilityProbeTest {

    @Test
    fun `inspect returns expected structure when all services available`() {
        val client = FakeSoterClient(
            nativeSupport = true,
            trebleConnected = true,
            hasAsk = true,
            authKeyOk = true,
        )
        val probe = SoterCapabilityProbe(
            client = client,
            environmentInspector = SoterEnvironmentInspector { SoterEnvironmentSnapshot() },
        )

        val state = probe.inspect()

        assertTrue(state.serviceReachable)
        assertTrue(state.keyPrepared)
        assertTrue(state.signSessionAvailable)
        assertTrue(state.available)
        assertFalse(state.damaged)
    }

    @Test
    fun `inspect reports unavailable when treble service not connected`() {
        val client = FakeSoterClient(
            nativeSupport = false,
            trebleConnected = false,
            hasAsk = false,
            authKeyOk = false,
        )
        val probe = SoterCapabilityProbe(
            client = client,
            environmentInspector = SoterEnvironmentInspector { SoterEnvironmentSnapshot() },
        )

        val state = probe.inspect()

        assertFalse(state.serviceReachable)
        assertFalse(state.available)
    }

    @Test
    fun `safety fix: never removes pre-existing ASK on retry`() {
        // Simulate the scenario where the ASK pre-existed (host's key)
        // The probe should NOT call removeAppGlobalSecureKey() on retry
        val client = FakeSoterClient(
            nativeSupport = true,
            trebleConnected = true,
            hasAsk = true,  // ASK pre-existed (host's key)
            authKeyOk = false,  // Auth key generation fails, triggering retry
        )
        val probe = SoterCapabilityProbe(
            client = client,
            environmentInspector = SoterEnvironmentInspector { SoterEnvironmentSnapshot() },
        )

        // This should not crash or throw, and should NOT call removeAppGlobalSecureKey()
        val state = probe.inspect()

        // Verify the probe completed without error
        assertTrue(state.serviceReachable)

        // The removeAppGlobalSecureKey should NOT have been called
        // because askPreExisted=true and the probe should skip the deletion
        assertEquals(0, client.removeAppGlobalSecureKeyCallCount)
    }

    @Test
    fun `safety fix: removes probe-generated ASK during cleanup`() {
        // Simulate the scenario where the ASK did NOT pre-exist (probe owns it)
        // The probe SHOULD be able to call removeAppGlobalSecureKey() during cleanup
        val client = FakeSoterClient(
            nativeSupport = true,
            trebleConnected = true,
            hasAsk = false,  // ASK did NOT pre-exist, probe owns it
            authKeyOk = true,  // Auth key generation succeeds
        )
        val probe = SoterCapabilityProbe(
            client = client,
            environmentInspector = SoterEnvironmentInspector { SoterEnvironmentSnapshot() },
        )

        // This should not crash or throw
        val state = probe.inspect()

        // The probe should have attempted to clean up since it owns the key
        assertTrue(state.serviceReachable)
        assertTrue(state.keyPrepared)
        // removeAppGlobalSecureKey should have been called during cleanup
        // (the probe generated the key, so it's safe to clean up)
        assertTrue(client.removeAppGlobalSecureKeyCallCount > 0)
    }

    @Test
    fun `safety fix: abnormal environment check still works`() {
        val client = FakeSoterClient(
            nativeSupport = false,
            trebleConnected = false,
            hasAsk = false,
            authKeyOk = false,
        )
        val probe = SoterCapabilityProbe(
            client = client,
            environmentInspector = SoterEnvironmentInspector {
                SoterEnvironmentSnapshot(
                    supportExpected = true,
                    simplifiedChineseLocale = true,
                    servicePackageVisible = false,
                )
            },
        )

        val state = probe.inspect()

        // Should report abnormal environment
        assertTrue(state.summary.contains("Soter"))
    }

    /**
     * A fake SoterClient that tracks calls for verification.
     * Returns null for SoterCoreResult when an operation should fail,
     * matching the probe's null-checking logic.
     */
    private class FakeSoterClient(
        private val nativeSupport: Boolean,
        private val trebleConnected: Boolean,
        private val hasAsk: Boolean,
        private val authKeyOk: Boolean,
    ) : SoterClient {

        var removeAppGlobalSecureKeyCallCount = 0
        var generateAppGlobalSecureKeyCallCount = 0
        var generateAuthKeyCallCount = 0
        var removeAuthKeyCallCount = 0

        override fun tryToInitSoterBeforeTreble() {}
        override fun tryToInitSoterTreble() {}
        override fun setUp() {}
        override fun isNativeSupportSoter(): Boolean = nativeSupport
        override fun getSoterCoreType(): Int = 1
        override fun isTrebleServiceConnected(): Boolean = trebleConnected
        override fun isSupportFingerprint(): Boolean = false
        override fun isSystemHasFingerprint(): Boolean = false
        override fun isSupportBiometric(biometricType: Int): Boolean = false
        override fun isSystemHasBiometric(biometricType: Int): Boolean = false
        override fun hasAppGlobalSecureKey(): Boolean = hasAsk || generateAppGlobalSecureKeyCallCount > 0
        override fun generateAppGlobalSecureKey(): SoterCoreResult? {
            generateAppGlobalSecureKeyCallCount++
            // Return null means failure (probe checks isSuccess())
            return null
        }
        override fun getAppGlobalSecureKeyModel(): Any? =
            if (hasAsk || generateAppGlobalSecureKeyCallCount > 0) "model" else null
        override fun generateAuthKey(alias: String): SoterCoreResult? {
            generateAuthKeyCallCount++
            // Return null means failure (probe checks isSuccess())
            return if (authKeyOk) SoterCoreResult() else null
        }
        override fun hasAuthKey(alias: String): Boolean = authKeyOk
        override fun getAuthKeyModel(alias: String): Any? = if (authKeyOk) "model" else null
        override fun initSigh(alias: String, challenge: String): SoterSessionResult? =
            SoterSessionResult().apply { resultCode = 0; session = 12345L }
        override fun removeAuthKey(alias: String, autoDeleteAsk: Boolean): SoterCoreResult? {
            removeAuthKeyCallCount++
            return SoterCoreResult()
        }
        override fun removeAppGlobalSecureKey(): SoterCoreResult? {
            removeAppGlobalSecureKeyCallCount++
            return SoterCoreResult()
        }
    }
}