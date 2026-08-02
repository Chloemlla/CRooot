# CRooot SDK documentation

Choose a third-party Android integration guide:

- [English SDK guide](SDK_USAGE.md)
- [简体中文 SDK 接入指南](SDK_USAGE_ZH.md)

Both guides cover GitHub Packages authentication, Maven and local-AAR integration, manifest and package-visibility considerations, coroutine usage, result interpretation, all Duck report keys, failure handling, active scan side effects, Soter safety, performance, benchmarking, CI/CD integration, migration paths, security decision framework, privacy, R8, testing, upgrades, and troubleshooting.

**New in this version:**
- Quick start guide (5-minute setup)
- Architecture overview with diagram
- Complete per-feature Duck flag reference
- Security decision framework with decision levels
- Migration guide from original Duck/KKND projects
- Performance benchmarking methodology
- CI/CD integration examples
- Release notes / changelog
- Stable third-party local reports with privacy-labelled evidence and JSON/Text/HTML export
- Permission dependency map

The public facade is implemented in [`CRoootSdk.kt`](../sdk/src/main/java/com/chloemlla/crooot/CRoootSdk.kt). Review [`NOTICE`](../NOTICE) before redistributing the SDK.
