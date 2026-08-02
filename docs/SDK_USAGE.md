# CRooot Android SDK integration guide

[简体中文](SDK_USAGE_ZH.md) · [Documentation index](README.md) · [Project README](../README.MD)

This guide targets third-party Android applications integrating CRooot `0.1.0`. It documents the supported facade, installation paths, result model, operational side effects, known limitations, and production rollout requirements from the actual source and published AAR.

> **Critical Soter warning for `0.1.0`:** the Duck TEE Soter retry path can remove or replace an existing Tencent Soter App Global Secure Key. A host that uses Soter must set `includeDuckFeatures=false` until the detector is fixed. Version `0.1.0` cannot disable only the `tee` report, so this also disables the other Duck reports.

## 1. Supported API and compatibility

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

## 2. Choose an integration method

### 2.1 GitHub Packages — recommended

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

### 2.2 Local AAR

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

### 2.3 Source checkout through an included build

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

## 3. Host manifest configuration

### 3.1 Components merged by the AAR

The SDK manifest merges these non-exported services:

| Service | Process behavior |
| --- | --- |
| `ZygiskFdTrapDetectorService` | Dedicated `:zygisk_fd_detector` process |
| `VirtualizationProbeService` | Dedicated `:virtualization_probe` process |
| `VirtualizationIsolatedProbeService` | Isolated process |
| `SelinuxContextValidityCarrierService` | Isolated process |
| `TeeGrantDomainGranteeService` | Isolated process |

Inspect the final merged manifest in the host APK. Do not remove these services unless the resulting coverage loss is intentional and tested.

### 3.2 Permissions

The AAR declares no `uses-permission` entries. The host owns every permission decision.

For reliable full Duck TEE scans, declare the normal biometric permission:

```xml
<uses-permission android:name="android.permission.USE_BIOMETRIC" />
```

On biometric-capable devices, omitting it can cause `BiometricManager.canAuthenticate()` to throw and the entire Duck `tee` report to become `FAILED`.

Optional permissions for additional paths:

```xml
<!-- Legacy/Soter compatibility. -->
<uses-permission android:name="android.permission.USE_FINGERPRINT" />

<!-- Local Scene/Frida loopback probes; also required by implementation-level online refresh. -->
<uses-permission android:name="android.permission.INTERNET" />

<!-- Connectivity preflight for implementation-level online TEE refresh. -->
<uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />
```

On a fresh install, the supported facade uses the embedded TEE revocation snapshot and does not make a remote HTTPS request. An implementation-package consent store is technically accessible and persisted state can enable a request to `https://android.googleapis.com/attestation/status`; that path is not part of the stable `com.chloemlla.crooot` facade in `0.1.0`.

For Soter service discovery, the original application used this optional package query, which is not included in the SDK manifest:

```xml
<queries>
    <package android:name="com.tencent.soter.soterserver" />
</queries>
```

This query does not mitigate the critical Soter key-deletion warning.

### 3.3 Package visibility

`QUERY_ALL_PACKAGES` can improve package inventory coverage, but it is distribution-policy sensitive:

```xml
<uses-permission android:name="android.permission.QUERY_ALL_PACKAGES" />
```

Add it only if the host application qualifies under its distribution store's policy. Without it, package-based Custom ROM, dangerous-app, LSPosed, and virtualization checks can degrade.

The report's `FULL` or `RESTRICTED` package-visibility value is heuristic: the implementation infers it from the number of visible applications. It does not prove whether `QUERY_ALL_PACKAGES` was granted.

### 3.4 Known manifest coverage gaps in `0.1.0`

The AAR does not include the original application's app-zygote and early-start wiring:

- no application `android:zygotePreloadName`;
- no `android:useAppZygote=true` on the SELinux carrier;
- no KKND DirtySepolicy service declaration;
- no NativeActivity early-preload launcher.

Consequently, advanced SELinux app-zygote checks, two KKND DirtySepolicy checks, and early mount/virtualization preload signals can report reduced or unavailable coverage. Do not claim parity with the original application.

Do not copy the original NativeActivity launcher into a third-party app: its native handoff assumes `${applicationId}.MainActivity` and is not a generic host integration contract.

## 4. Operational side effects and production safety

A full scan is local, but it is not purely passive.

### 4.1 Critical Soter state mutation

The `0.1.0` Soter retry path can call `removeAppGlobalSecureKey()` even when the App Global Secure Key existed before the scan, then generate a replacement. This can break a host's existing Soter-based business state.

If the host uses Tencent Soter:

```kotlin
CRoootScanOptions(includeDuckFeatures = false)
```

There is no supported way in `0.1.0` to keep the other Duck reports while disabling only `tee`.

### 4.2 Android Keystore mutations

Hardware and Duck TEE scans generate, import, grant, update, use, and delete many AndroidKeyStore test entries. Most Duck aliases are time-based, but process death or vendor failures can leave temporary entries.

KKND hardware checks delete these fixed aliases before and after use:

```text
rootdetector_tee_probe
rootdetector_ks_backing
rootdetector_sb_key
```

The host must never use those aliases.

The operation-pruning probe opens 18 concurrent ECDSA signing operations. This can invalidate or prune a host's concurrent Keystore encryption/signing session. Other TEE probes temporarily grant probe-key access to isolated processes and then attempt to revoke it.

### 4.3 Process-global hooks

TEE deep checks can temporarily replace the process `ServiceManager` Keystore Binder cache to capture responses. Restoration is attempted, but host Keystore calls made concurrently can pass through the probe hook.

The detector also invokes `HiddenApiBypass.addHiddenApiExemptions("")`. Hidden-API exemptions are process-global and are not reverted by the scan.

### 4.4 Files, broadcasts, sockets, and processes

Dangerous-app detection can:

- send an explicit command-like probe broadcast to a known Scene component;
- briefly create/check/delete a randomized external-storage probe file through that interaction;
- try to create a mode-`0` marker directory under `/dev`, then remove it if creation succeeds;
- start shell commands and inspect process, mount, property, and service output.

Other checks connect to local Scene/Frida loopback ports, bind the SDK services, and start dedicated or isolated processes. These actions can appear in EDR, audit, package, network, filesystem, and process telemetry.

Cleanup is best-effort. A crash, force-stop, timeout, or process kill can interrupt it.

### 4.5 Performance and cancellation

The facade launches KKND root, optional KKND hardware, and all sixteen Duck repositories concurrently. Duck TEE then fans out many additional Keystore probes. The timing side-channel path performs about 500 paired Binder/Keystore samples, and the pruning path occupies 18 operation slots.

Version `0.1.0` has:

- no built-in total timeout;
- no progress API;
- no scan mutex;
- no dispatcher/executor injection;
- shared use of `Dispatchers.Default` and `Dispatchers.IO`;
- blocking JNI, process, socket, Binder, and Keystore work that cannot be preempted immediately.

Caller cancellation and `withTimeout` limit how long the host waits, but underlying blocking work can continue briefly. Some implementation `runCatching`/`catch(Throwable)` paths can also delay cancellation propagation.

### 4.6 Required host operating rules

- Run only one scan at a time across the process.
- Do not scan during cold start, login, payment, signing, key rotation, or any host Keystore operation.
- Prefer an explicit user/admin action or a controlled background maintenance window.
- Validate side effects with the host security/EDR team before rollout.
- Cache the host's derived decision instead of scanning repeatedly.
- Test process death and inspect for leftover aliases/files.
- Disable all Duck features on any host that uses Soter until the defect is fixed.

## 5. Basic Kotlin usage

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
            includeDuckFeatures = true, // Must be false for Soter-using hosts on v0.1.0.
        ),
    )
}
```

Option semantics:

| Option | Behavior |
| --- | --- |
| `includeHardware=true` | Adds `kkndHardware`; it does not control Duck `tee` |
| `includeHardware=false` | Skips only KKND hardware checks |
| `includeDuckFeatures=true` | Runs all sixteen Duck repositories |
| `includeDuckFeatures=false` | Returns an empty `duckReports` map |

KKND root checks always run.

## 6. Lifecycle, serialization, timeout, and UI state

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

## 7. Result model

### 7.1 `CRoootScanResult`

| Field | Meaning |
| --- | --- |
| `kkndRoot` | Always-present KKND root/integrity evidence |
| `kkndHardware` | Optional KKND hardware/TEE/verified-boot evidence |
| `duckReports` | Fixed-key map of Duck domain reports, or empty when disabled |
| `durationMs` | Overall wall-clock delta using `System.currentTimeMillis()` |

`durationMs` is diagnostic, not a monotonic benchmark. System clock changes can affect it. `kkndRoot.scanDurationMs` and `kkndHardware.scanDurationMs` are measured from the same overall start time to their respective await points; they are not isolated engine timings.

### 7.2 KKND root results

`kkndRoot.items` contains `DetectionItem` values:

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

Summary semantics:

- `isRooted`: at least one `detected && severity == HIGH` item;
- `isSuspicious`: at least one detected item at any severity;
- `detectedCount`, `highRiskCount`, and `warningCount`: convenience counts.

Do not discard individual items after reading a summary Boolean.

### 7.3 KKND hardware results

`kkndHardware?.items` contains `HwCheckItem` values with `group`, `status`, `value`, `expected`, and `detail`.

`overallOk` means there are no `FAIL` or `WARN` items. `UNKNOWN` does not make it false, so `overallOk=true` is not proof of hardware trust or complete coverage.

### 7.4 Duck report keys and types

Keys are case-sensitive. Values are declared as `Any?`; use safe casts.

| Key | Domain type | Important fields |
| --- | --- | --- |
| `bootloader` | `BootloaderReport` | `stage`, `state`, attestation/availability, `findings`, `methods`, `errorMessage` |
| `customRom` | `CustomRomReport` | `packageVisibility`, `detectedRoms`, finding groups, availability |
| `dangerousApps` | `DangerousAppsReport` | visibility, `findings`, `hiddenFromPackageManager`, `issues` |
| `deviceInfo` | `DeviceInfoReport` | `sections`, `totalCount`, `errorMessage` |
| `kernel` | `KernelCheckReport` | danger/info findings, CVE patch state, `nativeAvailable` |
| `lsposed` | `LSPosedReport` | availability flags, `signals`, `methods`, hit counts |
| `memory` | `MemoryReport` | hook/executable-memory flags, `findings`, `methods` |
| `mount` | `MountReport` | readability, early-preload availability, findings, impacts |
| `nativeRoot` | `NativeRootReport` | root-family flags, availability/counts, findings, methods |
| `playIntegrityFix` | `PlayIntegrityFixReport` | property/consistency/native signals and availability |
| `selinux` | `SelinuxReport` | `mode`, `paradoxDetected`, methods, policy/audit analysis |
| `su` | `SuReport` | binaries, daemons, process context, methods |
| `systemProperties` | `SystemPropertiesReport` | danger/info signals, source counts, property-area availability |
| `tee` | `TeeReport` | `stage`, `verdict`, `tier`, score, signals, sections, network/failure state |
| `virtualization` | `VirtualizationReport` | process/native/ASM availability, counts, signals, impacts |
| `zygisk` | `ZygiskReport` | availability, strong/heuristic hits, signals, methods |

Concrete classes live under `com.eltavine.duckdetector.features.<feature>.domain`.

Example:

```kotlin
import com.eltavine.duckdetector.features.tee.domain.TeeReport
import com.eltavine.duckdetector.features.tee.domain.TeeScanStage

val tee = result.duckReports["tee"] as? TeeReport

val teeSummary = when {
    tee == null -> "TEE report unavailable"
    tee.stage != TeeScanStage.READY -> "TEE failed: ${tee.failureMessage}"
    else -> "TEE verdict=${tee.verdict}, tier=${tee.tier}, signals=${tee.signals.size}"
}
```

`READY` means aggregation completed, not that a report is clean or fully covered. A READY report can still contain an `errorMessage`, fallback evidence, restricted visibility, unreadable files, or unavailable native methods. Inspect the report's stage, error/failure text, availability/readability/visibility flags, and method outcomes together.

Do not persist or transmit `duckReports` by blindly serializing `Any?`. Define a host-owned DTO and explicitly select fields whose compatibility and privacy you accept.

## 8. Failure model

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

## 9. Java applications

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

## 10. R8, package size, and duplicate classes

The AAR bundles consumer rules that keep:

```text
com.chloemlla.crooot.CRoootSdk
com.eltavine.duckdetector.**
com.juanma0511.rootdetector.**
```

Normally, no additional keep rules are required. The conservative rules also mean most SDK bytecode cannot be removed or obfuscated. `includeHardware` and `includeDuckFeatures` change runtime work only; they do not materially reduce packaged code size.

Do not simultaneously package the original Duck/KKND artifacts or copied source trees. The CRooot AAR already contains those namespaces, services, native symbols, and resources; duplication can cause class, manifest, or JNI conflicts.

## 11. Privacy and data handling

The stable fresh-install path performs no remote HTTPS refresh, but a scan can expose sensitive diagnostic data to the host process, including:

- installed/visible package names;
- system properties, build fields, mounts, paths, processes, and service names;
- Android Keystore and attestation certificate details;
- device and runtime environment information;
- explicit evidence strings produced by native and shell probes.

Minimize collection and retention. Redact before logs or telemetry, obtain any required user/admin consent, and never upload raw reports merely because the SDK itself is local-first.

The explicit Scene broadcast and loopback connections are local interactions, but they are still observable active behavior and should be included in privacy/security review.

## 12. Testing and rollout checklist

Before production rollout:

- verify the merged manifest and permissions;
- verify the application does not use Soter, or disable all Duck features;
- search the host for the three reserved KKND aliases;
- test one-scan-at-a-time enforcement;
- test timeout, cancellation, process death, and cleanup;
- test with and without package visibility;
- test offline and local-loopback permission behavior;
- inspect restricted/unavailable states instead of treating them as clean;
- test stock and modified devices across representative OEMs and Android releases;
- test all shipped ABIs, especially non-arm64 reduced assembly coverage;
- validate CPU, I/O, battery, process, and Keystore impact;
- notify SOC/EDR owners about active broadcasts, files, sockets, hooks, and processes.

Current repository CI runs `:sdk:lintRelease :sdk:assembleRelease`. It validates static build/package readiness only. There are no SDK unit/instrumentation test trees and no published device-matrix accuracy result.

## 13. Upgrade policy

- Pin an exact SDK version.
- Re-read both guides and inspect source/API diffs before upgrading.
- Diff the merged manifest and bundled consumer rules.
- Re-test side effects, fixed aliases, Soter behavior, and permissions.
- Continue using safe casts and tolerate unknown Duck keys.
- Treat implementation-package classes as unstable even when Kotlin visibility currently exposes them.
- Re-run the device/OEM/ABI rollout matrix.

## 14. Troubleshooting

### `401` or `403` from GitHub Packages

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

This is expected on TEE-heavy devices. Serialize scans, move them out of interaction-critical paths, measure a suitable timeout, and remember that timeout does not immediately stop blocking work. `includeHardware=false` still runs Duck `tee`; only `includeDuckFeatures=false` skips it.

### Native or ASM support is unavailable

Check the device ABI and report availability fields. A packaged `.so` does not guarantee equal assembly coverage. Also verify the AAR was not replaced by `classes.jar`.

### No GitHub Release is visible

`v0.1.0` published GitHub Packages and an Actions artifact only. Use the Maven coordinate or build from source.

### Host uses Tencent Soter

Do not run Duck features in `0.1.0`. Set `includeDuckFeatures=false` until the destructive retry path is fixed.

## 15. License and redistribution

The artifact contains component-specific Apache-2.0 and MIT code. The published `0.1.0` POM lists only Apache-2.0, and the actual AAR does not embed `LICENSE`, `NOTICE`, or the retained MIT license.

When redistributing the AAR, source, or a derived product, include:

- [`../LICENSE`](../LICENSE)
- [`../NOTICE`](../NOTICE)
- [`../legacy/kknd-root-detector/LICENSE-MIT`](../legacy/kknd-root-detector/LICENSE-MIT)

The CI private-key marker check covers selected source extensions only and is not repository-wide secret scanning. Redistributors must perform their own comprehensive secret and artifact scan.

## 16. References

- [GitHub Packages: Apache Maven registry](https://docs.github.com/en/packages/working-with-a-github-packages-registry/working-with-the-apache-maven-registry)
- [Android package visibility declarations](https://developer.android.com/training/package-visibility/declaring)
- [`CRoootSdk.kt`](../sdk/src/main/java/com/chloemlla/crooot/CRoootSdk.kt)
- [`sdk/build.gradle.kts`](../sdk/build.gradle.kts)
- [`sdk/src/main/AndroidManifest.xml`](../sdk/src/main/AndroidManifest.xml)
- [`sdk/consumer-rules.pro`](../sdk/consumer-rules.pro)
