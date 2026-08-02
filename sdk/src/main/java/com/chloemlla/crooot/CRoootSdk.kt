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
    /**
     * Master switch for the 16 Duck feature repositories. When `false`, all Duck reports are
     * skipped regardless of individual feature flags. When `true`, individual feature flags
     * control which features run.
     */
    val includeDuckFeatures: Boolean = true,
    /** Runs the Duck `bootloader` feature repository. */
    val includeBootloader: Boolean = true,
    /** Runs the Duck `customRom` feature repository. */
    val includeCustomRom: Boolean = true,
    /** Runs the Duck `dangerousApps` feature repository. */
    val includeDangerousApps: Boolean = true,
    /** Runs the Duck `deviceInfo` feature repository. */
    val includeDeviceInfo: Boolean = true,
    /** Runs the Duck `kernel` feature repository. */
    val includeKernel: Boolean = true,
    /** Runs the Duck `lsposed` feature repository. */
    val includeLsposed: Boolean = true,
    /** Runs the Duck `memory` feature repository. */
    val includeMemory: Boolean = true,
    /** Runs the Duck `mount` feature repository. */
    val includeMount: Boolean = true,
    /** Runs the Duck `nativeRoot` feature repository. */
    val includeNativeRoot: Boolean = true,
    /** Runs the Duck `playIntegrityFix` feature repository. */
    val includePlayIntegrityFix: Boolean = true,
    /** Runs the Duck `selinux` feature repository. */
    val includeSelinux: Boolean = true,
    /** Runs the Duck `su` feature repository. */
    val includeSu: Boolean = true,
    /** Runs the Duck `systemProperties` feature repository. */
    val includeSystemProperties: Boolean = true,
    /** Runs the Duck `tee` feature repository. */
    val includeTee: Boolean = true,
    /** Runs the Duck `virtualization` feature repository. */
    val includeVirtualization: Boolean = true,
    /** Runs the Duck `zygisk` feature repository. */
    val includeZygisk: Boolean = true,
) {
    /**
     * Returns the list of Duck feature keys that should be scanned based on the current options.
     * Each entry is a pair of (key, async block returning the report).
     */
    internal fun duckFeatureKeys(): List<String> = buildList {
        if (!includeDuckFeatures) return@buildList emptyList()
        if (includeBootloader) add("bootloader")
        if (includeCustomRom) add("customRom")
        if (includeDangerousApps) add("dangerousApps")
        if (includeDeviceInfo) add("deviceInfo")
        if (includeKernel) add("kernel")
        if (includeLsposed) add("lsposed")
        if (includeMemory) add("memory")
        if (includeMount) add("mount")
        if (includeNativeRoot) add("nativeRoot")
        if (includePlayIntegrityFix) add("playIntegrityFix")
        if (includeSelinux) add("selinux")
        if (includeSu) add("su")
        if (includeSystemProperties) add("systemProperties")
        if (includeTee) add("tee")
        if (includeVirtualization) add("virtualization")
        if (includeZygisk) add("zygisk")
    }
}

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
) {
    /**
     * Convenience property that returns `true` when the KKND root detector found at least one
     * HIGH-severity root indication. Identical to [kkndRoot.isRooted].
     */
    val isRooted: Boolean get() = kkndRoot.isRooted

    /**
     * Convenience property that returns `true` when the KKND root detector found any indication
     * of root or tampering (any severity). Identical to [kkndRoot.isSuspicious].
     */
    val isSuspicious: Boolean get() = kkndRoot.isSuspicious

    /**
     * Returns a human-readable summary of the scan results suitable for diagnostic logging.
     * The summary is not a security verdict and must not be used as the sole basis for
     * device-trust decisions.
     */
    fun summary(): String = buildString {
        append("CRooot scan completed in ${durationMs}ms")
        if (kkndRoot.isRooted) { append("; ROOTED") }
        if (kkndRoot.isSuspicious) { append("; suspicious") }
        kkndHardware?.let { hw ->
            append("; hardware=${hw.overallOk}")
        }
        append("; duckReports=${duckReports.size}/16")
    }
}

/**
 * UI-independent entry point for CRooot device-security scans.
 *
 * Instances retain only the application [Context]. A scan is a suspending operation that moves
 * detector work away from the caller thread. Run one scan at a time from a lifecycle-owned
 * coroutine and handle exceptions or timeouts at the integration boundary.
 */
class CRoootSdk private constructor(private val context: Context) {
    /** Stable public SDK version for local report metadata. */
    val sdkVersion: String get() = SDK_VERSION

    /**
     * Produces the stable, privacy-aware local report model for third-party applications.
     * The existing [scan] API remains unchanged for source and binary compatibility.
     */
    suspend fun scanReport(
        options: CRoootReportOptions = CRoootReportOptions(),
        onEvent: ((CRoootScanEvent) -> Unit)? = null,
    ): CRoootLocalReport {
        onEvent?.invoke(CRoootScanEvent.Started(options.profile))
        val startedAtMillis = System.currentTimeMillis()
        return try {
            val report = CRoootLocalReportMapper.map(
                result = scan(options.effectiveScanOptions()),
                options = options,
                startedAtMillis = startedAtMillis,
            )
            onEvent?.invoke(CRoootScanEvent.Completed(report))
            report
        } catch (failure: Throwable) {
            onEvent?.invoke(CRoootScanEvent.Failed(failure))
            throw failure
        }
    }

    /** Maps an already-completed legacy result without running another scan. */
    fun mapReport(
        result: CRoootScanResult,
        options: CRoootReportOptions = CRoootReportOptions(),
        startedAtMillis: Long = System.currentTimeMillis() - result.durationMs,
    ): CRoootLocalReport = CRoootLocalReportMapper.map(result, options, startedAtMillis)

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
     * to `false` until that detector is fixed. As of 0.1.0, the per-feature flag [CRoootScanOptions.includeTee]
     * can be used to disable only the TEE report while keeping other Duck reports enabled.
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
            val duck: List<kotlinx.coroutines.Deferred<Pair<String, Any?>>> = if (options.includeDuckFeatures) {
                val keys = options.duckFeatureKeys()
                if (keys.isEmpty()) emptyList()
                else {
                    val bootloaderRepo = BootloaderRepository(appContext)
                    val customRomRepo = CustomRomRepository(appContext)
                    val dangerousAppsRepo = DangerousAppsRepository(appContext)
                    val deviceInfoRepo = DeviceInfoRepository(appContext)
                    val kernelCheckRepo = KernelCheckRepository()
                    val lsposedRepo = LSPosedRepository(appContext)
                    val memoryRepo = MemoryRepository()
                    val mountRepo = MountRepository()
                    val nativeRootRepo = NativeRootRepository(appContext)
                    val playIntegrityFixRepo = PlayIntegrityFixRepository()
                    val selinuxRepo = SelinuxRepository(appContext)
                    val suRepo = SuRepository()
                    val systemPropertiesRepo = SystemPropertiesRepository()
                    val teeRepo = TeeRepository(appContext)
                    val virtualizationRepo = VirtualizationRepository(appContext)
                    val zygiskRepo = ZygiskRepository(appContext)
                    keys.map { key ->
                        async {
                            key to when (key) {
                                "bootloader" -> bootloaderRepo.scan()
                                "customRom" -> customRomRepo.scan()
                                "dangerousApps" -> dangerousAppsRepo.scan()
                                "deviceInfo" -> deviceInfoRepo.scan()
                                "kernel" -> kernelCheckRepo.scan()
                                "lsposed" -> lsposedRepo.scan()
                                "memory" -> memoryRepo.scan()
                                "mount" -> mountRepo.scan()
                                "nativeRoot" -> nativeRootRepo.scan()
                                "playIntegrityFix" -> playIntegrityFixRepo.scan()
                                "selinux" -> selinuxRepo.scan()
                                "su" -> suRepo.scan()
                                "systemProperties" -> systemPropertiesRepo.scan()
                                "tee" -> teeRepo.scan()
                                "virtualization" -> virtualizationRepo.scan()
                                "zygisk" -> zygiskRepo.scan()
                                else -> throw IllegalArgumentException("Unknown Duck feature key: $key")
                            }
                        }
                    }
                }
            } else emptyList()
            val rootItems = root.await()
            val rootResult = ScanResult(rootItems, System.currentTimeMillis() - started)
            val hardwareItems = hardware?.await()
            val hardwareResult = hardwareItems?.let { HwScanResult(it, System.currentTimeMillis() - started) }
            CRoootScanResult(rootResult, hardwareResult, duck.awaitAll().toMap(), System.currentTimeMillis() - started)
        }
    }

    companion object {
        const val SDK_VERSION: String = "0.1.0"

        /** Creates an SDK instance backed by [Context.getApplicationContext]. */
        fun create(context: Context): CRoootSdk = CRoootSdk(context.applicationContext)
    }
}
