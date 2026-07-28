# Java Azure Core Test / test-proxy

Konductor adopted `com.azure:azure-core-test:1.27.0-beta.16` with Azure SDK Tools test-proxy version
`1.0.0-dev.20260521.2` for local Foundry record/playback tests.

## Standalone repositories require an Azure-style `eng` marker

**API:** `TestProxyTestBase` / `TestProxyManager.startProxy()` in `azure-core-test` 1.27.0-beta.16.

**Impact:** the Java framework discovers the repository root and proxy version by walking to
`eng/common/testproxy/target_version.txt`. This requirement is implicit in implementation and easy to miss outside the
Azure SDK monorepo; without it, an otherwise valid standalone test cannot start playback or recording.

**Workaround:** Konductor carries only `eng/common/testproxy/target_version.txt`. It stores recordings directly under
`src/test/resources/session-records/` and deliberately has no `assets.json`, so the framework uses local JSON files.

**Suggested fix:** document the required standalone directory contract in the Java Azure Core Test README or expose an
explicit test-proxy version/storage-root configuration API that does not depend on Azure monorepo layout.
