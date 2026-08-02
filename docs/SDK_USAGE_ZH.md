# CRooot Android SDK 第三方接入指南

[English](SDK_USAGE.md) · [文档索引](README.md) · [项目 README](../README_ZH.MD)

本文面向接入 CRooot `0.1.0` 的第三方 Android 应用，依据真实源码与已发布 AAR 说明稳定门面、安装方式、结果模型、运行副作用、已知限制和生产上线要求。

> **`0.1.0` Soter 说明：** Duck TEE 的 Soter 原始重试路径可能删除或替换宿主已有的 Tencent Soter App Global Secure Key。本 SDK **已修复该重试路径**：探针不再删除宿主已有的密钥。但探针仍会初始化 Soter Treble 服务并执行生物识别检查。使用 Soter 的宿主应在代表性设备上验证后用于生产环境。可设置 `includeTee=false` 仅关闭 TEE 报告，同时保持其他 Duck 报告启用。

---

## 目录

- [0. 快速入门](#0-快速入门)
- [1. 架构概览](#1-架构概览)
- [2. 支持的 API 与兼容性](#2-支持的-api-与兼容性)
- [3. 选择接入方式](#3-选择接入方式)
- [4. 宿主 Manifest 配置](#4-宿主-manifest-配置)
- [5. 运行副作用与生产安全](#5-运行副作用与生产安全)
- [6. Kotlin 基础调用](#6-kotlin-基础调用)
- [7. 生命周期、串行化、超时与 UI 状态](#7-生命周期串行化超时与-ui-状态)
- [8. 结果模型](#8-结果模型)
- [8.5 读取 Duck 报告——示例](#85-读取-duck-报告示例)
- [8.6 面向第三方应用的稳定本地报告](#86-面向第三方应用的稳定本地报告)
- [9. 安全决策框架](#9-安全决策框架)
- [10. 失败模型](#10-失败模型)
- [11. Java 应用](#11-java-应用)
- [12. 性能与基准测试](#12-性能与基准测试)
- [13. 迁移指南](#13-迁移指南)
- [14. CI/CD 集成](#14-cicd-集成)
- [15. R8、体积与重复类](#15-r8体积与重复类)
- [16. 隐私与数据处理](#16-隐私与数据处理)
- [17. 测试与上线清单](#17-测试与上线清单)
- [18. 升级策略](#18-升级策略)
- [19. 常见问题与排错](#19-常见问题与排错)
- [20. 许可证与再分发](#20-许可证与再分发)
- [21. 发布说明](#21-发布说明)
- [22. 参考资料](#22-参考资料)

---

## 0. 快速入门

五分钟内让 CRooot 在你的 Android 应用中跑起来。

### 0.1 添加依赖

```kotlin
// settings.gradle.kts — 添加仓库
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
                username = providers.gradleProperty("gpr.user").orNull
                password = providers.gradleProperty("gpr.key").orNull
            }
        }
    }
}

// app/build.gradle.kts — 添加依赖
dependencies {
    implementation("com.chloemlla.crooot:crooot-sdk:0.1.0")
}
```

### 0.2 添加生物识别权限

```xml
<!-- AndroidManifest.xml -->
<uses-permission android:name="android.permission.USE_BIOMETRIC" />
```

### 0.3 运行扫描

```kotlin
import com.chloemlla.crooot.CRoootScanOptions
import com.chloemlla.crooot.CRoootSdk

val sdk = CRoootSdk.create(applicationContext)

suspend fun checkDeviceSecurity(): String {
    val result = sdk.scan(CRoootScanOptions())
    return result.summary()
    // 例如 "CRooot scan completed in 3200ms; duckReports=16/16"
}
```

### 0.4 稳定本地报告（第三方应用推荐）

```kotlin
import com.chloemlla.crooot.CRoootReportExporter
import com.chloemlla.crooot.CRoootReportOptions
import com.chloemlla.crooot.CRoootScanProfile

suspend fun createLocalReport() {
    val report = sdk.scanReport(
        CRoootReportOptions(profile = CRoootScanProfile.FULL),
    )
    val json = CRoootReportExporter.toJson(report)
    val text = CRoootReportExporter.toText(report)
    // 只有取得宿主应用明确同意后才保存或分享。
}
```

`scanReport()` 是面向第三方的稳定边界，返回统一的检测器摘要和发现项，默认脱敏，且不会
隐式上传或持久化数据。已有接入仍可继续使用旧的 `scan()` API。

### 0.5 解读结果

```kotlin
val result = sdk.scan(CRoootScanOptions())

if (result.isRooted) {
    // 发现 HIGH 严重度 Root 指示 — 采取相应措施
}
if (result.isSuspicious) {
    // 发现至少一个低严重度检测 — 进一步调查
}
val teeReport = result.duckReports["tee"] // TEE 证明证据
```

### 0.5 Soter 安全（如使用 Tencent Soter）

```kotlin
CRoootScanOptions(includeTee = false) // 只关闭 TEE 报告；其他 15 个 Duck 报告保持启用
```

---

## 1. 架构概览

CRooot 将两套开源 Android 安全检测引擎整合到同一个基于协程的 facade 背后：

```
┌─────────────────────────────────────────────────────────────────┐
│                      CRoootSdk (公开 API)                        │
│  com.chloemlla.crooot.CRoootSdk                                  │
├──────────────────────┬──────────────────────────────────────────┤
│    KKND 引擎         │            Duck 引擎（16 个功能）          │
│  com.juanma0511.*    │  com.eltavine.duckdetector.features.*    │
│                      │                                           │
│  ┌───────────────┐   │  ┌───┐ ┌───┐ ┌───┐ ┌───┐ ┌───┐ ┌───┐    │
│  │ RootDetector  │   │  │ B │ │ C │ │ D │ │ K │ │ L │ │ M │    │
│  │ (70+ 项检查)  │   │  │ L │ │ R │ │ A │ │ E │ │ S │ │ M │    │
│  ├───────────────┤   │  │   │ │   │ │   │ │   │ │   │ │   │    │
│  │ HwSecurity    │   │  └───┘ └───┘ └───┘ └───┘ └───┘ └───┘    │
│  │ Detector      │   │  ┌───┐ ┌───┐ ┌───┐ ┌───┐ ┌───┐ ┌───┐    │
│  │ (12 项硬件    │   │  │ N │ │ P │ │ S │ │ S │ │ T │ │ V │    │
│  │  检查)        │   │  │ R │ │ I │ │ E │ │ U │ │ E │ │ I │    │
│  ├───────────────┤   │  │   │ │ F │ │ L │ │   │ │ E │ │ R │    │
│  │ Zygote/       │   │  └───┘ └───┘ └───┘ └───┘ └───┘ └───┘    │
│  │ DirtySepolicy │   │  ┌───┐ ┌───┐                             │
│  │ (app-zygote)  │   │  │ S │ │ Z │                             │
│  └───────────────┘   │  │ P │ │ Y │                             │
│                      │  │   │ │ G │                             │
│  共享原生库          │  └───┘ └───┘                             │
│  libchloemlla-crooot ├──────────────────────────────────────────┤
│  (arm64/arm/x86/x64) │  原生探针（C++20、JNI、汇编）              │
└──────────────────────┴──────────────────────────────────────────┘
```

### 1.1 KKND 引擎

KKND 引擎提供两个结果组：

- **Root 检测**（`RootDetector`）：约 70 项 Kotlin 检查，覆盖 su 二进制、Root 管理器（Magisk、KSU、APatch、SuperSU 等）、危险应用、Frida、模拟器检测、挂载点、SELinux 状态、内核 cmdline、Zygisk、Xposed 等。**始终运行。**
- **硬件检测**（`HwSecurityDetector`）：12 项检查，覆盖 TEE 可用性、Keystore 安全级别、StrongBox、验证启动、bootloader 锁、dm-verity、AVB、加密和密钥证明。通过 `includeHardware=true` 可选启用。

### 1.2 Duck 引擎

Duck 引擎提供 16 个功能报告（通过 `includeDuckFeatures=true` 可选）。每个功能是自包含模块：

- **Domain 层**：报告数据类（如 `TeeReport`、`SelinuxReport`）
- **Data 层**：repository、native bridge（JNI）、探针、规则/目录
- **Service 层**：部分功能在独立或隔离进程中运行

### 1.3 原生层

所有原生代码编译到单个 `libchloemlla-crooot.so` 中，JNI 符号保留原项目包名。可用 ABI：`arm64-v8a`、`armeabi-v7a`、`x86`、`x86_64`。汇编级覆盖因 ABI 而异（部分虚拟化陷阱仅 arm64 支持）。

### 1.4 关键设计决策

- **无 UI 依赖**：SDK 不要求 Compose、Activity 或任何 UI 框架
- **无需 Root**：所有检测均为启发式，在宿主应用权限内运行
- **离线优先**：TEE 吊销检查使用内置快照，默认不发起 HTTPS 请求
- **默认并发**：所有检测组通过协程并行运行
- **尽力清理**：临时文件、密钥和进程会被清理，但崩溃或强制停止可能留下痕迹

---

## 2. 支持的 API 与兼容性

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

---

## 3. 选择接入方式

### 3.1 GitHub Packages——推荐

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

### 3.2 本地 AAR

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

### 3.3 通过 included build 使用源码

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

---

## 4. 宿主 Manifest 配置

### 4.1 AAR 自动合并的组件

SDK Manifest 会合并以下非导出 Service：

| Service | 进程行为 | 用途 |
| --- | --- | --- |
| `ZygiskFdTrapDetectorService` | 独立 `:zygisk_fd_detector` 进程 | 检测 Zygisk FD 陷阱 |
| `VirtualizationProbeService` | 独立 `:virtualization_probe` 进程 | 检测虚拟化环境 |
| `VirtualizationIsolatedProbeService` | 隔离进程 | 辅助虚拟化检测 |
| `SelinuxContextValidityCarrierService` | 隔离进程（app-zygote） | SELinux 上下文有效性 Oracle |
| `TeeGrantDomainGranteeService` | 隔离进程 | TEE grant-domain 探针 |

请检查宿主 APK 的最终合并 Manifest。除非已经接受并验证覆盖损失，否则不要移除这些 Service。

### 4.2 权限

AAR 不声明任何 `uses-permission`，所有权限均由宿主决定。

**完整 TEE 覆盖所需：**

```xml
<uses-permission android:name="android.permission.USE_BIOMETRIC" />
```

在具备生物识别硬件的设备上，缺少该权限可能使 `BiometricManager.canAuthenticate()` 抛出异常，导致整个 Duck `tee` 报告进入 `FAILED`。

**其他可选权限：**

```xml
<!-- 旧版/Soter 兼容路径。 -->
<uses-permission android:name="android.permission.USE_FINGERPRINT" />

<!-- Scene/Frida 本机回环探针；实现层在线刷新也需要。 -->
<uses-permission android:name="android.permission.INTERNET" />

<!-- 实现层在线 TEE 刷新的网络状态预检。 -->
<uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />
```

**权限依赖映射：**

```
USE_BIOMETRIC
  └─ TEE 深度检查（生物识别探针必需）
USE_FINGERPRINT
  └─ Soter 旧版路径（可选）
INTERNET
  ├─ Scene/Frida 回环探针
  └─ TEE CRL 在线刷新（默认离线）
ACCESS_NETWORK_STATE
  └─ TEE CRL 网络状态预检（可选）
QUERY_ALL_PACKAGES
  └─ 应用清单检查（Custom ROM、危险应用、LSPosed、虚拟化）
```

全新安装时，受支持 facade 使用内置 TEE 吊销快照，不会发起远程 HTTPS 请求。实现包中的 consent store 技术上可被访问，且持久化状态可能开启对 `https://android.googleapis.com/attestation/status` 的请求；该路径不属于 `0.1.0` 稳定的 `com.chloemlla.crooot` facade。

原应用使用过以下可选 Soter 包可见性声明，但 SDK Manifest 未包含：

```xml
<queries>
    <package android:name="com.tencent.soter.soterserver" />
</queries>
```

### 4.3 包可见性

`QUERY_ALL_PACKAGES` 可提高应用清单覆盖度，但受分发平台政策约束：

```xml
<uses-permission android:name="android.permission.QUERY_ALL_PACKAGES" />
```

只有宿主符合分发平台政策时才应添加。缺少该权限时，Custom ROM、危险应用、LSPosed 和虚拟化中的包检测可能降级。

报告中的 `FULL`/`RESTRICTED` 是启发式结果：实现仅根据可见应用数量推断，不能证明 `QUERY_ALL_PACKAGES` 是否真正授予。

### 4.4 `0.1.0` 的 Manifest 覆盖情况

本 SDK **现已包含**原 `0.1.0` 缺失的 app-zygote 预加载接线：

- ✅ `android:zygotePreloadName="...AppZygotePreload"` — 已在 SDK Manifest 中声明
- ✅ `android:useAppZygote="true"` — 已设置在 `SelinuxContextValidityCarrierService` 上

仍然存在的覆盖缺口：

- KKND `DirtySepolicyService` 未在 SDK Manifest 中声明（宿主可按需手动添加）
- 不包含 `NativeActivity` early-preload launcher（不适用于第三方应用）
- 根据宿主应用的 Manifest，高级 SELinux app-zygote 检查和 early mount/virtualization preload 信号可能仍报告覆盖降低

不要把原 NativeActivity launcher 直接复制到第三方应用；其 native handoff 假定 `${applicationId}.MainActivity`，不是通用宿主契约。

---

## 5. 运行副作用与生产安全

完整扫描在本地执行，但不是纯被动读取。本节记录宿主必须了解的每一个副作用。

### 5.1 Soter 严重状态变更

`0.1.0` 的 Soter 重试路径**已修复**，不再对预先存在的密钥调用 `removeAppGlobalSecureKey()`。但探针仍会初始化 Soter Treble 服务并执行生物识别检查。

使用 Tencent Soter 的宿主可以配置：

```kotlin
CRoootScanOptions(includeTee = false)
```

`includeTee` 逐功能开关可以只禁用 Duck `tee` 报告，同时保留其他 15 个 Duck 报告。请在代表性设备上验证后再用于生产环境。

### 5.2 Android Keystore 写操作

硬件与 Duck TEE 扫描会生成、导入、授权、更新、使用和删除大量 AndroidKeyStore 测试条目。Duck 大多数 alias 含时间值，但进程死亡或厂商异常可能留下临时条目。

**保留 alias（宿主绝不能使用）：**

```text
rootdetector_tee_probe
rootdetector_ks_backing
rootdetector_sb_key
```

operation-pruning 探针会同时打开 18 个 ECDSA 签名操作，可能使宿主并发的 Keystore 加解密/签名会话失效或被裁剪。其他 TEE 探针还会临时向隔离进程授权测试密钥访问，并尝试撤销。

### 5.3 进程级 Hook

TEE 深检会临时替换当前进程 `ServiceManager` 中的 Keystore Binder cache 以捕获返回值。代码会尝试恢复，但宿主同期发起的 Keystore 调用可能经过探针 Hook。

探针还会调用 `HiddenApiBypass.addHiddenApiExemptions("")`。隐藏 API exemption 是进程级状态，扫描结束时不会恢复。

### 5.4 文件、广播、Socket 与进程

危险应用检测可能：

- 向已知 Scene 组件发送显式命令型探针广播；
- 通过该交互短暂创建、检查并删除随机外部存储探针文件；
- 尝试在 `/dev` 下创建 mode 为 `0` 的 marker 目录，成功后删除；
- 启动 shell 命令并读取进程、挂载、属性和 Service 输出。

其他检查会连接 Scene/Frida 本机回环端口、绑定 SDK Service，并启动独立或隔离进程。这些行为可能出现在 EDR、审计、包管理、网络、文件系统和进程遥测中。

所有清理均为尽力而为；崩溃、force-stop、超时或进程终止可能中断清理。

### 5.5 性能与取消

facade 会并发启动 KKND root、可选 KKND hardware 与全部 16 个 Duck repository；Duck TEE 内部还会展开大量 Keystore 探针。时序侧信道路径约执行 500 对 Binder/Keystore 采样，pruning 路径占用 18 个操作槽。

`0.1.0` 不提供：

- 内置总超时；
- 进度 API；
- 扫描互斥；
- dispatcher/executor 注入；
- 对阻塞 JNI、进程、Socket、Binder、Keystore 工作的即时抢占取消。

SDK 共享使用 `Dispatchers.Default` 与 `Dispatchers.IO`，可能影响宿主同进程协程吞吐。调用方取消和 `withTimeout` 只能限制宿主等待时间，底层阻塞操作仍可能短暂继续；部分 `runCatching`/`catch(Throwable)` 路径也可能延迟取消传播。

### 5.6 宿主必须遵守的运行规则

- 整个进程同一时刻只允许一次扫描。
- 不要在冷启动、登录、支付、签名、密钥轮换或任何宿主 Keystore 操作期间扫描。
- 优先采用用户/管理员明确触发或受控后台维护窗口。
- 上线前与宿主安全/EDR 团队确认主动探针行为。
- 缓存宿主派生判定，不要高频重复扫描。
- 测试进程死亡，并检查残留 alias/文件。
- 使用 Soter 的宿主在验证前应设置 `includeTee=false`。

---

## 6. Kotlin 基础调用

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
            includeDuckFeatures = true,
            includeTee = true, // 使用 Soter 的宿主可设为 false。
        ),
    )
}
```

选项语义：

| 选项 | 行为 |
| --- | --- |
| `includeHardware=true` | 增加 `kkndHardware`，不控制 Duck `tee` |
| `includeHardware=false` | 只跳过 KKND 硬件检查 |
| `includeDuckFeatures=true` | 主开关：按逐功能标志运行 Duck repository |
| `includeDuckFeatures=false` | 返回空 `duckReports` map（覆盖所有逐功能标志） |
| `includeTee=true` | 运行 Duck `tee` 功能（使用 Soter 的宿主可设为 `false`） |
| `includeSelinux=true` | 运行 Duck `selinux` 功能 |
| `includeVirtualization=true` | 运行 Duck `virtualization` 功能 |
| *（另外 11 个逐功能标志）* | 每个 Duck 功能对应 `include<Feature>` 标志，全部默认为 `true` |

KKND root 检查**始终运行**——无法禁用。

### 完整功能标志列表

```kotlin
CRoootScanOptions(
    includeDuckFeatures = true,    // 主开关
    includeBootloader = true,      // Bootloader 锁定状态
    includeCustomRom = true,       // 自定义 ROM 检测
    includeDangerousApps = true,   // 危险应用清单
    includeDeviceInfo = true,      // 设备信息
    includeKernel = true,          // 内核安全检测
    includeLsposed = true,         // LSPosed 框架检测
    includeMemory = true,          // 内存篡改检测
    includeMount = true,           // 挂载点完整性
    includeNativeRoot = true,      // 原生 Root 检测
    includePlayIntegrityFix = true,// Play Integrity 绕过检测
    includeSelinux = true,         // SELinux 状态分析
    includeSu = true,              // SU 二进制检测
    includeSystemProperties = true,// 系统属性分析
    includeTee = true,             // TEE/Keystore 证明
    includeVirtualization = true,  // 虚拟化环境检测
    includeZygisk = true,          // Zygisk 检测
)
```

---

## 7. 生命周期、串行化、超时与 UI 状态

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

---

## 8. 结果模型

### 8.1 `CRoootScanResult`

| 字段 | 含义 | 始终存在？ |
| --- | --- | --- |
| `kkndRoot` | KKND root/integrity 证据 | ✅ 始终 |
| `kkndHardware` | KKND hardware/TEE/verified-boot 证据 | ❌ 仅当 `includeHardware=true` |
| `duckReports` | 固定 key 的 Duck domain report | ✅ 始终（关闭时为空） |
| `durationMs` | 整体墙钟差值 | ✅ 始终 |
| `isRooted` | 是否有 HIGH 严重度 Root 指示 | ✅ 便捷属性 |
| `isSuspicious` | 是否有任意严重度检测项 | ✅ 便捷属性 |
| `summary()` | 诊断日志用可读字符串 | ✅ 便捷方法 |

`durationMs` 仅适合诊断，不是单调时钟基准，系统时钟变化可能影响结果。`kkndRoot.scanDurationMs` 与 `kkndHardware.scanDurationMs` 也从同一整体开始时间计算到各自 await 点，并非各引擎独立耗时。

### 8.2 KKND Root 结果

`kkndRoot.items` 包含 `DetectionItem`：

```kotlin
data class DetectionItem(
    val id: String,
    val name: String,
    val description: String,
    val category: DetectionCategory,
    val severity: Severity,     // HIGH 或 WARNING
    val detected: Boolean,
    val detail: String?,
)
```

摘要语义：

- `isRooted`：至少一个 `detected && severity == HIGH`；
- `isSuspicious`：任意严重度存在 detected item；
- `detectedCount`、`highRiskCount`、`warningCount`：便捷计数。

**读取摘要布尔值后仍应保留并展示单项证据。** `detail` 字段包含触发检测的证据路径、属性或信号。

### 8.3 KKND 硬件结果

`kkndHardware?.items` 包含 `HwCheckItem`，提供 `group`、`status`、`value`、`expected`、`detail`。

`overallOk` 只表示没有 `FAIL` 或 `WARN`。`UNKNOWN` 不会使其变为 false，因此 `overallOk=true` 不能证明硬件可信或覆盖完整。

### 8.4 Duck report key 与类型

key 区分大小写，值声明为 `Any?`，必须安全转换。

| Key | Domain 类型 | 检测内容 | 重点字段 |
| --- | --- | --- | --- |
| `bootloader` | `BootloaderReport` | Bootloader 解锁状态 | `stage`、`state`、attestation/availability、findings、methods、error |
| `customRom` | `CustomRomReport` | 自定义/非官方 ROM | `packageVisibility`、`detectedRoms`、各 finding、availability |
| `dangerousApps` | `DangerousAppsReport` | Root 管理器、修改工具、可疑应用 | visibility、`findings`、`hiddenFromPackageManager`、`issues` |
| `deviceInfo` | `DeviceInfoReport` | 通用设备信息 | `sections`、`totalCount`、`errorMessage` |
| `kernel` | `KernelCheckReport` | 内核完整性、CVE 补丁状态 | danger/info finding、CVE patch state、`nativeAvailable` |
| `lsposed` | `LSPosedReport` | LSPosed 框架痕迹 | availability、`signals`、`methods`、hit count |
| `memory` | `MemoryReport` | 运行时 Hook、可执行内存 | Hook/可执行内存标记、`findings`、`methods` |
| `mount` | `MountReport` | 挂载点篡改 | 可读性、early preload、findings、impacts |
| `nativeRoot` | `NativeRootReport` | KernelSU、APatch、SUSFS 等 | Root 家族标记、可用性/计数、findings、methods |
| `playIntegrityFix` | `PlayIntegrityFixReport` | Play Integrity 绕过模块 | property/consistency/native signal 与可用性 |
| `selinux` | `SelinuxReport` | SELinux 模式、脏策略、审计 | `mode`、`paradoxDetected`、methods、policy/audit analysis |
| `su` | `SuReport` | SU 二进制、守护进程、进程上下文 | binary、daemon、进程上下文、methods |
| `systemProperties` | `SystemPropertiesReport` | 篡改的系统属性 | danger/info signal、来源计数、property-area 可用性 |
| `tee` | `TeeReport` | TEE 证明、Keystore、StrongBox、Soter | `stage`、`verdict`、`tier`、score、signals、network/failure state |
| `virtualization` | `VirtualizationReport` | 模拟器、VM、AVF、WSA | 进程/native/ASM 可用性、计数、signals、impacts |
| `zygisk` | `ZygiskReport` | Zygisk、模块、FD 陷阱 | 可用性、strong/heuristic hit、signals、methods |

具体类位于 `com.eltavine.duckdetector.features.<feature>.domain`。

### 8.5 读取 Duck 报告——示例

```kotlin
import com.eltavine.duckdetector.features.tee.domain.TeeReport
import com.eltavine.duckdetector.features.tee.domain.TeeScanStage
import com.eltavine.duckdetector.features.selinux.domain.SelinuxReport
import com.eltavine.duckdetector.features.nativeroot.domain.NativeRootReport
import com.eltavine.duckdetector.features.bootloader.domain.BootloaderReport

// TEE 证明
val tee = result.duckReports["tee"] as? TeeReport
val teeSummary = when {
    tee == null -> "TEE 报告不可用"
    tee.stage != TeeScanStage.READY -> "TEE 失败：${tee.failureMessage}"
    else -> "TEE verdict=${tee.verdict}, tier=${tee.tier}, signals=${tee.signals.size}"
}

// SELinux 状态
val selinux = result.duckReports["selinux"] as? SelinuxReport
val selinuxMode = selinux?.mode  // ENFORCING、PERMISSIVE 或 null
val dirtyPolicyDetected = selinux?.paradoxDetected

// 原生 Root（KernelSU、APatch 等）
val nativeRoot = result.duckReports["nativeRoot"] as? NativeRootReport
val ksuDetected = nativeRoot?.findings?.any { it.contains("KernelSU", ignoreCase = true) }

// Bootloader
val bootloader = result.duckReports["bootloader"] as? BootloaderReport
val bootloaderUnlocked = bootloader?.state?.contains("unlocked", ignoreCase = true)
```

`READY` 只表示聚合完成，不表示结果干净或覆盖完整。READY report 仍可能含 `errorMessage`、fallback、受限可见性、不可读文件或不可用 native 方法。应联合检查 stage、错误文本、availability/readability/visibility 与 method outcome。

**不要直接序列化 `Any?` 形式的 `duckReports`。** 请定义宿主 DTO，只选择已接受兼容性和隐私风险的字段。

---

## 8.6 面向第三方应用的稳定本地报告

第三方应用需要生成本地报告时，应优先使用 `scanReport()`。它会把旧版
`CRoootScanResult` 转换为稳定的宿主侧模型，不会把 Duck 的 `Any?` map 暴露给调用方：

```kotlin
val report = CRoootSdk.create(applicationContext).scanReport(
    CRoootReportOptions(
        profile = CRoootScanProfile.FULL,
        includeSensitiveEvidence = false,
    ),
)

val text = CRoootReportExporter.toText(report)
val json = CRoootReportExporter.toJson(report)
val html = CRoootReportExporter.toHtml(report)
```

已有的 `scan(CRoootScanOptions)` API 保持不变。这是一个追加的兼容层，现有调用方可以继续
使用旧结果模型，并按需迁移到本地报告。

`CRoootLocalReport` 包含：

- `overallStatus`、`rooted`、`suspicious`，且不会把 `UNKNOWN` 或 `NOT_RUN` 当作通过；
- KKND Root、KKND 硬件以及 16 个 Duck key 的稳定 `detectorSummaries`；
- 含状态、严重度、置信度、来源、建议和隐私标签证据的统一 `findings`；
- schema 版本、SDK 版本、报告 ID、扫描档位、时间戳、耗时和设备 ABI/API 信息；
- 解释覆盖缺口、受限检查及启发式边界的 `limitations`。

`CRoootScanProfile` 提供 `QUICK`、`STANDARD`、`FULL` 和 `PRIVACY_MINIMAL`。如果宿主需要精确的
逐项检测开关，可以通过 `CRoootReportOptions.scanOptions` 传入原有 `CRoootScanOptions`。
默认导出器只处理稳定 DTO，不会序列化 `duckReports`，也不会联网或隐式持久化。加密存储、
保留时间、分享和上传授权由宿主应用负责。

证据默认脱敏。只有在用户明确进行本地排障时，才应设置
`includeSensitiveEvidence=true`。即使如此，本报告仍然只是启发式证据，不能作为账号封禁、
删除数据或其他不可逆设备决策的唯一依据。

可选进度回调会发送 `Started`、`Completed` 或 `Failed`。取消和异常仍会传播给调用方，
不会吞掉 `CancellationException`。

### 8.7 报告 schema 与兼容性约定

`CRoootLocalReport.schemaVersion` 从 `1` 开始。调用方必须忽略未知字段和未来新增的枚举值，
也不应依赖检测器顺序。在同一个 schema 版本内，Finding ID 与 detector ID 保持稳定；新增字段和
新增 detector ID 采用追加兼容。`CRoootReportOptions.scanOptions` 可能让实际执行项少于档位默认值，
因此调用方应以每个 detector 的 `status` 和 `executed` 为准，而不能只看 profile 名称。

省略语义明确区分：

- `PASS`：选中的检测器执行完成，支持的检查没有发现信号；
- `INFO`：执行完成，但只有信息性证据；
- `WARN` / `FAIL`：发现需要关注的信号；
- `UNKNOWN`：检测器执行了，但无法确定结果；
- `NOT_RUN`：选项没有选择该检测器；
- `ERROR`：检测器返回错误或失败状态。

`includeSensitiveEvidence` 只是诊断详情开关，不是绕过 Secret 脱敏的开关。强制 Secret 脱敏始终
生效。宿主应把 `privacy` 标签视为元数据，在持久化或传输前执行自己的最终分享策略。

---

## 9. 安全决策框架

CRooot 返回**启发式证据**，不是最终证明。本节提供将证据转化为决策的框架。

### 9.1 决策层级

```
Level 1: 调用失败
  └─ 异常或超时 → 不做任何安全决策；稍后重试
Level 2: 证据可用
  ├─ kkndRoot.isRooted == true → 高置信度：设备已 Root
  ├─ kkndRoot.isSuspicious == true → 中置信度：可疑状态
  ├─ kkndHardware?.overallOk == false → 硬件/TEE 完整性受损
  └─ duckReports[feature] → 检查各报告字段
Level 3: 证据不可用
  ├─ 报告为 FAILED、UNKNOWN 或 null
  ├─ 包可见性为 RESTRICTED
  ├─ Native/ASM 支持不可用
  └─ 不要解释为"干净"——视为"无法验证"
Level 4: 干净信号
  └─ 所有受支持方法完成且无发现 → 仍不是保证
```

### 9.2 推荐决策逻辑

```kotlin
fun assessDeviceSecurity(result: CRoootScanResult): SecurityLevel {
    // Level 1: 明确 Root 检测
    if (result.isRooted) return SecurityLevel.ROOTED

    // Level 2: 硬件受损
    if (result.kkndHardware?.overallOk == false) return SecurityLevel.SUSPICIOUS

    // Level 3: 可疑发现
    if (result.isSuspicious) return SecurityLevel.SUSPICIOUS

    // Level 4: 检查关键 Duck 报告
    val tee = result.duckReports["tee"] as? TeeReport
    if (tee?.verdict == TeeVerdict.FAILED) return SecurityLevel.SUSPICIOUS

    val selinux = result.duckReports["selinux"] as? SelinuxReport
    if (selinux?.paradoxDetected == true) return SecurityLevel.SUSPICIOUS

    // Level 5: 检查不可用覆盖
    val inaccessible = result.duckReports.any { (_, report) ->
        report == null || (report is TeeReport && report.stage == TeeScanStage.FAILED)
    }
    if (inaccessible) return SecurityLevel.UNVERIFIABLE

    // Level 6: 干净（带保留）
    return SecurityLevel.CLEAN
}

enum class SecurityLevel {
    ROOTED,        // 高置信度：设备已 Root
    SUSPICIOUS,    // 中置信度：存在指示器
    UNVERIFIABLE,  // 覆盖缺口阻止得出结论
    CLEAN,         // 未检测到指示器（不是保证）
}
```

### 9.3 重要注意事项

- **CLEAN 不等于 TRUSTED。** 干净结果表示未发现检测信号，不代表设备安全。高级 Root 工具可以隐藏自身。
- **UNKNOWN 不等于 CLEAN。** 不可用的探针、受限可见性或不受支持的 native 方法意味着检测器无法检查。不要将其视为通过。
- **组合多个信号。** 不要依赖单个布尔值或单个 Duck 报告。交叉验证 `kkndRoot`、`kkndHardware` 和相关 Duck 报告。
- **误报是可能的。** 某些 OEM 或自定义 ROM 可能触发检测器但并未被攻破。在目标设备上验证阈值。
- **不要作为不可逆操作的唯一依据。** 封号、删除数据或其他不可逆操作不应仅依赖 CRooot 结果。

---

## 10. 失败模型

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

---

## 11. Java 应用

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

---

## 12. 性能与基准测试

### 12.1 预期扫描时长

| 配置 | 典型时长 | 说明 |
| --- | --- | --- |
| `includeHardware=false, includeDuckFeatures=false` | 2–8 秒 | 仅 KKND root |
| `includeHardware=true, includeDuckFeatures=false` | 5–15 秒 | KKND root + 硬件 |
| `includeHardware=false, includeDuckFeatures=true` | 10–40 秒 | Duck 功能，不含 TEE 深检 |
| `includeHardware=true, includeDuckFeatures=true` | 15–60+ 秒 | 完整扫描（TEE 是瓶颈） |

### 12.2 性能调优

```kotlin
// 快速扫描（无 TEE、无硬件）
val fast = sdk.scan(CRoootScanOptions(
    includeHardware = false,
    includeTee = false,
))

// 均衡扫描（无 TEE 深检，但含基础硬件）
val balanced = sdk.scan(CRoootScanOptions(
    includeHardware = true,
    includeTee = false,
))

// 自定义扫描（只选择需要的功能）
val custom = sdk.scan(CRoootScanOptions(
    includeBootloader = true,
    includeSelinux = true,
    includeSu = true,
    includeTee = false,
    includeVirtualization = false,
    includeMemory = false,
    includeLsposed = false,
    includeZygisk = false,
    includeNativeRoot = false,
    includeCustomRom = false,
    includeDangerousApps = false,
    includeDeviceInfo = false,
    includeKernel = false,
    includeMount = false,
    includePlayIntegrityFix = false,
    includeSystemProperties = false,
))
```

### 12.3 基准测试方法

```kotlin
fun benchmark() {
    val sdk = CRoootSdk.create(context)
    val times = mutableListOf<Long>()

    repeat(5) {
        val start = System.nanoTime()
        sdk.scan(CRoootScanOptions(includeHardware = false, includeTee = false))
        times.add(System.nanoTime() - start)
    }

    val avgMs = times.map { it / 1_000_000 }.average()
    Log.d("CRooot", "平均扫描时间：${"%.0f".format(avgMs)}ms")
}
```

在代表性设备（不仅是模拟器）上运行基准测试，并测试所有已发布 ABI。

---

## 13. 迁移指南

### 13.1 从 Duck Detector 迁移

如果你之前使用过独立的 Duck Detector 应用/SDK：

1. **移除**原始 Duck Detector 依赖。
2. **添加** CRooot SDK（参见 [§3](#3-选择接入方式)）。
3. **替换** `com.eltavine.duckdetector.*` 入口为 `com.chloemlla.crooot.CRoootSdk`。
4. **更新**原生库引用：Duck 使用 `libduckdetector.so`，CRooot 使用 `libchloemlla-crooot.so`。
5. **验证**合并 Manifest：CRooot 的 AAR 包含相同的 5 个 Service。
6. **重新测试**所有 Duck 功能报告。Domain 报告类型完全相同。

### 13.2 从 KKND Root Detector 迁移

如果你之前使用过独立的 KKND Root Detector：

1. **移除**原始 KKND 依赖。
2. **添加** CRooot SDK（参见 [§3](#3-选择接入方式)）。
3. **通过** `result.kkndRoot` 和 `result.kkndHardware` 访问 KKND 结果，而不是直接调用 `RootDetector`/`HwSecurityDetector`。
4. **更新**原生库引用：如果之前使用 `libnative_root_checks.so`，CRooot 已将其合并到 `libchloemlla-crooot.so`。
5. **重新测试**目标设备上的 Root 检测。

### 13.3 同时使用两个原始检测器（不推荐）

```warning
不要同时打包原始 Duck/KKND artifact 或复制源码。
CRooot AAR 已包含这些命名空间、Service、native symbol 和资源，
否则可能产生 class、Manifest 或 JNI 冲突。
```

---

## 14. CI/CD 集成

### 14.1 GitHub Actions

```yaml
name: 安全扫描

on:
  schedule:
    - cron: '0 6 * * 1'  # 每周一
  workflow_dispatch:

jobs:
  scan:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - name: 使用 CRooot 构建
        run: ./gradlew :app:assembleDebug
      - name: 运行 CRooot SDK 测试
        run: ./gradlew :sdk:testReleaseUnitTest
```

### 14.2 CI 中的 Maven 认证

```yaml
- name: 构建
  env:
    GITHUB_ACTOR: ${{ github.actor }}
    GITHUB_TOKEN: ${{ secrets.GITHUB_TOKEN }}
  run: ./gradlew :app:assembleRelease
```

### 14.3 CI 中的本地 AAR

```bash
# 构建 AAR
./gradlew :sdk:assembleRelease

# 复制到宿主项目
cp sdk/build/outputs/aar/sdk-release.aar ../host-app/app/libs/crooot-sdk-0.1.0.aar

# 构建宿主
cd ../host-app && ./gradlew assembleRelease
```

---

## 15. R8、体积与重复类

AAR 内置 consumer rules，会保留：

```text
com.chloemlla.crooot.CRoootSdk
com.eltavine.duckdetector.**
com.juanma0511.rootdetector.**
```

通常无需额外 keep 规则，但保守规则也意味着大部分 SDK 字节码不能被删除或混淆。`includeHardware` 与 `includeDuckFeatures` 只改变运行工作量，不会明显减少打包体积。

不要同时打包原始 Duck/KKND artifact 或复制源码。CRooot AAR 已包含这些命名空间、Service、native symbol 和资源，否则可能产生 class、Manifest 或 JNI 冲突。

---

## 16. 隐私与数据处理

稳定的全新安装路径不会执行远程 HTTPS 刷新，但扫描会向宿主进程暴露敏感诊断信息，例如：

- 已安装/可见包名；
- 系统属性、Build 字段、挂载、路径、进程与 Service 名称；
- Android Keystore 与 attestation 证书详情；
- 设备与运行环境信息；
- native 与 shell 探针生成的证据字符串。

应最小化采集和留存，写日志或遥测前脱敏，取得所需用户/管理员同意。不能因为 SDK 本身 local-first 就默认上传原始报告。

Scene 显式广播与回环连接属于本地交互，但仍是可观察的主动行为，必须进入隐私与安全评审。

---

## 17. 测试与上线清单

生产上线前：

- 检查最终合并 Manifest 与权限；
- 确认宿主不使用 Soter，否则设置 `includeTee=false`；
- 搜索宿主是否使用三个 KKND 保留 alias；
- 验证单扫描互斥；
- 测试超时、取消、进程死亡与清理；
- 分别测试有/无包可见性；
- 测试离线与回环权限行为；
- 不把 restricted/unavailable 当 clean；
- 覆盖代表性的 OEM、Android 版本、原生/修改设备；
- 覆盖所有发布 ABI，特别是非 arm64 的汇编覆盖差异；
- 评估 CPU、I/O、电量、进程和 Keystore 影响；
- 向 SOC/EDR 团队说明广播、文件、Socket、Hook 和进程行为；
- 验证 `isRooted` 和 `isSuspicious` 便捷属性是否符合预期；
- 如果使用逐功能标志，验证每个启用的功能在 `duckReports` 中产生报告。

当前仓库 CI 仅运行 `:sdk:lintRelease :sdk:assembleRelease`，只能证明静态构建/打包准备度。仓库没有 SDK unit/instrumentation 测试树，也没有已发布的设备矩阵准确率结果。

---

## 18. 升级策略

- 固定精确 SDK 版本。
- 升级前重新阅读两种语言指南并检查源码/API diff。
- 比较最终合并 Manifest 与 consumer rules。
- 重新验证副作用、固定 alias、Soter 行为与权限。
- 持续安全转换，并容忍未知 Duck key。
- 即使 Kotlin 当前暴露实现包类，也应视为不稳定 API。
- 重新执行设备/OEM/ABI 上线矩阵。

---

## 19. 常见问题与排错

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

TEE-heavy 设备上属于预期行为。串行扫描、移出交互关键路径、测量合适超时，并记住超时不会立即终止阻塞工作。`includeHardware=false` 仍会运行 Duck `tee`，可使用 `includeTee=false` 跳过 TEE 报告，或 `includeDuckFeatures=false` 跳过所有 Duck 报告。

### Native 或 ASM 不可用

检查设备 ABI 和报告可用性字段。AAR 中存在 `.so` 不代表所有 ABI 汇编能力等价；还要确认没有错误地只集成 `classes.jar`。

### 找不到 GitHub Release

`v0.1.0` 只发布了 GitHub Packages 和 Actions artifact。请使用 Maven 坐标或从源码构建。

### 宿主使用 Tencent Soter

`0.1.0` 的 Soter 重试路径已修复，不再删除宿主已有的 App Global Secure Key。为安全起见，可设置 `includeTee=false` 仅关闭 Duck TEE 报告，同时保持其他 15 个 Duck 报告启用。请在代表性设备上验证后再用于生产环境。

### 排错决策树

```
扫描是否抛出异常？
├─ 是 → 是否为 CancellationException？
│   ├─ 是 → 重新抛出（不要吞掉）
│   └─ 否 → 检查网络、权限和设备状态
└─ 否 → 扫描返回结果但 kkndRoot 为空？
    ├─ 是 → 检查原生库是否正确加载
    └─ 否 → TEE 报告是否为 FAILED？
        ├─ 是 → 是否已声明 USE_BIOMETRIC？
        │   ├─ 是 → 在具备生物识别硬件的设备上测试
        │   └─ 否 → 在 Manifest 中添加 USE_BIOMETRIC
        └─ 否 → Duck 报告是否缺失？
            ├─ 是 → includeDuckFeatures 是否设为 true？
            └─ 否 → 检查各报告字段
```

---

## 20. 许可证与再分发

产物包含分别适用 Apache-2.0 与 MIT 的组件。已发布 `0.1.0` POM 只列出 Apache-2.0，且实际 AAR 未嵌入 `LICENSE`、`NOTICE` 或保留的 MIT 许可证。

再分发 AAR、源码或衍生产品时必须附带：

- [`../LICENSE`](../LICENSE)
- [`../NOTICE`](../NOTICE)
- [`../legacy/kknd-root-detector/LICENSE-MIT`](../legacy/kknd-root-detector/LICENSE-MIT)

CI 私钥标记检查只覆盖选定源码扩展，不是仓库级 secret scanning。再分发方必须自行执行完整的 secret 与 artifact 扫描。

---

## 21. 发布说明

### 0.1.0（当前版本）

**初始发布。**

- 整合 KKND Root Detector + Duck Detector 引擎
- 单一 `CRoootSdk` facade，提供 `suspend fun scan()`
- 16 个 Duck 功能报告，固定 key
- KKND root（始终）和硬件（可选）结果
- 逐功能 Duck 标志（`includeTee`、`includeSelinux` 等）
- `CRoootScanResult` 的 `isRooted`、`isSuspicious` 和 `summary()` 便捷属性
- Soter 重试路径修复（不再删除预先存在的 ASK）
- Manifest 中的 app-zygote 预加载接线
- 已发布到 GitHub Packages（`com.chloemlla.crooot:crooot-sdk:0.1.0`）
- 4 个 ABI：`arm64-v8a`、`armeabi-v7a`、`x86`、`x86_64`
- 最低 Android 10（API 29），compileSdk 36

**已知限制：**

- 最初无 Duck 报告逐功能选择器（已通过 `include<Feature>` 标志解决）
- Soter 重试路径可能破坏宿主密钥（已修复）
- Manifest 中无 app-zygote 预加载（已解决）
- 无 SDK 单元/仪器测试
- 无设备矩阵准确率结果

---

## 22. 参考资料

- [GitHub Packages：Apache Maven registry](https://docs.github.com/en/packages/working-with-a-github-packages-registry/working-with-the-apache-maven-registry)
- [Android 包可见性声明](https://developer.android.com/training/package-visibility/declaring)
- [`CRoootSdk.kt`](../sdk/src/main/java/com/chloemlla/crooot/CRoootSdk.kt)
- [`sdk/build.gradle.kts`](../sdk/build.gradle.kts)
- [`sdk/src/main/AndroidManifest.xml`](../sdk/src/main/AndroidManifest.xml)
- [`sdk/consumer-rules.pro`](../sdk/consumer-rules.pro)
- [KKND Root Detector（原项目）](https://github.com/juanma0511/Kknd_Root_Detector)
- [Duck Detector Refactoring（原项目）](https://github.com/eltavine/Duck-Detector-Refactoring)
- [LSPosed/DirtySepolicy](https://github.com/LSPosed/DirtySepolicy)