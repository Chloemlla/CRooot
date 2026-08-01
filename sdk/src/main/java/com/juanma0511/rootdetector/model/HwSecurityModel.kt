package com.juanma0511.rootdetector.model

enum class CheckStatus { PASS, WARN, FAIL, UNKNOWN }

data class HwCheckItem(
    val id: String,
    val name: String,
    val description: String,
    val group: HwGroup,
    val status: CheckStatus,
    val value: String,          
    val expected: String? = null, 
    val detail: String? = null
)

enum class HwGroup {
    KEYSTORE,
    BOOT,
    VBMETA,
    SYSTEM_PROPS
}

data class HwScanResult(
    val items: List<HwCheckItem>,
    val scanDurationMs: Long,
    val timestamp: Long = System.currentTimeMillis()
) {
    val failCount: Int get() = items.count { it.status == CheckStatus.FAIL }
    val warnCount: Int get() = items.count { it.status == CheckStatus.WARN }
    val passCount: Int get() = items.count { it.status == CheckStatus.PASS }
    val overallOk: Boolean get() = failCount == 0 && warnCount == 0
}

enum class HwScanState { IDLE, SCANNING, DONE }
