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

data class CRoootScanOptions(
    val includeHardware: Boolean = true,
    val includeDuckFeatures: Boolean = true,
)

data class CRoootScanResult(
    /** Complete KKND root, integrity, mount, SELinux and native evidence. */
    val kkndRoot: ScanResult,
    /** Complete KKND hardware/TEE/verified-boot evidence when enabled. */
    val kkndHardware: HwScanResult?,
    /** Duck feature reports keyed by detector feature name. */
    val duckReports: Map<String, Any?>,
    val durationMs: Long,
)

class CRoootSdk private constructor(private val context: Context) {
    suspend fun scan(options: CRoootScanOptions = CRoootScanOptions()): CRoootScanResult = withContext(Dispatchers.Default) {
        val started = System.currentTimeMillis()
        val appContext = context.applicationContext
        coroutineScope {
            val root = async(Dispatchers.IO) { RootDetector(appContext).runAllChecks() }
            val hardware = if (options.includeHardware) async(Dispatchers.IO) { HwSecurityDetector(appContext).runAllChecks() } else null
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
        fun create(context: Context): CRoootSdk = CRoootSdk(context.applicationContext)
    }
}
