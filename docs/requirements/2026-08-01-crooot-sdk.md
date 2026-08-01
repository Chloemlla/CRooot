# CRooot SDK Requirements

## Goal

Create a new Android project named `CRooot` that combines the detection implementations from `kknd_Root_Detector` and `Duck-Detector-Refactoring`, exposes a stable Android SDK API, and publishes release artifacts through GitHub Actions.

## Scope

- Preserve the Duck Detector Kotlin/native detector tree as the primary implementation base.
- Preserve the KKND Kotlin/Java/AIDL/native detector tree under a clearly attributed legacy namespace.
- Add an Android library module and a small public API that does not require the source applications' UI.
- Keep source and native code available for auditing and future refactoring.
- Declare all build dependencies in Gradle and CI; do not run a local build.
- Use `gh` to inspect workflows, trigger remote checks, and repair failing workflow definitions until remote checks pass or an external blocker is proven.

## Acceptance criteria

- `CRooot` exists as a standalone project with Gradle settings and an `sdk` Android library module.
- Both source trees' detector implementations are present, with MIT and Apache-2.0 notices retained.
- KKND and Duck native JNI remain callable from one shared `libchloemlla-crooot.so` library; the original JNI symbol package names remain unchanged for compatibility.
- Public SDK entry points return structured scan results and expose capability metadata.
- Maven publishing configuration and a tag/manual GitHub Actions release path exist.
- No local Gradle, CMake, or Android build command is run.
- Static checks and GitHub Actions results are recorded; completion claims distinguish code preparation from remote build success.

## Non-goals

- Do not silently rewrite or remove detector logic.
- Do not claim device-runtime accuracy without Android-device tests.
- Do not redistribute third-party DirtySepolicy-derived code without preserving or verifying its upstream notice.

## Constraints and assumptions

- The two input directories are source snapshots without usable Git history.
- Minimum supported Android API is 29 for the combined SDK because Duck's current implementation requires it.
- Maven publication uses GitHub Packages when credentials are available and supports local Maven publication configuration without requiring a local build.
