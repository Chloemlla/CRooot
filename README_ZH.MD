<p align="center">
  <a href="https://github.com/Chloemlla/CRooot/actions/workflows/android-sdk-ci.yml"><img src="https://github.com/Chloemlla/CRooot/actions/workflows/android-sdk-ci.yml/badge.svg" alt="Android SDK CI"></a>
  <img src="https://img.shields.io/badge/version-0.1.0-blue" alt="SDK version 0.1.0">
  <img src="https://img.shields.io/badge/Android-10%2B-3DDC84?logo=android&logoColor=white" alt="Android 10 or newer">
  <a href="NOTICE"><img src="https://img.shields.io/badge/license-Apache--2.0%20%2B%20MIT-blue" alt="Apache License 2.0 and MIT"></a>
</p>

<p align="center">
  <strong><a href="README_ZH.MD">简体中文</a></strong> · <strong><a href="README.MD">English</a></strong>
</p>

# CRooot Android SDK

CRooot 是一个与 UI 无关的 Android 安全检测 SDK。它将 KKND Root/硬件检查与 Duck 检测报告整合到同一个基于协程的门面中：

```kotlin
com.chloemlla.crooot.CRoootSdk
```

SDK 在设备本地收集 Root、启动完整性、Android Keystore/TEE、运行时 Hook、挂载篡改、SELinux、可疑应用、虚拟化、Zygisk 等安全证据。它不需要 Root 权限，也不会把任何单一结果宣称为设备可信或已被攻破的绝对证明。

## 文档

- [完整简体中文接入指南](docs/SDK_USAGE_ZH.md)
- [Complete English integration guide](docs/SDK_USAGE.md)
- [SDK 文档索引](docs/README.md)
- [公开门面源码](sdk/src/main/java/com/chloemlla/crooot/CRoootSdk.kt)

## SDK 能力

- 单一 `suspend` 扫描 API，仅持有 Application Context。
- 结构化 KKND Root 结果，以及可选的 KKND 硬件/验证启动结果。
- 通过固定且区分大小写的键返回 16 类 Duck 检测报告。
- `libchloemlla-crooot.so` 已打包 `arm64-v8a`、`armeabi-v7a`、`x86`、`x86_64`，但不同 ABI 的底层汇编覆盖并不等价。
- 不要求宿主引入 Compose、Activity、Coil 或任何 SDK UI。
- AAR 已内置消费者侧 R8/ProGuard 规则。
- 稳定门面默认使用内置快照执行离线优先的 TEE 吊销检查。

当前消费者规则会保留两套 detector 代码树；扫描选项只能减少运行时工作，不会明显减少 AAR/APK 代码体积。

## 接入要求

| 要求 | 取值 |
| --- | --- |
| 最低 Android 版本 | Android 10 / API 29 |
| 宿主 `compileSdk` | 36 或更高（AAR 的 `minCompileSdk=36`） |
| 宿主 `targetSdk` | 由宿主应用自行决定 |
| 主要 API 语言 | Kotlin；Java 调用方需要 Kotlin 协程桥接层 |
| Maven 认证 | GitHub 用户名及具备 `read:packages` 的令牌 |

通过 Maven/AAR 使用已发布 SDK 时不需要安装 Android NDK 或 CMake。从源码构建 SDK 时需要 JDK 17、Android SDK/Build Tools 36.0.0、NDK 30.0.15729638 和 CMake 4.1.2。

## v0.1.0 Soter 严重警告

原始 Duck TEE 的 Soter 探针在重试路径中可能删除或替换宿主已有的 Tencent Soter App Global Secure Key。本 SDK **已修复该重试路径**：探针不再删除宿主已有的密钥（属于宿主应用）。但探针仍会初始化 Soter Treble 服务并执行生物识别检查。使用 Soter 的宿主应在代表性设备上验证后再用于生产环境。

v0.1.0 现在支持**逐功能开关**：设置 `includeTee=false` 可仅关闭 TEE 报告，同时保持其他 15 个 Duck 功能启用。之前只能通过 `includeDuckFeatures=false` 关闭所有 Duck 报告。

## 从 GitHub Packages 安装

将凭据保存在仓库之外，例如用户级 `~/.gradle/gradle.properties`：

```properties
gpr.user=YOUR_GITHUB_USERNAME
gpr.key=YOUR_CLASSIC_PAT_WITH_READ_PACKAGES
```

在 `settings.gradle.kts` 中添加仓库。SDK 的传递依赖 Soter 来自 JitPack，示例通过内容过滤仅允许其准确 group：

```kotlin
dependencyResolutionManagement {
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

在宿主模块中添加依赖：

```kotlin
dependencies {
    implementation("com.chloemlla.crooot:crooot-sdk:0.1.0")
}
```

本地 AAR 与源码模块接入方式见完整指南。

## 最小扫描示例

```kotlin
import com.chloemlla.crooot.CRoootScanOptions
import com.chloemlla.crooot.CRoootSdk

val sdk = CRoootSdk.create(applicationContext)

val result = sdk.scan(
    CRoootScanOptions(
        includeHardware = true,
        includeDuckFeatures = true, // v0.1.0 中使用 Soter 的宿主必须设为 false。
    ),
)

val rooted = result.kkndRoot.isRooted
val suspicious = result.kkndRoot.isSuspicious
val hardware = result.kkndHardware
val teeReport = result.duckReports["tee"]
```

请在由生命周期管理的协程中调用 `scan`，并由宿主处理取消、超时与异常。同一时刻只运行一次扫描：阻塞的 native、进程与 Keystore 工作可能在调用方取消或超时后短暂继续。`includeHardware=false` 只关闭 `kkndHardware`，不会关闭 `duckReports["tee"]`。`0.1.0` 暂不支持逐个选择 Duck 模块；`includeDuckFeatures=false` 会关闭全部 16 类 Duck 报告。

固定 Duck 键如下：

```text
bootloader, customRom, dangerousApps, deviceInfo, kernel, lsposed,
memory, mount, nativeRoot, playIntegrityFix, selinux, su,
systemProperties, tee, virtualization, zygisk
```

门面将值声明为 `Any?`，接入方应按文档使用安全类型转换，并在升级时容忍未知键。

## Manifest 与权限

AAR 会合并 5 个非导出的检测 Service，但自身不声明任何 `uses-permission`；宿主需要自行决定并声明所需权限。

- 为保证 Duck TEE 深检可靠运行，应声明普通权限 `USE_BIOMETRIC`；在具备生物识别硬件的设备上，缺失该权限可能导致整个 `tee` 报告失败。兼容旧版/Soter 路径的 `USE_FINGERPRINT` 为可选项。
- `QUERY_ALL_PACKAGES` 可提高应用清单检测覆盖度，但只有在应用符合分发平台政策时才应添加。包可见性受限必须解释为覆盖度下降，不能解释为“未检测到风险”。
- 全新安装时，稳定门面使用内置吊销快照，不会主动发起远程 HTTPS 请求；如果实现层曾持久化在线同意，后续扫描可能改变此行为。`INTERNET` 权限也可提高本机回环端口探针覆盖度。

请在宿主应用中检查最终合并后的 Manifest，并在修改或移除 SDK Service 前阅读完整指南。

`0.1.0` 未打包原应用专用的 app-zygote 与 early-preload 启动接线，因此标准 AAR 接入中的高级 SELinux app-zygote、DirtySepolicy、early mount/virtualization 路径可能报告覆盖度降低或不可用。

## 扫描副作用

完整扫描在本地执行，但并非纯被动读取。部分探针会向已知第三方包发送显式广播、短暂创建并删除探针路径/文件、测试本机回环端口，以及生成和删除 AndroidKeyStore 测试密钥。KKND 硬件检查尤其会占用并删除以下固定 alias：

```text
rootdetector_tee_probe
rootdetector_ks_backing
rootdetector_sb_key
```

TEE 深检还会临时 Hook 当前进程的 Keystore Binder 缓存、放宽进程级隐藏 API exemption，并占用 18 个并发签名操作槽。宿主绝不能复用上述固定 alias。请先在有代表性的设备上验证扫描行为，串行调用 `scan`，避免与任何宿主 Keystore/密码学操作并发；如果进程在清理前被终止，也应允许存在临时测试 alias。完整扫描可能持续数十秒，并可能拉起子进程或隔离进程。不要在冷启动、登录、支付、签名或密钥轮换期间运行。

AAR 完整包含 `com.eltavine.duckdetector.*` 与 `com.juanma0511.rootdetector.*` 实现树。不要同时打包原始 Duck/KKND artifact 或复制的 detector 源码，否则可能产生 duplicate class、Manifest 组件和 native/JNI 冲突。

## 结果使用原则

CRooot 返回证据、可用性、严重度、详情和支持状态。第三方接入时应：

- 联合评估多个独立信号，不依赖单个布尔值；
- 将 `FAILED`、`UNKNOWN`、`SUPPORT`、受限和不可用状态与“干净”状态严格区分；
- 在保留诊断证据的同时，尽量减少设备数据的采集、上传与留存；
- 在有代表性的 OEM、Android 版本、ABI、模拟器和修改环境上验证阈值；
- 不把 SDK 作为执行不可逆账号或设备处置的唯一依据。

## 构建与验证

从源码仅构建 SDK 模块：

```bash
./gradlew :sdk:lintRelease :sdk:assembleRelease
```

`v0.1.0` 的 GitHub Actions 发布任务已成功发布 Maven 产物并上传 AAR workflow artifact，但没有创建 GitHub Release。CI 仅对选定源码扩展执行有限的私钥标记检查，不能替代仓库级 secret scanning。

## 许可证与再分发

CRooot 集成代码与 Duck 检测代码使用 Apache License 2.0；保留的 KKND 代码使用 MIT License；DirtySepolicy 衍生文件保留 Apache 2.0 归属。再分发方必须保留相应声明。

`v0.1.0` AAR 本身未嵌入所需声明。发布 AAR、源码或衍生产品时，必须随分发附带 [LICENSE](LICENSE)、[NOTICE](NOTICE) 与[保留的 KKND MIT 许可证](legacy/kknd-root-detector/LICENSE-MIT)。
