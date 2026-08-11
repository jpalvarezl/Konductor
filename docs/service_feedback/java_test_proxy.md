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

## Test HTTP clients can mediate production OkHttp without Okio

**API:** the `azure-core-test` 1.27.0-beta.16 dependency on `azure-core-http-okhttp` 1.13.5.

**Impact:** Maven selected the nearer test-framework OkHttp path while resolving openai-java 4.14.0, but kept that
path's `okio-jvm` test-scoped. Tests passed because their classpath contained Okio; `./mvnw` failed at runtime with
`NoClassDefFoundError: okio/Buffer` while `AgentsClientBuilder.buildOpenAIClient()` initialized OkHttp.

**Workaround:** exclude `azure-core-http-okhttp` from the test-framework dependency. Konductor's recorded tests use the
proxy playback client, while the production openai-java dependency then supplies matching runtime OkHttp/Okio artifacts.
CI also checks the shaded jar for `okio/Buffer.class`.

**2.3.0 recheck:** the exclusion remains necessary with `azure-ai-agents` 2.3.0 / openai-java 4.45.0. Playback and
shaded-jar validation still retain `okio/Buffer`; openai-java no longer brings OkHttp's logging interceptor, but the
production client remains OkHttp/Okio-backed.

**Suggested fix:** avoid exporting optional HTTP-client implementations transitively from `azure-core-test`, or ensure
they cannot mediate a consuming application's production runtime graph. Document exclusions for standalone projects.
