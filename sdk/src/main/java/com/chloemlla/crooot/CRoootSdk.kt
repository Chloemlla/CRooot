package com.chloemlla.crooot

import android.content.Context
import com.eltavine.duckdetector.features.bootloader.data.repository.BootloaderRepository
import com.eltavine.duckdetector.features.customrom.data.repository.CustomRomRepository
import com.eltavine.duckdetector.features.dangerousapps.data.repository.DangerousAppsRepository
import com.eltavine.duckdetector.features.deviceinfo.data.repository.DeviceInfoRepository
import com.eltavine.duckdetector.features.kernelcheck.data.repository.KernelCheckRepository
import com.eltavine.duckdetector.features.lsposed.data.repository.LSPosedRepository
import com.eltavine.duckdetector.features.memory.data.repository.MemoryRepository
import com.eltavine.duckdetector.features.mount.data.repository.MountRepository
import com.eltavine.duckdetector.features.nativeroot.data.repository.NativeRootRepository
import com.eltavine.duckdetector.features.playintegrityfix.data.repository.PlayIntegrityFixRepository
import com.eltavine.duckdetector.features.selinux.data.repository.SelinuxRepository
import com.eltavine.duckdetector.features.su.data.repository.SuRepository
import com.eltavine.duckdetector.features.systemproperties.data.repository.SystemPropertiesRepository
import com.eltavine.duckdetector.features.tee.data.repository.TeeRepository
import com.eltavine.duckdetector.features.virtualization.data.repository.VirtualizationRepository
import com.eltavine.duckdetector.features.zygisk.data.repository.ZygiskRepository
import com.juanma0511.rootdetector.detector.HwSecurityDetector
import com.juanma0511.rootdetector.detector.RootDetector
import com.juanma0511.rootdetector.model.HwScanResult
import com.juanma0511.rootdetector.model.ScanResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext

/** Selects the optional detector groups included in a [CRoootSdk.scan] call. */
data class CRoootScanOptions(
    /**
     * Runs the optional KKND hardware result group (Keystore, TEE, and verified boot). This does
     * not disable the Duck `tee` report when [includeDuckFeatures] remains `true`.
     */
    val includeHardware: Boolean = true,
    /** Runs the 16 Duck feature repositories and populates [CRoootScanResult.duckReports]. */
    val includeDuckFeatures: Boolean = true,
)

/**
 * Structured evidence returned by [CRoootSdk.scan].
 *
 * [duckReports] is empty when [CRoootScanOptions.includeDuckFeatures] is `false`. Otherwise its
 * stable keys are `bootloader`, `customRom`, `dangerousApps`, `deviceInfo`, `kernel`, `lsposed`,
 * `memory`, `mount`, `nativeRoot`, `playIntegrityFix`, `selinux`, `su`, `systemProperties`, `tee`,
 * `virtualization`, and `zygisk`. Values are the corresponding Duck domain report objects.
 *
 * Results are heuristic evidence. A clean result is not proof that a device is trustworthy, and
 * unavailable or restricted probes must not be interpreted as clean signals.
 */
data class CRoootScanResult(
    /**
     * KKND root, integrity, mount, SELinux, and native evidence. `isRooted` means at least one
     * detected HIGH item; `isSuspicious` means at least one detected item of any severity.
     */
    val kkndRoot: ScanResult,
    /**
     * KKND hardware/TEE/verified-boot evidence when enabled. Its `overallOk` value ignores UNKNOWN
     * items, so callers must inspect individual item statuses and coverage.
     */
    val kkndHardware: HwScanResult?,
    /** Duck feature reports keyed by detector feature name; use safe casts for the `Any?` values. */
    val duckReports: Map<String, Any?>,
    /** Overall wall-clock delta in milliseconds; intended for diagnostics, not benchmarking. */
    val durationMs: Long,
)

/**
 * UI-independent entry point for CRooot device-security scans.
 *
 * Instances retain only the application [Context]. A scan is a suspending operation that moves
 * detector work away from the caller thread. Run one scan at a time from a lifecycle-owned
 * coroutine and handle exceptions or timeouts at the integration boundary.
 */
class CRoootSdk private constructor(private val context: Context) {
    /**
     * Runs the requested detector groups concurrently and returns their combined evidence.
     *
     * If an uncaught detector exception escapes a child coroutine, structured concurrency cancels
     * the remaining children and this function rethrows the failure. Caller cancellation is
     * cooperative; blocking native, process, or Keystore work may not stop immediately.
     *
     * A full scan is not purely passive: detector implementations can create and remove temporary
     * probe files or paths, open loopback sockets, send an explicit probe broadcast, and create or
     * delete AndroidKeyStore test keys. Hosts must not use the fixed aliases `rootdetector_tee_probe`,
     * `rootdetector_ks_backing`, or `rootdetector_sb_key`.
     *
     * Version 0.1.0 warning: the Duck TEE Soter retry path can remove or replace an existing Soter
     * App Global Secure Key. Hosts using Tencent Soter must set [CRoootScanOptions.includeDuckFeatures]
     * to `false` until that detector is fixed.
     */
    suspend fun scan(
        options: CRoootScanOptions = CRoootScanOptions(),
    ): CRoootScanResult = withContext(Dispatchers.Default) {
        val started = System.currentTimeMillis()
        val appContext = context.applicationContext
        coroutineScope {
            val root = async(Dispatchers.IO) { RootDetector(appContext).runAllChecks() }
            val hardware = if (options.includeHardware) {
                async(Dispatchers.IO) { HwSecurityDetector(appContext).runAllChecks() }
            } else {
                null
            }
            val duck: List<kotlinx.coroutines.Deferred<Pair<String, Any?>>> = if (options.includeDuckFeatures) listOf(
                async { "bootloader" to BootloaderRepository(appContext).scan() },
                async { "customRom" to CustomRomRepository(appContext).scan() },
                async { "dangerousApps" to DangerousAppsRepository(appContext).scan() },
                async { "deviceInfo" to DeviceInfoRepository(appContext).scan() },
                async { "kernel" to KernelCheckRepository().scan() },
                async { "lsposed" to LSPosedRepository(appContext).scan() },
                async { "memory" to MemoryRepository().scan() },
                async { "mount" to MountRepository().scan() },
                async { "nativeRoot" to NativeRootRepository(appContext).scan() },
                async { "playIntegrityFix" to PlayIntegrityFixRepository().scan() },
                async { "selinux" to SelinuxRepository(appContext).scan() },
                async { "su" to SuRepository().scan() },
                async { "systemProperties" to SystemPropertiesRepository().scan() },
                async { "tee" to TeeRepository(appContext).scan() },
                async { "virtualization" to VirtualizationRepository(appContext).scan() },
                async { "zygisk" to ZygiskRepository(appContext).scan() },
            ) else emptyList()
            val rootItems = root.await()
            val rootResult = ScanResult(rootItems, System.currentTimeMillis() - started)
            val hardwareItems = hardware?.await()
            val hardwareResult = hardwareItems?.let { HwScanResult(it, System.currentTimeMillis() - started) }
            CRoootScanResult(rootResult, hardwareResult, duck.awaitAll().toMap(), System.currentTimeMillis() - started)
        }
    }

    companion object {
        /** Creates an SDK instance backed by [Context.getApplicationContext]. */
        fun create(context: Context): CRoootSdk = CRoootSdk(context.applicationContext)
    }
}
