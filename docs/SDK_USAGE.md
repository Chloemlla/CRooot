# CRooot Android SDK

CRooot exposes a small, UI-independent facade over the KKND and Duck detector implementations.

## Dependency

Use the published SDK coordinates when a release is available:

```kotlin
implementation("com.chloemlla.crooot:crooot-sdk:0.1.0")
```

The current project configuration targets Android API 29 or newer.

The public SDK namespace is `com.chloemlla.crooot`, and the native artifact is `libchloemlla-crooot.so`.

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

## Release blockers

- The copied `sdk/src/main/java/com/juanma0511/rootdetector/zygote/DirtySepolicyService.java` and related files have unresolved upstream provenance and notice requirements. Do not redistribute source or binaries containing this implementation until its origin, copyright, and license notice are verified.
- The original Duck snapshot contained private marker-key material; CRooot removed it. Release checks must fail if any private-key material is reintroduced, and any originally exposed key must be rotated or revoked.

See [`../NOTICE`](../NOTICE) for the attribution and redistribution gate.
