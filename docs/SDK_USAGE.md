# CRooot Android SDK

CRooot exposes a small, UI-independent facade over the KKND and Duck detector implementations.

## Dependency

Use the published SDK coordinates when a release is available:

```kotlin
implementation("com.chloemlla.crooot:crooot-sdk:0.1.0")
```

The current project configuration targets Android API 29 or newer.

The public SDK namespace is `com.chloemlla.crooot`, and the native artifact is `libchloemlla-crooot.so`.

The core artifact has no Compose, Coil, Activity, or UI dependency. The copied UI trees remain in the repository for audit and can be packaged as a separate host-owned layer when needed.

If the host enables package-wide app visibility checks, declare `QUERY_ALL_PACKAGES` in the host manifest only after confirming the Play policy requirement. The SDK does not inject permissions into the host manifest.

## Scan

Create the SDK with an Android `Context`, then call the suspend `scan` method from a coroutine:

```kotlin
val sdk = CRoootSdk.create(applicationContext)
val result = sdk.scan(
    CRoootScanOptions(
        includeHardware = true,
        includeDuckFeatures = true,
    ),
)

val rootItems = result.kkndRoot.items
val hardwareItems = result.kkndHardware?.items
val duckReports = result.duckReports
```

`CRoootScanResult` contains KKND root checks, optional KKND hardware checks, a map of Duck feature reports, and elapsed time in milliseconds. The scan is heuristic evidence, not a proof of device integrity; callers should present individual signals with their availability and limitations.

Set `includeHardware` or `includeDuckFeatures` to `false` when the host does not need that portion of the scan. The scan does not require root access, but device, OEM, ABI, and sandbox restrictions can reduce coverage.

## Resolved release notes

- The DirtySepolicy implementation has been traced to LSPosed/DirtySepolicy (Apache 2.0). See [`NOTICE`](../NOTICE) for full attribution.
- The original Duck snapshot private marker key has been removed from the source tree. CI workflows now scan for private-key material and fail if reintroduced. Any originally exposed key must be rotated or revoked outside this repository.

See [`../NOTICE`](../NOTICE) for the attribution and redistribution gate.
