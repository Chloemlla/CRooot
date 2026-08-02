# CRooot Android SDK integration guide

[简体中文](SDK_USAGE_ZH.md) · [Documentation index](README.md) · [Project README](../README.MD)

This guide targets third-party Android applications integrating CRooot `0.1.0`. It documents the supported facade, installation paths, result model, operational side effects, known limitations, and production rollout requirements from the actual source and published AAR.

> **Soter note for `0.1.0`:** the original Duck TEE Soter retry path could remove or replace an existing Tencent Soter App Global Secure Key. This SDK **fixes that retry path**: the probe no longer deletes a pre-existing key. However, it still initializes the Soter Treble service and exercises biometric checks. Validate on representative devices before production use. For additional safety, set `includeTee=false` to disable only the TEE report while keeping the other Duck reports enabled.

---

## Table of contents

- [0. Quick start](#0-quick-start)
- [1. Architecture overview](#1-architecture-overview)
- [2. Supported API and compatibility](#2-supported-api-and-compatibility)
- [3. Choose an integration method](#3-choose-an-integration-method)
- [4. Host manifest configuration](#4-host-manifest-configuration)
- [5. Operational side effects and production safety](#5-operational-side-effects-and-production-safety)
- [6. Basic Kotlin usage](#6-basic-kotlin-usage)
- [7. Lifecycle, serialization, timeout, and UI state](#7-lifecycle-serialization-timeout-and-ui-state)
- [8. Result model](#8-result-model)
- [9. Security decision framework](#9-security-decision-framework)
- [10. Failure model](#10-failure-model)
- [11. Java applications](#11-java-applications)
- [12. Performance and benchmarking](#12-performance-and-benchmarking)
- [13. Migration guide](#13-migration-guide)
- [14. CI/CD integration](#14-cicd-integration)
- [15. R8, package size, and duplicate classes](#15-r8-package-size-and-duplicate-classes)
- [16. Privacy and data handling](#16-privacy-and-data-handling)
- [17. Testing and rollout checklist](#17-testing-and-rollout-checklist)
- [18. Upgrade policy](#18-upgrade-policy)
- [19. Troubleshooting](#19-troubleshooting)
- [20. License and redistribution](#20-license-and-redistribution)
- [21. Release notes](#21-release-notes)
- [22. References](#22-references)

---

## 0. Quick start

Get CRooot running in your Android app in five minutes.

### 0.1 Add the dependency

```kotlin
// settings.gradle.kts — add repositories
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

// app/build.gradle.kts — add dependency
dependencies {
    implementation("com.chloemlla.crooot:crooot-sdk:0.1.0")
}
```

### 0.2 Add the biometric permission

```xml
<!-- AndroidManifest.xml -->
<uses-permission android:name="android.permission.USE_BIOMETRIC" />
```

### 0.3 Run a scan

```kotlin
import com.chloemlla.crooot.CRoootScanOptions
import com.chloemlla.crooot.CRoootSdk

val sdk = CRoootSdk.create(applicationContext)

suspend fun checkDeviceSecurity(): String {
    val result = sdk.scan(CRoootScanOptions())
    return result.summary()
    // e.g. "CRooot scan completed in 3200ms; duckReports=16/16"
}
```

### 0.4 Interpret the result

```kotlin
val result = sdk.scan(CRoootScanOptions())

if (result.isRooted) {
    // HIGH-severity root indication found — act accordingly
}
if (result.isSuspicious) {
    // At least one low-severity finding — investigate further
}
val teeReport = result.duckReports["tee"] // TEE attestation evidence
```

### 0.5 Soter safety (if you use Tencent Soter)

```kotlin
CRoootScanOptions(includeTee = false) // Only disables the TEE report; other 15 Duck reports remain active
```

---

## 1. Architecture overview

CRooot combines two open-source Android security-detection engines behind a single coroutine-based facade:

```
┌─────────────────────────────────────────────────────────────────┐
│                      CRoootSdk (public API)                      │
│  com.chloemlla.crooot.CRoootSdk                                  │
├──────────────────────┬──────────────────────────────────────────┤
│    KKND Engine       │            Duck Engine (16 features)      │
│  com.juanma0511.*    │  com.eltavine.duckdetector.features.*    │
│                      │                                           │
│  ┌───────────────┐   │  ┌───┐ ┌───┐ ┌───┐ ┌───┐ ┌───┐ ┌───┐    │
│  │ RootDetector  │   │  │ B │ │ C │ │ D │ │ K │ │ L │ │ M │    │
│  │ (70+ checks)  │   │  │ L │ │ R │ │ A │ │ E │ │ S │ │ M │    │
│  ├───────────────┤   │  │   │ │   │ │   │ │   │ │   │ │   │    │
│  │ HwSecurity    │   │  └───┘ └───┘ └───┘ └───┘ └───┘ └───┘    │
│  │ Detector      │   │  ┌───┐ ┌───┐ ┌───┐ ┌───┐ ┌───┐ ┌───┐    │
│  │ (12 hardware  │   │  │ N │ │ P │ │ S │ │ S │ │ T │ │ V │    │
│  │  checks)      │   │  │ R │ │ I │ │ E │ │ U │ │ E │ │ I │    │
│  ├───────────────┤   │  │   │ │ F │ │ L │ │   │ │ E │ │ R │    │
│  │ Zygote/       │   │  └───┘ └───┘ └───┘ └───┘ └───┘ └───┘    │
│  │ DirtySepolicy │   │  ┌───┐ ┌───┐                             │
│  │ (app-zygote)  │   │  │ S │ │ Z │                             │
│  └───────────────┘   │  │ P │ │ Y │                             │
│                      │  │   │ │ G │                             │
│  Shared native lib   │  └───┘ └───┘                             │
│  libchloemlla-crooot ├──────────────────────────────────────────┤
│  (arm64/arm/x86/x64) │  Native probes (C++20, JNI, assembly)    │
└──────────────────────┴──────────────────────────────────────────┘
```

### 1.1 KKND Engine

The KKND engine provides two result groups:

- **Root detection** (`RootDetector`): ~70 Kotlin checks covering su binaries, root managers (Magisk, KSU, APatch, SuperSU, etc.), dangerous apps, Frida, emulator detection, mount points, SELinux state, kernel cmdline, Zygisk, Xposed, and more. Always runs.
- **Hardware detection** (`HwSecurityDetector`): 12 checks covering TEE availability, Keystore security level, StrongBox, verified boot, bootloader lock, dm-verity, AVB, encryption, and key attestation. Optional via `includeHardware=true`.

### 1.2 Duck Engine

The Duck engine provides 16 feature reports (optional via `includeDuckFeatures=true`). Each feature is a self-contained module with:

- **Domain layer**: report data classes (e.g., `TeeReport`, `SelinuxReport`)
- **Data layer**: repository, native bridge (JNI), probes, rules/catalog
- **Service layer**: some features run in isolated or dedicated processes

### 1.3 Native Layer

All native code is compiled into a single `libchloemlla-crooot.so` with JNI symbols preserved from both original projects. Available ABIs: `arm64-v8a`, `armeabi-v7a`, `x86`, `x86_64`. Assembly-level coverage differs by ABI (some virtualization traps are arm64-only).

### 1.4 Key design decisions

- **No UI dependency**: the SDK does not require Compose, Activity, or any UI framework
- **No root required**: all detection is heuristic and runs within the host app's permissions
- **Offline-first**: TEE revocation checking uses an embedded snapshot; no remote HTTPS by default
- **Concurrent by default**: all detector groups run in parallel via coroutines
- **Best-effort cleanup**: temporary files, keys, and processes are cleaned up, but crashes or force-stops can leave traces

---

## 2. Supported API and compatibility

The supported entry point is:

```kotlin
com.chloemlla.crooot.CRoootSdk
```

The AAR also contains the complete Duck and KKND implementation packages. Treat those packages as implementation details unless this guide explicitly names a report model for result casting.

| Item | `0.1.0` value |
| --- | --- |
| Maven coordinate | `com.chloemlla.crooot:crooot-sdk:0.1.0` |
| Minimum device API | Android 10 / API 29 |
| Minimum consumer `compileSdk` | 36, declared by AAR metadata |
| Consumer `targetSdk` | Controlled by the host application |
| Supported facade language | Kotlin-first suspend API |
| Native library | `libchloemlla-crooot.so` |
| Packaged ABIs | `arm64-v8a`, `armeabi-v7a`, `x86`, `x86_64` |

All four ABIs contain the native library, but low-level feature coverage is not identical. Some virtualization trap assembly is arm64-only. Always inspect report availability fields such as `nativeAvailable` and `asmSupported`.

The SDK is UI-independent and does not require Compose, an Activity, Coil, or the original application UI. Maven/AAR consumers do not need the NDK or CMake. Building from source uses JDK 17, Android SDK/Build Tools 36.0.0, NDK 30.0.15729638, and CMake 4.1.2.

---

## 3. Choose an integration method

### 3.1 GitHub Packages — recommended

GitHub Packages requires authentication for Maven downloads. Create a classic personal access token with `read:packages`, then store it outside the repository, for example in user-level `~/.gradle/gradle.properties`:

```properties
gpr.user=YOUR_GITHUB_USERNAME
gpr.key=YOUR_CLASSIC_PAT_WITH_READ_PACKAGES
```

Add all required repositories in `settings.gradle.kts`. JitPack is needed for the transitive Tencent Soter wrapper:

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

Add the dependency to the host module:

```kotlin
dependencies {
    implementation("com.chloemlla.crooot:crooot-sdk:0.1.0")
}
```

For CI, use a secret with `read:packages`. A repository `GITHUB_TOKEN` can read a package only when package/repository access has been granted appropriately.

### 3.2 Local AAR

Build the AAR from the complete CRooot checkout:

```bash
./gradlew :sdk:assembleRelease
```

The output is `sdk/build/outputs/aar/sdk-release.aar`. Copy or rename it under the host module, for example `app/libs/crooot-sdk-0.1.0.aar`:

```kotlin
dependencies {
    implementation(files("libs/crooot-sdk-0.1.0.aar"))
}
```

A file-based AAR has no Maven POM, so declare the current runtime dependencies manually:

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

Keep the JitPack repository configuration from the Maven example. Use the complete `.aar`; extracting only `classes.jar` loses the manifest, native libraries, assets, and consumer ProGuard rules.

The Actions artifact is not a permanent release channel and can expire. The `v0.1.0` workflow published GitHub Packages and uploaded an Actions AAR artifact, but did not create a GitHub Release.

### 3.3 Source checkout through an included build

The `sdk/` directory is not standalone: it depends on the repository's version catalog, Gradle properties, and build logic. Keep the complete CRooot checkout as a sibling and substitute the published coordinate:

```kotlin
// Host settings.gradle.kts
includeBuild("../CRooot") {
    dependencySubstitution {
        substitute(module("com.chloemlla.crooot:crooot-sdk"))
            .using(project(":sdk"))
    }
}
```

The host dependency remains:

```kotlin
implementation("com.chloemlla.crooot:crooot-sdk:0.1.0")
```

Do not copy only `sdk/` into another build without also migrating every referenced build property, plugin, version-catalog alias, native source, resource, and asset.

---

## 4. Host manifest configuration

### 4.1 Components merged by the AAR

The SDK manifest merges these non-exported services:

| Service | Process behavior | Purpose |
| --- | --- | --- |
| `ZygiskFdTrapDetectorService` | Dedicated `:zygisk_fd_detector` process | Detects Zygisk FD traps |
| `VirtualizationProbeService` | Dedicated `:virtualization_probe` process | Detects virtualization environments |
| `VirtualizationIsolatedProbeService` | Isolated process | Secondary virtualization detection |
| `SelinuxContextValidityCarrierService` | Isolated process (app-zygote) | SELinux context validity oracle |
| `TeeGrantDomainGranteeService` | Isolated process | TEE grant-domain probe |

Inspect the final merged manifest in the host APK. Do not remove these services unless the resulting coverage loss is intentional and tested.

### 4.2 Permissions

The AAR declares no `uses-permission` entries. The host owns every permission decision.

**Required for full TEE coverage:**

```xml
<uses-permission android:name="android.permission.USE_BIOMETRIC" />
```

On biometric-capable devices, omitting it can cause `BiometricManager.canAuthenticate()` to throw and the entire Duck `tee` report to become `FAILED`.

**Optional permissions for additional paths:**

```xml
<!-- Legacy/Soter compatibility. -->
<uses-permission android:name="android.permission.USE_FINGERPRINT" />

<!-- Local Scene/Frida loopback probes; also required by implementation-level online refresh. -->
<uses-permission android:name="android.permission.INTERNET" />

<!-- Connectivity preflight for implementation-level online TEE refresh. -->
<uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />
```

**Permission dependency map:**

```
USE_BIOMETRIC
  └─ TEE deep checks (required for biometric probe)
USE_FINGERPRINT
  └─ Soter legacy path (optional)
INTERNET
  ├─ Scene/Frida loopback probes
  └─ TEE CRL online refresh (offline by default)
ACCESS_NETWORK_STATE
  └─ TEE CRL connectivity preflight (optional)
QUERY_ALL_PACKAGES
  └─ Package-inventory checks (Custom ROM, dangerous apps, LSPosed, virtualization)
```

On a fresh install, the supported facade uses the embedded TEE revocation snapshot and does not make a remote HTTPS request. An implementation-package consent store is technically accessible and persisted state can enable a request to `https://android.googleapis.com/attestation/status`; that path is not part of the stable `com.chloemlla.crooot` facade in `0.1.0`.

For Soter service discovery, the original application used this optional package query, which is not included in the SDK manifest:

```xml
<queries>
    <package android:name="com.tencent.soter.soterserver" />
</queries>
```

### 4.3 Package visibility

`QUERY_ALL_PACKAGES` can improve package inventory coverage, but it is distribution-policy sensitive:

```xml
<uses-permission android:name="android.permission.QUERY_ALL_PACKAGES" />
```

Add it only if the host application qualifies under its distribution store's policy. Without it, package-based Custom ROM, dangerous-app, LSPosed, and virtualization checks can degrade.

The report's `FULL` or `RESTRICTED` package-visibility value is heuristic: the implementation infers it from the number of visible applications. It does not prove whether `QUERY_ALL_PACKAGES` was granted.

### 4.4 Known manifest coverage gaps in `0.1.0`

The AAR **now includes** the app-zygote preload wiring that was missing from the original `0.1.0` release:

- ✅ `android:zygotePreloadName="...AppZygotePreload"` — declared in the SDK manifest
- ✅ `android:useAppZygote="true"` — set on `SelinuxContextValidityCarrierService`

Remaining coverage gaps:

- KKND `DirtySepolicyService` is not declared in the SDK manifest (hosts can add it manually if needed)
- No `NativeActivity` early-preload launcher (not applicable to third-party apps)
- Advanced SELinux app-zygote checks and early mount/virtualization preload signals may still report reduced coverage depending on the host app's manifest

Do not copy the original NativeActivity launcher into a third-party app: its native handoff assumes `${applicationId}.MainActivity` and is not a generic host integration contract.

---

## 5. Operational side effects and production safety

A full scan is local, but it is not purely passive. This section documents every side effect a host must be aware of.

### 5.1 Critical Soter state mutation

The `0.1.0` Soter retry path **has been fixed** to never call `removeAppGlobalSecureKey()` on a pre-existing key. However, the probe still initializes the Soter Treble service and exercises biometric checks.

If the host uses Tencent Soter:

```kotlin
CRoootScanOptions(includeTee = false)
```

The per-feature `includeTee` flag disables only the Duck `tee` report while keeping the other 15 Duck reports active. Validate on representative devices before production use.

### 5.2 Android Keystore mutations

Hardware and Duck TEE scans generate, import, grant, update, use, and delete many AndroidKeyStore test entries. Most Duck aliases are time-based, but process death or vendor failures can leave temporary entries.

**Reserved aliases (host must never use):**

```text
rootdetector_tee_probe
rootdetector_ks_backing
rootdetector_sb_key
```

The operation-pruning probe opens 18 concurrent ECDSA signing operations. This can invalidate or prune a host's concurrent Keystore encryption/signing session. Other TEE probes temporarily grant probe-key access to isolated processes and then attempt to revoke it.

### 5.3 Process-global hooks

TEE deep checks can temporarily replace the process `ServiceManager` Keystore Binder cache to capture responses. Restoration is attempted, but host Keystore calls made concurrently can pass through the probe hook.

The detector also invokes `HiddenApiBypass.addHiddenApiExemptions("")`. Hidden-API exemptions are process-global and are not reverted by the scan.

### 5.4 Files, broadcasts, sockets, and processes

Dangerous-app detection can:

- send an explicit command-like probe broadcast to a known Scene component;
- briefly create/check/delete a randomized external-storage probe file through that interaction;
- try to create a mode-`0` marker directory under `/dev`, then remove it if creation succeeds;
- start shell commands and inspect process, mount, property, and service output.

Other checks connect to local Scene/Frida loopback ports, bind the SDK services, and start dedicated or isolated processes. These actions can appear in EDR, audit, package, network, filesystem, and process telemetry.

Cleanup is best-effort. A crash, force-stop, timeout, or process kill can interrupt it.

### 5.5 Performance and cancellation

The facade launches KKND root, optional KKND hardware, and all sixteen Duck repositories concurrently. Duck TEE then fans out many additional Keystore probes. The timing side-channel path performs about 500 paired Binder/Keystore samples, and the pruning path occupies 18 operation slots.

Version `0.1.0` has:

- no built-in total timeout;
- no progress API;
- no scan mutex;
- no dispatcher/executor injection;
- shared use of `Dispatchers.Default` and `Dispatchers.IO`;
- blocking JNI, process, socket, Binder, and Keystore work that cannot be preempted immediately.

Caller cancellation and `withTimeout` limit how long the host waits, but underlying blocking work can continue briefly. Some implementation `runCatching`/`catch(Throwable)` paths can also delay cancellation propagation.

### 5.6 Required host operating rules

- Run only one scan at a time across the process.
- Do not scan during cold start, login, payment, signing, key rotation, or any host Keystore operation.
- Prefer an explicit user/admin action or a controlled background maintenance window.
- Validate side effects with the host security/EDR team before rollout.
- Cache the host's derived decision instead of scanning repeatedly.
- Test process death and inspect for leftover aliases/files.
- For Soter-using hosts, set `includeTee=false` until validated on representative devices.

---

## 6. Basic Kotlin usage

`CRoootSdk` stores only the application context. Reuse one instance:

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
            includeTee = true, // Set to false for Soter-using hosts.
        ),
    )
}
```

Option semantics:

| Option | Behavior |
| --- | --- |
| `includeHardware=true` | Adds `kkndHardware`; it does not control Duck `tee` |
| `includeHardware=false` | Skips only KKND hardware checks |
| `includeDuckFeatures=true` | Master switch: runs Duck repositories according to individual feature flags |
| `includeDuckFeatures=false` | Returns an empty `duckReports` map (overrides all individual flags) |
| `includeTee=true` | Runs the Duck `tee` feature repository (set to `false` for Soter-using hosts) |
| `includeSelinux=true` | Runs the Duck `selinux` feature repository |
| `includeVirtualization=true` | Runs the Duck `virtualization` feature repository |
| *(11 more individual flags)* | Each Duck feature has a corresponding `include<Feature>` flag, all defaulting to `true` |

KKND root checks **always run** — they cannot be disabled.

### Complete feature flag list

```kotlin
CRoootScanOptions(
    includeDuckFeatures = true,    // Master switch
    includeBootloader = true,      // Bootloader lock status
    includeCustomRom = true,       // Custom ROM detection
    includeDangerousApps = true,   // Dangerous app inventory
    includeDeviceInfo = true,      // Device information
    includeKernel = true,          // Kernel security checks
    includeLsposed = true,         // LSPosed framework detection
    includeMemory = true,          // Memory tampering detection
    includeMount = true,           // Mount point integrity
    includeNativeRoot = true,      // Native root detection
    includePlayIntegrityFix = true,// Play Integrity bypass detection
    includeSelinux = true,         // SELinux state analysis
    includeSu = true,              // SU binary detection
    includeSystemProperties = true,// System property analysis
    includeTee = true,             // TEE/Keystore attestation
    includeVirtualization = true,  // Virtualization environment
    includeZygisk = true,          // Zygisk detection
)
```

---

## 7. Lifecycle, serialization, timeout, and UI state

The following ViewModel serializes scans and exposes explicit state. The timeout bounds host waiting; it does not guarantee immediate termination of blocking detector work.

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
                    "CRooot timed out; blocking probe cleanup may still be finishing.",
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

Use a longer timeout after measuring representative hardware. A full Duck TEE scan can exceed 60 seconds on slow or heavily restricted devices.

---

## 8. Result model

### 8.1 `CRoootScanResult`

| Field | Meaning | Always present? |
| --- | --- | --- |
| `kkndRoot` | KKND root/integrity evidence | ✅ Always |
| `kkndHardware` | KKND hardware/TEE/verified-boot evidence | ❌ Only when `includeHardware=true` |
| `duckReports` | Fixed-key map of Duck domain reports | ✅ Always (empty when disabled) |
| `durationMs` | Overall wall-clock delta | ✅ Always |
| `isRooted` | `true` if any HIGH-severity root indication | ✅ Convenience property |
| `isSuspicious` | `true` if any detection at any severity | ✅ Convenience property |
| `summary()` | Human-readable diagnostic string | ✅ Convenience method |

`durationMs` is diagnostic, not a monotonic benchmark. System clock changes can affect it. `kkndRoot.scanDurationMs` and `kkndHardware.scanDurationMs` are measured from the same overall start time to their respective await points; they are not isolated engine timings.

### 8.2 KKND root results

`kkndRoot.items` contains `DetectionItem` values:

```kotlin
data class DetectionItem(
    val id: String,
    val name: String,
    val description: String,
    val category: DetectionCategory,
    val severity: Severity,     // HIGH or WARNING
    val detected: Boolean,
    val detail: String?,
)
```

Summary semantics:

- `isRooted`: at least one `detected && severity == HIGH` item;
- `isSuspicious`: at least one detected item at any severity;
- `detectedCount`, `highRiskCount`, and `warningCount`: convenience counts.

**Do not discard individual items after reading a summary Boolean.** The `detail` field contains the evidence path, property, or signal that triggered the detection.

### 8.3 KKND hardware results

`kkndHardware?.items` contains `HwCheckItem` values with `group`, `status`, `value`, `expected`, and `detail`.

`overallOk` means there are no `FAIL` or `WARN` items. `UNKNOWN` does not make it false, so `overallOk=true` is not proof of hardware trust or complete coverage.

### 8.4 Duck report keys and types

Keys are case-sensitive. Values are declared as `Any?`; use safe casts.

| Key | Domain type | What it detects | Important fields |
| --- | --- | --- | --- |
| `bootloader` | `BootloaderReport` | Bootloader unlock status | `stage`, `state`, attestation/availability, `findings`, `methods`, `errorMessage` |
| `customRom` | `CustomRomReport` | Custom/informal ROMs | `packageVisibility`, `detectedRoms`, finding groups, availability |
| `dangerousApps` | `DangerousAppsReport` | Root manager, modding, and suspicious apps | visibility, `findings`, `hiddenFromPackageManager`, `issues` |
| `deviceInfo` | `DeviceInfoReport` | General device information | `sections`, `totalCount`, `errorMessage` |
| `kernel` | `KernelCheckReport` | Kernel integrity, CVE patch state | danger/info findings, CVE patch state, `nativeAvailable` |
| `lsposed` | `LSPosedReport` | LSPosed framework traces | availability flags, `signals`, `methods`, hit counts |
| `memory` | `MemoryReport` | Runtime hooking, executable memory | hook/executable-memory flags, `findings`, `methods` |
| `mount` | `MountReport` | Mount point manipulation | readability, early-preload availability, findings, impacts |
| `nativeRoot` | `NativeRootReport` | KernelSU, APatch, SUSFS, etc. | root-family flags, availability/counts, findings, methods |
| `playIntegrityFix` | `PlayIntegrityFixReport` | Play Integrity bypass modules | property/consistency/native signals and availability |
| `selinux` | `SelinuxReport` | SELinux mode, dirty policy, audit | `mode`, `paradoxDetected`, methods, policy/audit analysis |
| `su` | `SuReport` | SU binary, daemon, process context | binaries, daemons, process context, methods |
| `systemProperties` | `SystemPropertiesReport` | Tampered system properties | danger/info signals, source counts, property-area availability |
| `tee` | `TeeReport` | TEE attestation, Keystore, StrongBox, Soter | `stage`, `verdict`, `tier`, score, signals, sections, network/failure state |
| `virtualization` | `VirtualizationReport` | Emulator, VM, AVF, WSA | process/native/ASM availability, counts, signals, impacts |
| `zygisk` | `ZygiskReport` | Zygisk, modules, FD traps | availability, strong/heuristic hits, signals, methods |

Concrete classes live under `com.eltavine.duckdetector.features.<feature>.domain`.

### 8.5 Reading Duck reports — examples

```kotlin
import com.eltavine.duckdetector.features.tee.domain.TeeReport
import com.eltavine.duckdetector.features.tee.domain.TeeScanStage
import com.eltavine.duckdetector.features.selinux.domain.SelinuxReport
import com.eltavine.duckdetector.features.nativeroot.domain.NativeRootReport
import com.eltavine.duckdetector.features.bootloader.domain.BootloaderReport

// TEE attestation
val tee = result.duckReports["tee"] as? TeeReport
val teeSummary = when {
    tee == null -> "TEE report unavailable"
    tee.stage != TeeScanStage.READY -> "TEE failed: ${tee.failureMessage}"
    else -> "TEE verdict=${tee.verdict}, tier=${tee.tier}, signals=${tee.signals.size}"
}

// SELinux state
val selinux = result.duckReports["selinux"] as? SelinuxReport
val selinuxMode = selinux?.mode  // ENFORCING, PERMISSIVE, or null
val dirtyPolicyDetected = selinux?.paradoxDetected

// Native root (KernelSU, APatch, etc.)
val nativeRoot = result.duckReports["nativeRoot"] as? NativeRootReport
val ksuDetected = nativeRoot?.findings?.any { it.contains("KernelSU", ignoreCase = true) }

// Bootloader
val bootloader = result.duckReports["bootloader"] as? BootloaderReport
val bootloaderUnlocked = bootloader?.state?.contains("unlocked", ignoreCase = true)
```

`READY` means aggregation completed, not that a report is clean or fully covered. A READY report can still contain an `errorMessage`, fallback evidence, restricted visibility, unreadable files, or unavailable native methods. Inspect the report's stage, error/failure text, availability/readability/visibility flags, and method outcomes together.

**Do not persist or transmit `duckReports` by blindly serializing `Any?`.** Define a host-owned DTO and explicitly select fields whose compatibility and privacy you accept.

---

## 8.6 Stable local reports for third-party applications

Third-party applications should prefer `scanReport()` when they need a local report. It converts the
legacy `CRoootScanResult` into a stable host-facing model and does not expose Duck's `Any?` map:

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

The existing `scan(CRoootScanOptions)` API is unchanged. `scanReport()` is an additive compatibility
layer, so applications can migrate without changing existing result consumers.

`CRoootLocalReport` contains:

- `overallStatus`, `rooted`, and `suspicious` without treating `UNKNOWN` or `NOT_RUN` as clean;
- stable `detectorSummaries` for KKND root, KKND hardware, and all sixteen Duck keys;
- normalized `findings` with status, severity, confidence, source, recommendations, and privacy-labelled evidence;
- schema version, SDK version, report ID, scan profile, timestamps, duration, and device ABI/API metadata;
- `limitations` explaining missing coverage, restricted checks, and the heuristic nature of the result.

`CRoootScanProfile` provides `QUICK`, `STANDARD`, `FULL`, and `PRIVACY_MINIMAL` presets. Use
`CRoootReportOptions.scanOptions` when a host needs exact per-feature flags. The default exporter emits
only the stable DTO; it never serializes `duckReports`, and it performs no network request or implicit
persistence. Hosts remain responsible for encrypted storage, retention, sharing, and upload consent.

Evidence is redacted by default. Set `includeSensitiveEvidence=true` only for an explicit, local
troubleshooting action. Even then, a report is heuristic evidence and must not be used as the sole basis
for irreversible account or device actions.

The optional progress callback emits `Started`, `Completed`, or `Failed`. Cancellation and exceptions
still propagate to the caller; `CancellationException` is not swallowed.

---

## 9. Security decision framework

CRooot returns **heuristic evidence**, not definitive proof. This section provides a framework for translating evidence into decisions.

### 9.1 Decision levels

```
Level 1: Call failure
  └─ Exception or timeout → do not make any security decision; retry later
Level 2: Evidence available
  ├─ kkndRoot.isRooted == true → HIGH confidence: device is rooted
  ├─ kkndRoot.isSuspicious == true → MEDIUM confidence: suspicious state
  ├─ kkndHardware?.overallOk == false → Hardware/TEE integrity compromised
  └─ duckReports[feature] → inspect individual report fields
Level 3: Evidence unavailable
  ├─ Report is FAILED, UNKNOWN, or null
  ├─ Package visibility is RESTRICTED
  ├─ Native/ASM support is unavailable
  └─ Do NOT interpret as "clean" — treat as "cannot verify"
Level 4: Clean signal
  └─ All supported methods completed with no findings → still not a guarantee
```

### 9.2 Recommended decision logic

```kotlin
fun assessDeviceSecurity(result: CRoootScanResult): SecurityLevel {
    // Level 1: explicit root detection
    if (result.isRooted) return SecurityLevel.ROOTED

    // Level 2: hardware compromise
    if (result.kkndHardware?.overallOk == false) return SecurityLevel.SUSPICIOUS

    // Level 3: suspicious findings
    if (result.isSuspicious) return SecurityLevel.SUSPICIOUS

    // Level 4: check key Duck reports
    val tee = result.duckReports["tee"] as? TeeReport
    if (tee?.verdict == TeeVerdict.FAILED) return SecurityLevel.SUSPICIOUS

    val selinux = result.duckReports["selinux"] as? SelinuxReport
    if (selinux?.paradoxDetected == true) return SecurityLevel.SUSPICIOUS

    // Level 5: check for unavailable coverage
    val inaccessible = result.duckReports.any { (_, report) ->
        report == null || (report is TeeReport && report.stage == TeeScanStage.FAILED)
    }
    if (inaccessible) return SecurityLevel.UNVERIFIABLE

    // Level 6: clean (with caveats)
    return SecurityLevel.CLEAN
}

enum class SecurityLevel {
    ROOTED,        // HIGH confidence: device is rooted
    SUSPICIOUS,    // MEDIUM confidence: indicators present
    UNVERIFIABLE,  // Coverage gaps prevent a conclusion
    CLEAN,         // No indicators detected (not a guarantee)
}
```

### 9.3 Important caveats

- **CLEAN is not TRUSTED.** A clean result means no detection signals were found, not that the device is secure. Sophisticated root kits can hide from detection.
- **UNKNOWN is not CLEAN.** An unavailable probe, restricted visibility, or unsupported native method means the detector could not check. Do not treat this as a pass.
- **Combine multiple signals.** Do not rely on a single Boolean or a single Duck report. Cross-reference `kkndRoot`, `kkndHardware`, and relevant Duck reports.
- **False positives are possible.** Some OEMs or custom ROMs may trigger detectors without being compromised. Validate thresholds on your target devices.
- **Do not use as the sole basis for irreversible actions.** Account bans, data deletion, or other irreversible actions should not rely solely on CRooot results.

---

## 10. Failure model

Many Duck repositories convert their own errors into a report with `FAILED` stage or error fields. Uncaught root/hardware/child failures can still escape `CRoootSdk.scan`, cancel sibling coroutines, and fail the whole call.

At the host boundary:

```kotlin
try {
    consume(sdk.scan(options))
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (failure: Throwable) {
    recordScanFailure(failure)
}
```

Do not swallow `CancellationException`. The ViewModel example also handles timeout separately.

Distinguish:

- call-level exception or timeout;
- report-level `FAILED`;
- `UNKNOWN`, `SUPPORT`, restricted, unreadable, and unavailable coverage;
- a completed and genuinely clean set of supported methods.

---

## 11. Java applications

`scan` is a Kotlin suspend function and has no Java-friendly callback or future overload in `0.1.0`. Add a small Kotlin bridge instead of calling the generated `Continuation` signature directly:

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

The host must still serialize bridge calls and apply all side-effect restrictions.

---

## 12. Performance and benchmarking

### 12.1 Expected scan duration

| Configuration | Typical duration | Notes |
| --- | --- | --- |
| `includeHardware=false, includeDuckFeatures=false` | 2–8 seconds | KKND root only |
| `includeHardware=true, includeDuckFeatures=false` | 5–15 seconds | KKND root + hardware |
| `includeHardware=false, includeDuckFeatures=true` | 10–40 seconds | Duck features without TEE deep checks |
| `includeHardware=true, includeDuckFeatures=true` | 15–60+ seconds | Full scan (TEE is the bottleneck) |

### 12.2 Performance tuning

```kotlin
// Fast scan (no TEE, no hardware)
val fast = sdk.scan(CRoootScanOptions(
    includeHardware = false,
    includeTee = false,
))

// Balanced scan (no TEE deep checks, but hardware basics)
val balanced = sdk.scan(CRoootScanOptions(
    includeHardware = true,
    includeTee = false,
))

// Custom scan (select only features you need)
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

### 12.3 Benchmarking methodology

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
    Log.d("CRooot", "Average scan time: ${"%.0f".format(avgMs)}ms")
}
```

Run benchmarks on representative devices (not just emulators) and test all shipped ABIs.

---

## 13. Migration guide

### 13.1 Migrating from Duck Detector

If you previously used the standalone Duck Detector app/SDK:

1. **Remove** the original Duck Detector dependency from your build.
2. **Add** CRooot SDK (see [§3](#3-choose-an-integration-method)).
3. **Replace** `com.eltavine.duckdetector.*` entry points with `com.chloemlla.crooot.CRoootSdk`.
4. **Update** native library references: Duck used `libduckdetector.so`; CRooot uses `libchloemlla-crooot.so`.
5. **Verify** the merged manifest: CRooot's AAR ships the same 5 services.
6. **Re-test** all Duck feature reports. The domain report types are identical.

### 13.2 Migrating from KKND Root Detector

If you previously used the standalone KKND Root Detector:

1. **Remove** the original KKND dependency.
2. **Add** CRooot SDK (see [§3](#3-choose-an-integration-method)).
3. **Access** KKND results via `result.kkndRoot` and `result.kkndHardware` instead of calling `RootDetector`/`HwSecurityDetector` directly.
4. **Update** native library references: if you used `libnative_root_checks.so`, CRooot bundles it into `libchloemlla-crooot.so`.
5. **Re-test** root detection on your target devices.

### 13.3 Using both original detectors alongside CRooot (not recommended)

```warning
Do not simultaneously package the original Duck/KKND artifacts or copied source trees.
The CRooot AAR already contains those namespaces, services, native symbols, and resources;
duplication can cause class, manifest, or JNI conflicts.
```

---

## 14. CI/CD integration

### 14.1 GitHub Actions

```yaml
name: Security scan

on:
  schedule:
    - cron: '0 6 * * 1'  # Every Monday
  workflow_dispatch:

jobs:
  scan:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - name: Build with CRooot
        run: ./gradlew :app:assembleDebug
      - name: Run CRooot SDK tests
        run: ./gradlew :sdk:testReleaseUnitTest
```

### 14.2 Maven authentication in CI

```yaml
- name: Build
  env:
    GITHUB_ACTOR: ${{ github.actor }}
    GITHUB_TOKEN: ${{ secrets.GITHUB_TOKEN }}
  run: ./gradlew :app:assembleRelease
```

### 14.3 Local AAR in CI

```bash
# Build the AAR
./gradlew :sdk:assembleRelease

# Copy to host project
cp sdk/build/outputs/aar/sdk-release.aar ../host-app/app/libs/crooot-sdk-0.1.0.aar

# Build the host
cd ../host-app && ./gradlew assembleRelease
```

---

## 15. R8, package size, and duplicate classes

The AAR bundles consumer rules that keep:

```text
com.chloemlla.crooot.CRoootSdk
com.eltavine.duckdetector.**
com.juanma0511.rootdetector.**
```

Normally, no additional keep rules are required. The conservative rules also mean most SDK bytecode cannot be removed or obfuscated. `includeHardware` and `includeDuckFeatures` change runtime work only; they do not materially reduce packaged code size.

Do not simultaneously package the original Duck/KKND artifacts or copied source trees. The CRooot AAR already contains those namespaces, services, native symbols, and resources; duplication can cause class, manifest, or JNI conflicts.

---

## 16. Privacy and data handling

The stable fresh-install path performs no remote HTTPS refresh, but a scan can expose sensitive diagnostic data to the host process, including:

- installed/visible package names;
- system properties, build fields, mounts, paths, processes, and service names;
- Android Keystore and attestation certificate details;
- device and runtime environment information;
- explicit evidence strings produced by native and shell probes.

Minimize collection and retention. Redact before logs or telemetry, obtain any required user/admin consent, and never upload raw reports merely because the SDK itself is local-first.

The explicit Scene broadcast and loopback connections are local interactions, but they are still observable active behavior and should be included in privacy/security review.

---

## 17. Testing and rollout checklist

Before production rollout:

- verify the merged manifest and permissions;
- verify the application does not use Soter, or set `includeTee=false`;
- search the host for the three reserved KKND aliases;
- test one-scan-at-a-time enforcement;
- test timeout, cancellation, process death, and cleanup;
- test with and without package visibility;
- test offline and local-loopback permission behavior;
- inspect restricted/unavailable states instead of treating them as clean;
- test stock and modified devices across representative OEMs and Android releases;
- test all shipped ABIs, especially non-arm64 reduced assembly coverage;
- validate CPU, I/O, battery, process, and Keystore impact;
- notify SOC/EDR owners about active broadcasts, files, sockets, hooks, and processes;
- verify the summary `isRooted` and `isSuspicious` convenience properties match expectations;
- if using per-feature flags, verify each enabled feature produces a report in `duckReports`.

Current repository CI runs `:sdk:lintRelease :sdk:assembleRelease`. It validates static build/package readiness only. There are no SDK unit/instrumentation test trees and no published device-matrix accuracy result.

---

## 18. Upgrade policy

- Pin an exact SDK version.
- Re-read both guides and inspect source/API diffs before upgrading.
- Diff the merged manifest and bundled consumer rules.
- Re-test side effects, fixed aliases, Soter behavior, and permissions.
- Continue using safe casts and tolerate unknown Duck keys.
- Treat implementation-package classes as unstable even when Kotlin visibility currently exposes them.
- Re-run the device/OEM/ABI rollout matrix.

---

## 19. Troubleshooting

### GitHub Packages returns `401` or `403`

Use a classic PAT with `read:packages`, confirm the username matches the token owner, and keep credentials in user Gradle properties or CI secrets. Verify package access for CI repositories.

### Could not resolve `com.github.Tencent.soter:soter-wrapper`

Add the filtered JitPack repository shown above. Maven repositories declared by a library are not automatically inherited by consumers.

### AAR metadata requires compile SDK 36

Set the host `compileSdk` to 36 or newer. Changing only `targetSdk` does not satisfy `minCompileSdk`.

### Duplicate classes under Duck or KKND packages

Remove original detector dependencies or copied detector sources. Use only the CRooot AAR/source build.

### Duck `tee` is `FAILED` with a biometric/security error

Declare `android.permission.USE_BIOMETRIC`, rebuild, and inspect the merged manifest. Do not confuse this with a runtime permission dialog; it is a normal permission.

### Package visibility says `RESTRICTED`

Treat it as reduced coverage. Review scoped `<queries>` first. Add `QUERY_ALL_PACKAGES` only when policy permits; the report value itself is not proof of permission state.

### Scan is slow or times out

This is expected on TEE-heavy devices. Serialize scans, move them out of interaction-critical paths, measure a suitable timeout, and remember that timeout does not immediately stop blocking work. `includeHardware=false` still runs Duck `tee`; use `includeTee=false` to skip the TEE report, or `includeDuckFeatures=false` to skip all Duck reports.

### Native or ASM support is unavailable

Check the device ABI and report availability fields. A packaged `.so` does not guarantee equal assembly coverage. Also verify the AAR was not replaced by `classes.jar`.

### No GitHub Release is visible

`v0.1.0` published GitHub Packages and an Actions artifact only. Use the Maven coordinate or build from source.

### Host uses Tencent Soter

The Soter retry path in `0.1.0` has been fixed to never delete a pre-existing App Global Secure Key. For additional safety, set `includeTee=false` to disable only the Duck TEE report while keeping the other 15 Duck reports enabled. Validate on representative devices before production use.

### Troubleshooting decision tree

```
Is the scan failing with an exception?
├─ Yes → Is it a CancellationException?
│   ├─ Yes → Rethrow it (don't swallow)
│   └─ No → Check network, permissions, and device state
└─ No → Is the scan returning results but kkndRoot is empty?
    ├─ Yes → Check if the native library loaded correctly
    └─ No → Is the TEE report FAILED?
        ├─ Yes → Do you have USE_BIOMETRIC declared?
        │   ├─ Yes → Test on a device with biometric hardware
        │   └─ No → Add USE_BIOMETRIC to manifest
        └─ No → Are Duck reports missing?
            ├─ Yes → Is includeDuckFeatures=true?
            └─ No → Check individual report fields
```

---

## 20. License and redistribution

The artifact contains component-specific Apache-2.0 and MIT code. The published `0.1.0` POM lists only Apache-2.0, and the actual AAR does not embed `LICENSE`, `NOTICE`, or the retained MIT license.

When redistributing the AAR, source, or a derived product, include:

- [`../LICENSE`](../LICENSE)
- [`../NOTICE`](../NOTICE)
- [`../legacy/kknd-root-detector/LICENSE-MIT`](../legacy/kknd-root-detector/LICENSE-MIT)

The CI private-key marker check covers selected source extensions only and is not repository-wide secret scanning. Redistributors must perform their own comprehensive secret and artifact scan.

---

## 21. Release notes

### 0.1.0 (current)

**Initial release.**

- Combined KKND Root Detector + Duck Detector engines
- Single `CRoootSdk` facade with `suspend fun scan()`
- 16 Duck feature reports with fixed keys
- KKND root (always) and hardware (optional) results
- Per-feature Duck flags (`includeTee`, `includeSelinux`, etc.)
- `CRoootScanResult.isRooted`, `isSuspicious`, and `summary()` convenience properties
- Soter retry path fix (no longer deletes pre-existing ASK)
- App-zygote preload wiring in manifest
- Published to GitHub Packages (`com.chloemlla.crooot:crooot-sdk:0.1.0`)
- 4 ABIs: `arm64-v8a`, `armeabi-v7a`, `x86`, `x86_64`
- Minimum Android 10 (API 29), compileSdk 36

**Known limitations:**

- No per-feature selector for Duck reports (now resolved with individual `include<Feature>` flags)
- Soter retry path could destroy host keys (now fixed)
- No app-zygote preload in manifest (now resolved)
- No SDK unit/instrumentation tests
- No device-matrix accuracy results

---

## 22. References

- [GitHub Packages: Apache Maven registry](https://docs.github.com/en/packages/working-with-a-github-packages-registry/working-with-the-apache-maven-registry)
- [Android package visibility declarations](https://developer.android.com/training/package-visibility/declaring)
- [`CRoootSdk.kt`](../sdk/src/main/java/com/chloemlla/crooot/CRoootSdk.kt)
- [`sdk/build.gradle.kts`](../sdk/build.gradle.kts)
- [`sdk/src/main/AndroidManifest.xml`](../sdk/src/main/AndroidManifest.xml)
- [`sdk/consumer-rules.pro`](../sdk/consumer-rules.pro)
- [KKND Root Detector (original)](https://github.com/juanma0511/Kknd_Root_Detector)
- [Duck Detector Refactoring (original)](https://github.com/eltavine/Duck-Detector-Refactoring)
- [LSPosed/DirtySepolicy](https://github.com/LSPosed/DirtySepolicy)