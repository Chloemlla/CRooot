<p align="center">
  <img src="logo.png" alt="DuckDetector Logo" width="600px">
</p>

<p align="center">
  <a href="https://github.com/eltavine/Duck-Detector-Refactoring/stargazers"><img src="https://img.shields.io/github/stars/eltavine/Duck-Detector-Refactoring?style=social" alt="Stars"></a>
  <a href="https://github.com/eltavine/Duck-Detector-Refactoring/network/members"><img src="https://img.shields.io/github/forks/eltavine/Duck-Detector-Refactoring?style=social" alt="Forks"></a>
  <a href="https://github.com/eltavine/Duck-Detector-Refactoring/releases"><img src="https://img.shields.io/github/v/release/eltavine/Duck-Detector-Refactoring?style=flat-square&logo=github" alt="Releases"></a>
  <a href="https://github.com/eltavine/Duck-Detector-Refactoring/releases"><img src="https://img.shields.io/github/downloads/eltavine/Duck-Detector-Refactoring/total?style=flat-square" alt="Downloads"></a>
  <a href="https://t.me/duck_detector"><img src="https://img.shields.io/badge/Telegram-Channel-2CA5E0?logo=telegram&logoColor=white&style=flat-square" alt="Telegram"></a>
  <a href="https://github.com/eltavine/Duck-Detector-Refactoring/blob/main/LICENSE"><img src="https://img.shields.io/github/license/eltavine/Duck-Detector-Refactoring?style=flat-square" alt="License"></a>
</p>

<p align="center">
  <strong><a href="README.MD">English</a></strong> &nbsp; <strong><a href="README_ZH.MD">简体中文</a></strong>
</p>

<h4 align="center">
  <strong>DuckDetector is the ultimate open-source, on-device Android security tool for auditing Bootloader, Root, SU, and KeyStore Attestation.</strong>
</h4>

# 📖 Description
DuckDetector is an Android security detection application focusing on local, on-device evidence collection. It is designed to identify Root-related tampering, runtime Hooks, Mount operations, KeyStore integrity (Attestation Trust), and virtualized execution environments. This application combines a Jetpack Compose UI interface, modular Kotlin detection packages, and low-level C++ / assembly probes to present detailed detection results, method coverage, and scanning status summaries through structured cards.

# CRooot SDK
CRooot exposes the detector implementations through the UI-independent `CRoootSdk` facade. Add the SDK when a published release is available:

```kotlin
implementation("com.chloemlla.crooot:crooot-sdk:0.1.0")
```

Run a scan from a coroutine:

```kotlin
val result = CRoootSdk.create(applicationContext).scan()
val rootItems = result.kkndRoot.items
val duckReports = result.duckReports
```

Use `CRoootScanOptions(includeHardware = false, includeDuckFeatures = false)` to omit optional portions. Results are heuristic evidence, not a guarantee of device integrity; OEM, device, ABI, and sandbox restrictions can reduce coverage. See [`docs/SDK_USAGE.md`](docs/SDK_USAGE.md) for the full API example.

The published core is UI-independent and does not force Compose, Coil, or Activity dependencies onto the host. The copied UI sources remain available for audit and optional host integration.

# ⚠️ Development Rules
> Please read the [DuckDetector coding standards](./CODING_STANDARDS.md) before submitting changes to the codebase, workflows, or git history.

# ✨ Highlights
 * **Modular Architecture**: Features independent modules with specific functions; each module includes its own exclusive Repository, Mapper, ViewModel, and card UI.
 * **Native Preload**: Implements low-level early preloading via a transparent NativeActivity launcher to capture extremely early mount points and virtualization traces.
 * **Native Probes**: Utilizes system-call (Syscall) level, time-sensitive, mount, and runtime visibility checks implemented in C++ and Assembly.
 * **Cross-Process & Isolated Process Validation**: Co-validates across multiple processes and isolated processes (Isolated Process) to minimize reliance on a single signal.
 * **Local & Privacy-First**: By default, TEE revocation checks use a built-in static snapshot. It only accesses the network when the user manually enables "Online Refresh" in settings.
# 🔍 Detector Coverage
The app currently supports heuristic detection in the following security domains:
> Bootloader · Custom ROM · Dangerous Apps · Kernel Check · LSPosed · Memory · Mount · Native Root · Play Integrity Fix · SELinux · SU · System Properties · TEE · Virtualization · Zygisk
> 
In addition, the project contains helper modules such as dashboard, settings, and deviceinfo to provide data aggregation, user controls, and device context visualization.
# 📱 Compatibility

| Dimension / Target | Notes |
| :--- | :--- |
| **Android Version** | Supports Android 10+ (minSdk 29), built based on targetSdk 37 / compileSdk 37.0. |
| **Architecture (ABI)** | Low-level probes are implemented via the NDK layer; some low-level trap paths are optimized primarily for the arm64-v8a architecture. |
| **Environment Requirements** | Runs without root permissions, suitable for stock official systems as well as various customized/modified environments. |
| **Coverage Performance** | When OEM manufacturers or strict sandbox rules restrict access, some checks may downgrade to support, unavailable, or low-coverage status. |
| **Network Dependency** | Completely local by default. Built-in TEE revocation credentials run offline, generating traffic only during a manual network refresh. |

## 🎨 User Interface
 * **Compose Dashboard**: Includes detection cards, scan status summaries, top findings, and detailed expandable rows.
 * **Consistent Card Design**: A unified shared card style ensures excellent readability for long labels, detection statuses, and evidence strings across different modules.
 * **Independent Settings & Info Streams**: Separates advanced controls and environmental context from the main detection logic to keep the interface clean.
## 📁 Architecture Snapshot
```text
Duck-Detector-Refactoring/
├─ app/
│  ├─ src/main/java/com/eltavine/duckdetector/
│  │  ├─ core/            # Core common components and foundations
│  │  ├─ features/        # Individual detection feature modules
│  │  └─ ui/              # Global common UI and themes
│  └─ src/main/cpp/       # Low-level C++ / Assembly probe source code
├─ build-logic/           # Gradle composite build logic
├─ gradle/                # Gradle wrapper configuration
├─ scripts/               # Automation or helper scripts
├─ build.gradle.kts
└─ settings.gradle.kts
```
Most detection feature modules (features) strictly follow this clear package structure:
 * **domain**: Data models for reports and results (Models).
 * **data**: Repositories, probes, native bridges, and system service helpers.
 * **presentation**: Data mappers and UI-state reducers.
 * **ui**: Detection card components and module-specific UI models.
> 💡 All native probes are located under app/src/main/cpp and are ultimately compiled into a single shared dynamic link library (.so). This is used for preload capture, mount checks, virtualization snapshots, renderer validation, and low-level trap path interceptions.
> 
## 🛠️ Build
### Requirements
 * **Android Studio** (Latest stable version recommended)
 * Android SDK 37.0 & Android Build Tools 37.0.0
 * **JDK** 17
 * Android **NDK** 30.0.14904198
 * **CMake** 4.1.2
### Build Commands
**Compile Debug Version:**
```bash
# Windows environment
gradlew.bat :app:assembleDebug
# Linux / macOS environment
./gradlew :app:assembleDebug
```
**Daily Development Validation Command (Recommended):**
```bash
# Validate Kotlin compilation, run unit tests, and package the app
./gradlew :app:compileDebugKotlin :app:testDebugUnitTest :app:assembleDebug
```
> 🔐 **Signing Note**: Automatic signing for the Release version is controlled by the environment variables ANDROID_KEYSTORE_PATH, ANDROID_KEYSTORE_PASSWORD, ANDROID_KEY_ALIAS, and ANDROID_KEY_PASSWORD. When these four variables are fully present, the build system will automatically enable the ciRelease signing configuration.
> 
## 🔒 Privacy & Limits
 * **Localized Security**: Detection logic runs primarily locally on the device; it does not collect or upload your sensitive private data.
 * **Non-Intrusive**: The app itself **does not** require root privileges to function properly.
 * **Heuristic Detection**: All security detections are based on heuristic algorithms. No single signal should be relied upon in isolation as absolute proof of trustworthiness.
 * **Hardware & Vendor Restrictions**: Due to OEM policies or sandbox rules, some probes may report downgraded statuses on specific models. Certain low-level checks rely strictly on the arm64-v8a ABI architecture.
## 📈 Status & Stars

<p>
  <img src="https://api.star-history.com/svg?repos=eltavine/Duck-Detector-Refactoring&type=Date" alt="Star History Chart" width="600">
</p>

## ⚖️ Disclaimer
This software is provided "as is", without warranty of any kind. It is designed for educational, diagnostic, and security research purposes only. The developers assume no liability for direct or indirect damage, data loss, or system instability resulting from use of this application. Relying on heuristic security detection is at your own risk.

## 📄 License
The CRooot integration and Duck detector code are distributed under the Apache License 2.0. The retained KKND detector code is Copyright (c) 2026 Juan Martin and distributed under the MIT License; its notice must be preserved. DirtySepolicy-related code remains blocked from redistribution until its upstream provenance and license notice are verified. See [`LICENSE`](LICENSE) and [`NOTICE`](NOTICE).

## 👥 Contributors

### Code Contributors

<a href="https://github.com/eltavine/Duck-Detector-Refactoring/graphs/contributors">
  <img src="https://contrib.rocks/image?repo=eltavine/Duck-Detector-Refactoring" alt="Contributors" />
</a>

### Special Thanks

<p>
  <a href="https://github.com/aviraxp" title="aviraxp"><img src="https://images.weserv.nl/?url=https://github.com/aviraxp.png&w=64&h=64&mask=circle" width="64" height="64" alt="aviraxp"></a>
  &nbsp;
  <a href="https://github.com/5ec1cff" title="5ec1cff"><img src="https://images.weserv.nl/?url=https://github.com/5ec1cff.png&w=64&h=64&mask=circle" width="64" height="64" alt="5ec1cff"></a>
</p>

