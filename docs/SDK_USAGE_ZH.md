# CRooot Android SDK 第三方接入指南

[English](SDK_USAGE.md) · [文档索引](README.md) · [项目 README](../README_ZH.MD)

本文面向接入 CRooot `0.1.0` 的第三方 Android 应用，依据真实源码与已发布 AAR 说明稳定门面、安装方式、结果模型、运行副作用、已知限制和生产上线要求。

> **`0.1.0` Soter 严重警告：** Duck TEE 的 Soter 重试路径可能删除或替换宿主已有的 Tencent Soter App Global Secure Key。使用 Soter 的宿主在修复前必须设置 `includeDuckFeatures=false`。`0.1.0` 无法只关闭 `tee`，因此其他 Duck 报告也会同时关闭。

## 1. 支持的 API 与兼容性

受支持的稳定入口为：

```kotlin
com.chloemlla.crooot.CRoootSdk
```

AAR 还包含完整的 Duck 与 KKND 实现包。除本文明确用于结果类型转换的报告模型外，请将这些包视为实现细节。

| 项目 | `0.1.0` 取值 |
| --- | --- |
| Maven 坐标 | `com.chloemlla.crooot:crooot-sdk:0.1.0` |
| 最低设备 API | Android 10 / API 29 |
| 宿主最低 `compileSdk` | 36，由 AAR metadata 声明 |
| 宿主 `targetSdk` | 由宿主应用控制 |
| 主要 API 语言 | Kotlin-first suspend API |
| 原生库 | `libchloemlla-crooot.so` |
| 已打包 ABI | `arm64-v8a`、`armeabi-v7a`、`x86`、`x86_64` |

四个 ABI 均包含原生库，但底层能力并不完全等价。部分虚拟化 Trap 汇编仅支持 arm64。必须检查 `nativeAvailable`、`asmSupported` 等可用性字段。

SDK 与 UI 无关，不要求 Compose、Activity、Coil 或原应用界面。通过 Maven/AAR 接入时不需要 NDK 或 CMake；从源码构建使用 JDK 17、Android SDK/Build Tools 36.0.0、NDK 30.0.15729638 和 CMake 4.1.2。

## 2. 选择接入方式

### 2.1 GitHub Packages——推荐

GitHub Packages 的 Maven 下载需要认证。创建具备 `read:packages` 的 classic personal access token，并保存在仓库之外，例如用户级 `~/.gradle/gradle.properties`：

```properties
gpr.user=YOUR_GITHUB_USERNAME
gpr.key=YOUR_CLASSIC_PAT_WITH_READ_PACKAGES
```

在 `settings.gradle.kts` 中添加全部所需仓库。传递依赖 Tencent Soter wrapper 来自 JitPack：

```kotlin
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven(url = "https://jitpack.io") {
            content { includeGroup("com.github.Tencent.soter") }
        }
        maven {
            url = uri("https://maven.pkg.github.com/Chloemlla/CRooot")
            content { includeGroup("com.chloemlla.crooot") }
            credentials {
                username = providers.gradleProperty("gpr.user")
                    .orElse(providers.environmentVariable("GITHUB_ACTOR"))
                    .orNull
                password = providers.gradleProperty("gpr.key")
                    .orElse(providers.environmentVariable("GITHUB_TOKEN"))
                    .orNull
            }
        }
    }
}
```

在宿主模块添加依赖：

```kotlin
dependencies {
    implementation("com.chloemlla.crooot:crooot-sdk:0.1.0")
}
```

CI 中应使用具备 `read:packages` 的 secret。仓库的 `GITHUB_TOKEN` 只有在 Package/仓库访问关系已正确授权时才能读取对应包。

### 2.2 本地 AAR

在完整 CRooot 仓库中构建 AAR：

```bash
./gradlew :sdk:assembleRelease
```

输出位于 `sdk/build/outputs/aar/sdk-release.aar`。复制或重命名到宿主模块，例如 `app/libs/crooot-sdk-0.1.0.aar`：

```kotlin
dependencies {
    implementation(files("libs/crooot-sdk-0.1.0.aar"))
}
```

文件型 AAR 没有 Maven POM，需要手动声明当前运行时依赖：

```kotlin
dependencies {
    implementation(files("libs/crooot-sdk-0.1.0.aar"))
    implementation("androidx.core:core-ktx:1.18.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")
    implementation("androidx.datastore:datastore-preferences:1.2.1")
    implementation("org.bouncycastle:bcprov-jdk18on:1.85")
    implementation("org.lsposed.hiddenapibypass:hiddenapibypass:6.1")
    implementation("com.github.Tencent.soter:soter-wrapper:2.0.7")
}
```

仍需保留 Maven 示例中的 JitPack 配置。必须使用完整 `.aar`；只抽取 `classes.jar` 会丢失 Manifest、原生库、assets 和 consumer ProGuard 规则。

Actions artifact 不是永久发布渠道，可能过期。`v0.1.0` workflow 发布了 GitHub Packages 并上传了 Actions AAR artifact，但没有创建 GitHub Release。

### 2.3 通过 included build 使用源码

`sdk/` 目录不能独立构建，它依赖本仓库的 version catalog、Gradle properties 和 build logic。应将完整 CRooot 仓库作为相邻目录，并替换发布坐标：

```kotlin
// 宿主 settings.gradle.kts
includeBuild("../CRooot") {
    dependencySubstitution {
        substitute(module("com.chloemlla.crooot:crooot-sdk"))
            .using(project(":sdk"))
    }
}
```

宿主依赖仍写为：

```kotlin
implementation("com.chloemlla.crooot:crooot-sdk:0.1.0")
```

不要只复制 `sdk/` 到另一工程，除非同时迁移所有构建属性、插件、version-catalog alias、原生源码、资源和 assets。

## 3. 宿主 Manifest 配置

### 3.1 AAR 自动合并的组件

SDK Manifest 会合并以下非导出 Service：

| Service | 进程行为 |
| --- | --- |
| `ZygiskFdTrapDetectorService` | 独立 `:zygisk_fd_detector` 进程 |
| `VirtualizationProbeService` | 独立 `:virtualization_probe` 进程 |
| `VirtualizationIsolatedProbeService` | 隔离进程 |
| `SelinuxContextValidityCarrierService` | 隔离进程 |
| `TeeGrantDomainGranteeService` | 隔离进程 |

请检查宿主 APK 的最终合并 Manifest。除非已经接受并验证覆盖损失，否则不要移除这些 Service。

### 3.2 权限

AAR 不声明任何 `uses-permission`，所有权限均由宿主决定。

为可靠运行完整 Duck TEE 深检，应声明普通生物识别权限：

```xml
<uses-permission android:name="android.permission.USE_BIOMETRIC" />
```

在具备生物识别硬件的设备上，缺少该权限可能使 `BiometricManager.canAuthenticate()` 抛出异常，导致整个 Duck `tee` 报告进入 `FAILED`。

其他可选权限：

```xml
<!-- 旧版/Soter 兼容路径。 -->
<uses-permission android:name="android.permission.USE_FINGERPRINT" />

<!-- Scene/Frida 本机回环探针；实现层在线刷新也需要。 -->
<uses-permission android:name="android.permission.INTERNET" />

<!-- 实现层在线 TEE 刷新的网络状态预检。 -->
<uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />
```

全新安装时，受支持 facade 使用内置 TEE 吊销快照，不会发起远程 HTTPS 请求。实现包中的 consent store 技术上可被访问，且持久化状态可能开启对 `https://android.googleapis.com/attestation/status` 的请求；该路径不属于 `0.1.0` 稳定的 `com.chloemlla.crooot` facade。

原应用使用过以下可选 Soter 包可见性声明，但 SDK Manifest 未包含：

```xml
<queries>
    <package android:name="com.tencent.soter.soterserver" />
</queries>
```

该 query 不能消除前述 Soter 密钥破坏风险。

### 3.3 包可见性

`QUERY_ALL_PACKAGES` 可提高应用清单覆盖度，但受分发平台政策约束：

```xml
<uses-permission android:name="android.permission.QUERY_ALL_PACKAGES" />
```

只有宿主符合分发平台政策时才应添加。缺少该权限时，Custom ROM、危险应用、LSPosed 和虚拟化中的包检测可能降级。

报告中的 `FULL`/`RESTRICTED` 是启发式结果：实现仅根据可见应用数量推断，不能证明 `QUERY_ALL_PACKAGES` 是否真正授予。

### 3.4 `0.1.0` 的 Manifest 覆盖缺口

AAR 未包含原应用的 app-zygote 与 early-start 接线：

- 未设置 application `android:zygotePreloadName`；
- SELinux carrier 未设置 `android:useAppZygote=true`；
- 未声明 KKND DirtySepolicy Service；
- 未包含 NativeActivity early-preload launcher。

因此，高级 SELinux app-zygote、两项 KKND DirtySepolicy、early mount/virtualization preload 可能报告覆盖降低或不可用。不能宣称 SDK 与原应用检测能力完全等价。

不要把原 NativeActivity launcher 直接复制到第三方应用；其 native handoff 假定 `${applicationId}.MainActivity`，不是通用宿主契约。

## 4. 运行副作用与生产安全

完整扫描在本地执行，但不是纯被动读取。

### 4.1 Soter 严重状态变更

`0.1.0` 的 Soter 重试路径即使发现扫描前已有 App Global Secure Key，也可能调用 `removeAppGlobalSecureKey()` 并生成替代密钥，从而破坏宿主已有的 Soter 业务状态。

使用 Tencent Soter 的宿主必须配置：

```kotlin
CRoootScanOptions(includeDuckFeatures = false)
```

`0.1.0` 没有受支持的方式只禁用 `tee` 而保留其他 Duck 报告。

### 4.2 Android Keystore 写操作

硬件与 Duck TEE 扫描会生成、导入、授权、更新、使用和删除大量 AndroidKeyStore 测试条目。Duck 大多数 alias 含时间值，但进程死亡或厂商异常可能留下临时条目。

KKND 硬件检查会在使用前后删除以下固定 alias：

```text
rootdetector_tee_probe
rootdetector_ks_backing
rootdetector_sb_key
```

宿主绝不能使用这些 alias。

operation-pruning 探针会同时打开 18 个 ECDSA 签名操作，可能使宿主并发的 Keystore 加解密/签名会话失效或被裁剪。其他 TEE 探针还会临时向隔离进程授权测试密钥访问，并尝试撤销。

### 4.3 进程级 Hook

TEE 深检会临时替换当前进程 `ServiceManager` 中的 Keystore Binder cache 以捕获返回值。代码会尝试恢复，但宿主同期发起的 Keystore 调用可能经过探针 Hook。

探针还会调用 `HiddenApiBypass.addHiddenApiExemptions("")`。隐藏 API exemption 是进程级状态，扫描结束时不会恢复。

### 4.4 文件、广播、Socket 与进程

危险应用检测可能：

- 向已知 Scene 组件发送显式命令型探针广播；
- 通过该交互短暂创建、检查并删除随机外部存储探针文件；
- 尝试在 `/dev` 下创建 mode 为 `0` 的 marker 目录，成功后删除；
- 启动 shell 命令并读取进程、挂载、属性和 Service 输出。

其他检查会连接 Scene/Frida 本机回环端口、绑定 SDK Service，并启动独立或隔离进程。这些行为可能出现在 EDR、审计、包管理、网络、文件系统和进程遥测中。

所有清理均为尽力而为；崩溃、force-stop、超时或进程终止可能中断清理。

### 4.5 性能与取消

facade 会并发启动 KKND root、可选 KKND hardware 与全部 16 个 Duck repository；Duck TEE 内部还会展开大量 Keystore 探针。时序侧信道路径约执行 500 对 Binder/Keystore 采样，pruning 路径占用 18 个操作槽。

`0.1.0` 不提供：

- 内置总超时；
- 进度 API；
- 扫描互斥；
- dispatcher/executor 注入；
- 对阻塞 JNI、进程、Socket、Binder、Keystore 工作的即时抢占取消。

SDK 共享使用 `Dispatchers.Default` 与 `Dispatchers.IO`，可能影响宿主同进程协程吞吐。调用方取消和 `withTimeout` 只能限制宿主等待时间，底层阻塞操作仍可能短暂继续；部分 `runCatching`/`catch(Throwable)` 路径也可能延迟取消传播。

### 4.6 宿主必须遵守的运行规则

- 整个进程同一时刻只允许一次扫描。
- 不要在冷启动、登录、支付、签名、密钥轮换或任何宿主 Keystore 操作期间扫描。
- 优先采用用户/管理员明确触发或受控后台维护窗口。
- 上线前与宿主安全/EDR 团队确认主动探针行为。
- 缓存宿主派生判定，不要高频重复扫描。
- 测试进程死亡，并检查残留 alias/文件。
- 使用 Soter 的宿主在缺陷修复前必须关闭全部 Duck features。

## 5. Kotlin 基础调用

`CRoootSdk` 仅保存 Application Context，建议复用单一实例：

```kotlin
import com.chloemlla.crooot.CRoootScanOptions
import com.chloemlla.crooot.CRoootScanResult
import com.chloemlla.crooot.CRoootSdk

val sdk = CRoootSdk.create(applicationContext)

suspend fun runSecurityScan(): CRoootScanResult {
    return sdk.scan(
        CRoootScanOptions(
            includeHardware = true,
            includeDuckFeatures = true, // v0.1.0 中使用 Soter 的宿主必须设为 false。
        ),
    )
}
```

选项语义：

| 选项 | 行为 |
| --- | --- |
| `includeHardware=true` | 增加 `kkndHardware`，不控制 Duck `tee` |
| `includeHardware=false` | 只跳过 KKND 硬件检查 |
| `includeDuckFeatures=true` | 运行全部 16 个 Duck repository |
| `includeDuckFeatures=false` | 返回空 `duckReports` map |

KKND root 检查始终运行。

## 6. 生命周期、串行化、超时与 UI 状态

以下 ViewModel 会串行扫描并暴露明确状态。超时只限制宿主等待，不保证底层阻塞探针立即终止。

```kotlin
import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.chloemlla.crooot.CRoootScanOptions
import com.chloemlla.crooot.CRoootScanResult
import com.chloemlla.crooot.CRoootSdk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withTimeout

sealed interface CRoootUiState {
    data object Idle : CRoootUiState
    data object Running : CRoootUiState
    data class Success(val result: CRoootScanResult) : CRoootUiState
    data class Failure(val message: String) : CRoootUiState
}

class CRoootViewModel(application: Application) : AndroidViewModel(application) {
    private val sdk = CRoootSdk.create(application)
    private val scanMutex = Mutex()

    private val _state = MutableStateFlow<CRoootUiState>(CRoootUiState.Idle)
    val state: StateFlow<CRoootUiState> = _state.asStateFlow()

    fun startScan(includeDuckFeatures: Boolean) {
        viewModelScope.launch {
            if (!scanMutex.tryLock()) return@launch
            try {
                _state.value = CRoootUiState.Running
                val result = withTimeout(60_000) {
                    sdk.scan(
                        CRoootScanOptions(
                            includeHardware = true,
                            includeDuckFeatures = includeDuckFeatures,
                        ),
                    )
                }
                _state.value = CRoootUiState.Success(result)
            } catch (_: TimeoutCancellationException) {
                _state.value = CRoootUiState.Failure(
                    "CRooot 已超时；阻塞探针的清理可能仍在结束中。",
                )
            } catch (cancelled: CancellationException) {
                _state.value = CRoootUiState.Idle
                throw cancelled
            } catch (failure: Throwable) {
                _state.value = CRoootUiState.Failure(
                    failure.message ?: failure::class.java.simpleName,
                )
            } finally {
                scanMutex.unlock()
            }
        }
    }
}
```

应在真实设备测量后设置更合适的超时；低性能或限制严格的设备上，完整 Duck TEE 扫描可能超过 60 秒。

## 7. 结果模型

### 7.1 `CRoootScanResult`

| 字段 | 含义 |
| --- | --- |
| `kkndRoot` | 始终存在的 KKND root/integrity 证据 |
| `kkndHardware` | 可选 KKND hardware/TEE/verified-boot 证据 |
| `duckReports` | 固定 key 的 Duck domain report；关闭时为空 |
| `durationMs` | 使用 `System.currentTimeMillis()` 计算的整体墙钟差值 |

`durationMs` 仅适合诊断，不是单调时钟基准，系统时钟变化可能影响结果。`kkndRoot.scanDurationMs` 与 `kkndHardware.scanDurationMs` 也从同一整体开始时间计算到各自 await 点，并非各引擎独立耗时。

### 7.2 KKND Root 结果

`kkndRoot.items` 包含 `DetectionItem`：

```kotlin
data class DetectionItem(
    val id: String,
    val name: String,
    val description: String,
    val category: DetectionCategory,
    val severity: Severity,
    val detected: Boolean,
    val detail: String?,
)
```

摘要语义：

- `isRooted`：至少一个 `detected && severity == HIGH`；
- `isSuspicious`：任意严重度存在 detected item；
- `detectedCount`、`highRiskCount`、`warningCount`：便捷计数。

读取摘要布尔值后仍应保留并展示单项证据。

### 7.3 KKND 硬件结果

`kkndHardware?.items` 包含 `HwCheckItem`，提供 `group`、`status`、`value`、`expected`、`detail`。

`overallOk` 只表示没有 `FAIL` 或 `WARN`。`UNKNOWN` 不会使其变为 false，因此 `overallOk=true` 不能证明硬件可信或覆盖完整。

### 7.4 Duck report key 与类型

key 区分大小写，值声明为 `Any?`，必须安全转换。

| Key | Domain 类型 | 重点字段 |
| --- | --- | --- |
| `bootloader` | `BootloaderReport` | `stage`、`state`、attestation/availability、findings、methods、error |
| `customRom` | `CustomRomReport` | `packageVisibility`、`detectedRoms`、各 finding、availability |
| `dangerousApps` | `DangerousAppsReport` | visibility、`findings`、`hiddenFromPackageManager`、`issues` |
| `deviceInfo` | `DeviceInfoReport` | `sections`、`totalCount`、`errorMessage` |
| `kernel` | `KernelCheckReport` | danger/info finding、CVE patch state、`nativeAvailable` |
| `lsposed` | `LSPosedReport` | availability、`signals`、`methods`、hit count |
| `memory` | `MemoryReport` | Hook/可执行内存标记、`findings`、`methods` |
| `mount` | `MountReport` | 可读性、early preload、findings、impacts |
| `nativeRoot` | `NativeRootReport` | Root 家族标记、可用性/计数、findings、methods |
| `playIntegrityFix` | `PlayIntegrityFixReport` | property/consistency/native signal 与可用性 |
| `selinux` | `SelinuxReport` | `mode`、`paradoxDetected`、methods、policy/audit analysis |
| `su` | `SuReport` | binary、daemon、进程上下文、methods |
| `systemProperties` | `SystemPropertiesReport` | danger/info signal、来源计数、property-area 可用性 |
| `tee` | `TeeReport` | `stage`、`verdict`、`tier`、score、signals、network/failure state |
| `virtualization` | `VirtualizationReport` | 进程/native/ASM 可用性、计数、signals、impacts |
| `zygisk` | `ZygiskReport` | 可用性、strong/heuristic hit、signals、methods |

具体类位于 `com.eltavine.duckdetector.features.<feature>.domain`。

示例：

```kotlin
import com.eltavine.duckdetector.features.tee.domain.TeeReport
import com.eltavine.duckdetector.features.tee.domain.TeeScanStage

val tee = result.duckReports["tee"] as? TeeReport

val teeSummary = when {
    tee == null -> "TEE report 不可用"
    tee.stage != TeeScanStage.READY -> "TEE 失败：${tee.failureMessage}"
    else -> "TEE verdict=${tee.verdict}, tier=${tee.tier}, signals=${tee.signals.size}"
}
```

`READY` 只表示聚合完成，不表示结果干净或覆盖完整。READY report 仍可能含 `errorMessage`、fallback、受限可见性、不可读文件或不可用 native 方法。应联合检查 stage、错误文本、availability/readability/visibility 与 method outcome。

不要直接序列化 `Any?` 形式的 `duckReports`。请定义宿主 DTO，只选择已接受兼容性和隐私风险的字段。

## 8. 失败模型

许多 Duck repository 会把自身异常转换为 `FAILED` stage 或错误字段；未捕获的 root/hardware/child 异常仍可能逃逸 `CRoootSdk.scan`，取消同级协程并使整次调用失败。

宿主边界示例：

```kotlin
try {
    consume(sdk.scan(options))
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (failure: Throwable) {
    recordScanFailure(failure)
}
```

不要吞掉 `CancellationException`；ViewModel 示例还单独处理了超时。

必须区分：

- 调用级异常或超时；
- report 级 `FAILED`；
- `UNKNOWN`、`SUPPORT`、受限、不可读、不可用；
- 所有受支持方法均已完成且真正无异常的情况。

## 9. Java 应用

`scan` 是 Kotlin suspend 函数，`0.1.0` 没有 Java-friendly callback/future overload。不要直接调用生成的 `Continuation` 签名，应增加 Kotlin 桥接层：

```kotlin
import android.content.Context
import com.chloemlla.crooot.CRoootScanOptions
import com.chloemlla.crooot.CRoootScanResult
import com.chloemlla.crooot.CRoootSdk
import java.io.Closeable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class CRoootJavaBridge(context: Context) : Closeable {
    interface Callback {
        fun onSuccess(result: CRoootScanResult)
        fun onFailure(error: Throwable)
    }

    private val sdk = CRoootSdk.create(context)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    fun scan(options: CRoootScanOptions, callback: Callback) {
        scope.launch {
            try {
                val result = sdk.scan(options)
                withContext(Dispatchers.Main.immediate) { callback.onSuccess(result) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                withContext(Dispatchers.Main.immediate) { callback.onFailure(failure) }
            }
        }
    }

    override fun close() {
        scope.cancel()
    }
}
```

桥接层仍必须串行调用，并遵守全部副作用限制。

## 10. R8、体积与重复类

AAR 内置 consumer rules，会保留：

```text
com.chloemlla.crooot.CRoootSdk
com.eltavine.duckdetector.**
com.juanma0511.rootdetector.**
```

通常无需额外 keep 规则，但保守规则也意味着大部分 SDK 字节码不能被删除或混淆。`includeHardware` 与 `includeDuckFeatures` 只改变运行工作量，不会明显减少打包体积。

不要同时打包原始 Duck/KKND artifact 或复制源码。CRooot AAR 已包含这些命名空间、Service、native symbol 和资源，否则可能产生 class、Manifest 或 JNI 冲突。

## 11. 隐私与数据处理

稳定的全新安装路径不会执行远程 HTTPS 刷新，但扫描会向宿主进程暴露敏感诊断信息，例如：

- 已安装/可见包名；
- 系统属性、Build 字段、挂载、路径、进程与 Service 名称；
- Android Keystore 与 attestation 证书详情；
- 设备与运行环境信息；
- native 与 shell 探针生成的证据字符串。

应最小化采集和留存，写日志或遥测前脱敏，取得所需用户/管理员同意。不能因为 SDK 本身 local-first 就默认上传原始报告。

Scene 显式广播与回环连接属于本地交互，但仍是可观察的主动行为，必须进入隐私与安全评审。

## 12. 测试与上线清单

生产上线前：

- 检查最终合并 Manifest 与权限；
- 确认宿主不使用 Soter，否则关闭全部 Duck features；
- 搜索宿主是否使用三个 KKND 保留 alias；
- 验证单扫描互斥；
- 测试超时、取消、进程死亡与清理；
- 分别测试有/无包可见性；
- 测试离线与回环权限行为；
- 不把 restricted/unavailable 当 clean；
- 覆盖代表性的 OEM、Android 版本、原生/修改设备；
- 覆盖所有发布 ABI，特别是非 arm64 的汇编覆盖差异；
- 评估 CPU、I/O、电量、进程和 Keystore 影响；
- 向 SOC/EDR 团队说明广播、文件、Socket、Hook 和进程行为。

当前仓库 CI 仅运行 `:sdk:lintRelease :sdk:assembleRelease`，只能证明静态构建/打包准备度。仓库没有 SDK unit/instrumentation 测试树，也没有已发布的设备矩阵准确率结果。

## 13. 升级策略

- 固定精确 SDK 版本。
- 升级前重新阅读两种语言指南并检查源码/API diff。
- 比较最终合并 Manifest 与 consumer rules。
- 重新验证副作用、固定 alias、Soter 行为与权限。
- 持续安全转换，并容忍未知 Duck key。
- 即使 Kotlin 当前暴露实现包类，也应视为不稳定 API。
- 重新执行设备/OEM/ABI 上线矩阵。

## 14. 常见问题与排错

### GitHub Packages 返回 `401` 或 `403`

使用具备 `read:packages` 的 classic PAT，确认用户名与 token 所属用户一致，并把凭据放在用户 Gradle properties 或 CI secret 中。检查 Package 是否允许 CI 仓库访问。

### 无法解析 `com.github.Tencent.soter:soter-wrapper`

添加上文经过过滤的 JitPack 仓库。Library 声明的 Maven 仓库不会自动传递给 consumer。

### AAR 要求 compile SDK 36

将宿主 `compileSdk` 调整到 36 或更高。只修改 `targetSdk` 不能满足 `minCompileSdk`。

### Duck 或 KKND 包出现 duplicate class

删除原始 detector 依赖或复制源码，只保留 CRooot AAR/源码构建。

### Duck `tee` 因 biometric/security 错误进入 `FAILED`

声明 `android.permission.USE_BIOMETRIC`，重新构建并检查最终 Manifest。该权限为普通权限，不会出现运行时授权弹窗。

### Package visibility 显示 `RESTRICTED`

按覆盖降低处理。优先评估精确 `<queries>`；仅在政策允许时添加 `QUERY_ALL_PACKAGES`。报告值本身不能证明权限状态。

### 扫描很慢或超时

TEE-heavy 设备上属于预期行为。串行扫描、移出交互关键路径、测量合适超时，并记住超时不会立即终止阻塞工作。`includeHardware=false` 仍会运行 Duck `tee`，只有 `includeDuckFeatures=false` 会跳过。

### Native 或 ASM 不可用

检查设备 ABI 和报告可用性字段。AAR 中存在 `.so` 不代表所有 ABI 汇编能力等价；还要确认没有错误地只集成 `classes.jar`。

### 找不到 GitHub Release

`v0.1.0` 只发布了 GitHub Packages 和 Actions artifact。请使用 Maven 坐标或从源码构建。

### 宿主使用 Tencent Soter

`0.1.0` 不得运行 Duck features。修复破坏性重试路径前必须设置 `includeDuckFeatures=false`。

## 15. 许可证与再分发

产物包含分别适用 Apache-2.0 与 MIT 的组件。已发布 `0.1.0` POM 只列出 Apache-2.0，且实际 AAR 未嵌入 `LICENSE`、`NOTICE` 或保留的 MIT 许可证。

再分发 AAR、源码或衍生产品时必须附带：

- [`../LICENSE`](../LICENSE)
- [`../NOTICE`](../NOTICE)
- [`../legacy/kknd-root-detector/LICENSE-MIT`](../legacy/kknd-root-detector/LICENSE-MIT)

CI 私钥标记检查只覆盖选定源码扩展，不是仓库级 secret scanning。再分发方必须自行执行完整的 secret 与 artifact 扫描。

## 16. 参考资料

- [GitHub Packages：Apache Maven registry](https://docs.github.com/en/packages/working-with-a-github-packages-registry/working-with-the-apache-maven-registry)
- [Android 包可见性声明](https://developer.android.com/training/package-visibility/declaring)
- [`CRoootSdk.kt`](../sdk/src/main/java/com/chloemlla/crooot/CRoootSdk.kt)
- [`sdk/build.gradle.kts`](../sdk/build.gradle.kts)
- [`sdk/src/main/AndroidManifest.xml`](../sdk/src/main/AndroidManifest.xml)
- [`sdk/consumer-rules.pro`](../sdk/consumer-rules.pro)
