# CRooot SDK Execution Plan

## Internal grade

XL: sequential waves with bounded parallel inventory and review tasks. Write scopes are disjoint for delegated work.

## Waves

1. Inventory and governance: inspect both source trees, licenses, JNI entry points, and existing workflows.
2. Source integration: copy detector implementations and add the SDK facade and native target wiring.
3. Packaging: configure the Android library, declarative dependencies, Maven publishing, README, notices, and examples.
4. Remote verification: create/inspect the GitHub repository with `gh`, dispatch Actions, inspect failures, and patch workflow/configuration.
5. Cleanup: remove temporary receipts, record static verification, and report any remote blockers.

## Ownership boundaries

- Root lane: project structure, requirements, plan, integration, final verification, and delivery truth.
- Inventory agents: read-only source and CI audits.
- Integration lane: SDK facade and Gradle module files.
- CI lane: workflow definitions and `gh` diagnostics.

## Verification

- Static: file counts, JNI symbol/path consistency, Gradle configuration inspection, YAML parsing where available, and license/notice presence.
- Remote: `gh workflow list`, `gh workflow run`, `gh run watch`, and `gh run view --log-failed`.
- Forbidden locally: `gradlew`, `cmake`, Android Studio build, or any local native/Android compilation.

## Rollback

Preserve all source snapshots. If a merge conflict appears, isolate the conflicting target rather than deleting either implementation. Remote workflow edits remain in the new repository branch and can be reverted by commit.

## Completion language

Claim SDK preparation only after static checks. Claim Actions success only after `gh` reports successful remote runs for the relevant workflows. If repository creation, push, secrets, or runner availability blocks verification, report that explicitly.
