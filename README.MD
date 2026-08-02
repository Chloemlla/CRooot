<p align="center">
  <a href="https://github.com/Chloemlla/CRooot/actions/workflows/android-sdk-ci.yml"><img src="https://github.com/Chloemlla/CRooot/actions/workflows/android-sdk-ci.yml/badge.svg" alt="Android SDK CI"></a>
  <img src="https://img.shields.io/badge/version-0.1.0-blue" alt="SDK version 0.1.0">
  <img src="https://img.shields.io/badge/Android-10%2B-3DDC84?logo=android&logoColor=white" alt="Android 10 or newer">
  <a href="NOTICE"><img src="https://img.shields.io/badge/license-Apache--2.0%20%2B%20MIT-blue" alt="Apache License 2.0 and MIT"></a>
</p>

<p align="center">
  <strong><a href="README.MD">English</a></strong> · <strong><a href="README_ZH.MD">简体中文</a></strong>
</p>

# CRooot Android SDK

CRooot is a UI-independent Android security detection SDK. It combines KKND root and hardware checks with Duck detector reports behind one coroutine-based facade:

```kotlin
com.chloemlla.crooot.CRoootSdk
```

The SDK collects local, heuristic evidence for root, boot integrity, Android Keystore/TEE, runtime hooks, mount manipulation, SELinux, suspicious applications, virtualization, Zygisk, and related device-security signals. It does not require root access and does not claim that any single result proves a device trusted or compromised.

## Documentation

- [Complete English integration guide](docs/SDK_USAGE.md)
- [完整简体中文接入指南](docs/SDK_USAGE_ZH.md)
- [SDK documentation index](docs/README.md)
- [Public facade source](sdk/src/main/java/com/chloemlla/crooot/CRoootSdk.kt)

## SDK capabilities

- One `suspend` scan API with application-context ownership.
- Structured KKND root results and optional KKND hardware/verified-boot results.
- Sixteen Duck feature reports exposed through fixed, case-sensitive keys.
- Native probes packaged as `libchloemlla-crooot.so` for `arm64-v8a`, `armeabi-v7a`, `x86`, and `x86_64`; low-level assembly coverage differs by ABI.
- No Compose, Activity, Coil, or host UI requirement.
- Consumer R8/ProGuard rules bundled in the AAR.
- Stable third-party local report API (`scanReport`) with privacy-aware JSON/Text/HTML export
- Versioned report schema with normalized detector summaries, findings, limitations, and default redaction
- Report consumers can ignore unknown fields and enum values for additive upgrades

The conservative consumer rules keep both detector trees. Scan options reduce runtime work but do not materially reduce AAR/APK code size.

## Requirements

| Requirement | Value |
| --- | --- |
| Minimum Android version | Android 10 / API 29 |
| Consumer `compileSdk` | 36 or newer (`minCompileSdk=36` in the AAR) |
| Consumer `targetSdk` | Chosen by the host application |
| Primary API language | Kotlin; Java callers need a Kotlin coroutine bridge |
| Maven authentication | GitHub user plus a token with `read:packages` |

Consumers of the published AAR do not need the Android NDK or CMake. Building the SDK from source requires JDK 17, Android SDK/Build Tools 36.0.0, NDK 30.0.15729638, and CMake 4.1.2.

## Critical v0.1.0 Soter warning

The original Duck TEE Soter probe could remove or replace an existing Tencent Soter App Global Secure Key during its retry path. This SDK **fixes the retry path**: the probe no longer deletes a pre-existing key (it belongs to the host application). However, the probe still initializes the Soter Treble service and exercises biometric checks. Hosts that use Soter should validate on representative devices before production use.

For additional safety, v0.1.0 now supports **per-feature flags**: set `includeTee=false` to disable only the TEE report while keeping all other Duck features enabled. Previously the only option was `includeDuckFeatures=false` which disabled all 16 Duck reports.

## Install from GitHub Packages

Store credentials outside the repository, for example in the user-level `~/.gradle/gradle.properties`:

```properties
gpr.user=YOUR_GITHUB_USERNAME
gpr.key=YOUR_CLASSIC_PAT_WITH_READ_PACKAGES
```

Add the repositories to `settings.gradle.kts`. JitPack is required for the transitive Soter dependency and is restricted here to its exact group:

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

Add the dependency to the host module:

```kotlin
dependencies {
    implementation("com.chloemlla.crooot:crooot-sdk:0.1.0")
}
```

See the full guides for local-AAR and source-module integration.

## Minimal scan

```kotlin
import com.chloemlla.crooot.CRoootScanOptions
import com.chloemlla.crooot.CRoootSdk

val sdk = CRoootSdk.create(applicationContext)

val result = sdk.scan(
    CRoootScanOptions(
        includeHardware = true,
        includeDuckFeatures = true, // Per-feature flags available: includeTee, includeSelinux, etc.
    ),
)

val rooted = result.isRooted
val suspicious = result.isSuspicious
val hardware = result.kkndHardware
val teeReport = result.duckReports["tee"]
val logSummary = result.summary() // "CRooot scan completed in 3200ms; duckReports=16/16"
```

Call `scan` from a lifecycle-owned coroutine and handle cancellation, timeout, and exceptions at the host boundary. Run only one scan at a time: blocking native, process, and Keystore work may continue briefly after caller cancellation or timeout. `includeHardware=false` disables only `kkndHardware`; it does not disable `duckReports["tee"]`. Version 0.1.0 has no per-Duck-feature selector: `includeDuckFeatures=false` disables all sixteen Duck reports.

The fixed Duck keys are:

```text
bootloader, customRom, dangerousApps, deviceInfo, kernel, lsposed,
memory, mount, nativeRoot, playIntegrityFix, selinux, su,
systemProperties, tee, virtualization, zygisk
```

Values are typed as `Any?` by the facade, so consumers should use safe casts to the documented domain report types and tolerate unknown keys when upgrading.

## Manifest and permissions

The AAR merges five non-exported detector services and declares no `uses-permission` entries. The host remains responsible for every permission it chooses to add.

- Declare the normal `USE_BIOMETRIC` permission for reliable Duck TEE deep checks; on biometric-capable devices, omitting it can fail the complete `tee` report. Legacy `USE_FINGERPRINT` remains optional for older/Soter paths.
- `QUERY_ALL_PACKAGES` can improve package-inventory checks, but add it only when the application qualifies under distribution-store policy. Restricted visibility is reported as reduced coverage rather than a clean result.
- On a fresh install, the stable facade uses the embedded revocation snapshot and makes no remote HTTPS request. A previously persisted implementation-level online consent can change later scans; `INTERNET` also improves local loopback-port probe coverage.

Inspect the merged manifest in the host application and read the full guide before changing or removing SDK services.

Version `0.1.0` does not package the original application's app-zygote and early-preload launch wiring. Advanced SELinux app-zygote, DirtySepolicy, and early mount/virtualization paths can therefore report reduced or unavailable coverage in a normal AAR integration.

## Operational side effects

A full scan is local but not purely passive. Some probes send an explicit broadcast to a known third-party package, briefly create and remove probe paths/files, test loopback ports, and generate/delete AndroidKeyStore test keys. In particular, the KKND hardware checks reserve and delete these fixed aliases:

```text
rootdetector_tee_probe
rootdetector_ks_backing
rootdetector_sb_key
```

TEE deep checks can also temporarily hook the process Keystore Binder cache, widen hidden-API exemptions for the process, and occupy 18 concurrent signing-operation slots. The host must never use the fixed aliases above. Validate scan behavior on representative devices, serialize calls to `scan`, avoid every concurrent host Keystore/cryptographic operation, and expect temporary test aliases to remain if the process is terminated before cleanup completes. A full scan can take tens of seconds and can start child or isolated processes. Do not run it on cold start or during login, payment, signing, or key rotation.

The AAR contains the complete `com.eltavine.duckdetector.*` and `com.juanma0511.rootdetector.*` implementation trees. Do not package original Duck/KKND artifacts or copied detector sources beside CRooot; doing so can cause duplicate classes, manifest components, and native/JNI conflicts.

## Result policy

CRooot returns evidence, availability, severity, details, and support state. Integrators should:

- evaluate multiple independent signals instead of one Boolean;
- distinguish `FAILED`, `UNKNOWN`, `SUPPORT`, restricted, and unavailable states from clean states;
- keep detailed evidence for diagnostics while minimizing collection and retention of device data;
- validate thresholds on representative OEMs, Android releases, ABIs, emulators, and modified devices;
- avoid using the SDK as the sole basis for irreversible account or device actions.

## Build and verification

Build only the SDK module from source:

```bash
./gradlew :sdk:lintRelease :sdk:assembleRelease
```

The `v0.1.0` GitHub Actions release workflow successfully published the Maven artifact and uploaded an AAR workflow artifact. It did not create a GitHub Release. CI includes a limited private-key marker check for selected source extensions; it is not a substitute for repository-wide secret scanning.

## License and redistribution

CRooot integration and Duck detector code use the Apache License 2.0. Retained KKND code uses the MIT License, and DirtySepolicy-derived files retain their Apache 2.0 attribution. Redistributors must preserve the applicable notices.

The `v0.1.0` AAR does not embed the required notice files. Before shipping the AAR, source, or a derived product, include [LICENSE](LICENSE), [NOTICE](NOTICE), and [the retained KKND MIT license](legacy/kknd-root-detector/LICENSE-MIT) with the distribution.
