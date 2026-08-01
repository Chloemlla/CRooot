package com.juanma0511.rootdetector.detector

import com.juanma0511.rootdetector.model.DetectionCategory
import com.juanma0511.rootdetector.model.DetectionItem
import com.juanma0511.rootdetector.model.Severity
import java.io.File

class MountNamespaceDetector {

    fun detect(): DetectionItem {
        val evidence = linkedSetOf<String>()
        val trustedLocked = DetectorTrust.bootLooksTrustedLocked()

        fun readMountInfo(path: String): Map<String, String> {
            val result = linkedMapOf<String, String>()
            runCatching {
                File(path).forEachLine { line ->
                    val parts = line.split(" ")
                    val sep = parts.indexOf("-")
                    if (parts.size < 10 || sep == -1) return@forEachLine
                    val mountPoint = parts[4]
                    val fileSystem = parts.getOrNull(sep + 1).orEmpty()
                    val source = parts.getOrNull(sep + 2).orEmpty()
                    if (DetectorTrust.shouldTrackSensitiveMount(mountPoint)) {
                        result[mountPoint] = "$source [$fileSystem]"
                    }
                }
            }
            return result
        }

        val selfMounts = readMountInfo("/proc/self/mountinfo")
        val initMounts = readMountInfo("/proc/1/mountinfo")

        selfMounts.forEach { (mountPoint, selfSignature) ->
            val initSignature = initMounts[mountPoint]
            if (initSignature == null) {
                if (DetectorTrust.hasRootMountSignal(selfSignature, mountPoint, trustedLocked)) {
                    evidence += "$mountPoint self-only=$selfSignature"
                }
            } else if (initSignature != selfSignature) {
                val combined = "$selfSignature :: $initSignature"
                if (DetectorTrust.hasRootMountSignal(combined, mountPoint, trustedLocked)) {
                    evidence += "$mountPoint self=$selfSignature init=$initSignature"
                }
            }
        }

        return DetectionItem(
            id = "mount_namespace",
            name = "Mount Namespace Isolation",
            description = "Sensitive mount points diverge from init only when root-specific namespace traces are present",
            category = DetectionCategory.MOUNT_POINTS,
            severity = Severity.HIGH,
            detected = evidence.isNotEmpty(),
            detail = evidence.take(6).joinToString("\n").ifEmpty { null }
        )
    }
}
