---
id: I066
title: Minimal Foundry test-proxy foundation
issue: https://github.com/jpalvarezl/Konductor/issues/66
created: 2026-07-28
---

# I066 — Minimal Foundry Test-Proxy Foundation

Issue [#66](https://github.com/jpalvarezl/Konductor/issues/66) owns coordination and follow-up migration. This packet
covers only the smallest local Java-style record/playback proof.

## Outcome

Konductor can record one real Foundry request to a local JSON file, replay it deterministically by default, run the same
test live without recording, and mark unrelated service tests as live-only using Azure Core Test conventions.

## Scope

- Add test-scoped `azure-core-test` and the pinned test-proxy version marker required by the Java framework.
- Add a minimal `TestProxyTestBase` subclass for `AIProjectClientBuilder` in PLAYBACK, RECORD, and LIVE modes.
- Record one `getDeployment` call through `AzureFoundryDeploymentCatalog` to
  `src/test/resources/session-records/` and commit the sanitized JSON.
- Convert existing connection and PromptAgent smoke tests from `KONDUCTOR_LIVE_TESTS` to `@LiveOnly` and
  `AZURE_TEST_MODE=LIVE`.
- Document local commands and a manual recording review checklist.

## Non-goals

- CI or release workflow changes.
- Cross-platform proxy provisioning or CI-environment workarounds.
- External `assets.json`/recording repositories.
- Migrating connection or PromptAgent tests to record/playback.
- A general recording-security framework.
- Production client-construction changes.

## Acceptance

- [x] With `AZURE_TEST_MODE` unset, the explicit deployment IT replays the committed local recording.
- [x] RECORD mode calls the real Foundry project and writes the same predictable recording path.
- [x] LIVE mode calls the real service without reading or writing a recording.
- [x] The recording name is derived from stable camelCase class/method identifiers.
- [x] Manual review confirms the committed recording contains no token, credential, real host/project, deployment name,
  or other sensitive identifier.
- [x] Existing connection and PromptAgent live tests run only with `AZURE_TEST_MODE=LIVE` via `@LiveOnly`.
- [x] Ordinary unit tests, package, and documentation validation remain unchanged and pass.

## Context pack

### Required references

- [Java Azure Core Test README](https://github.com/Azure/azure-sdk-for-java/blob/main/sdk/core/azure-core-test/README.md)
- [Test-proxy README](https://github.com/Azure/azure-sdk-tools/blob/main/tools/test-proxy/Azure.Sdk.Tools.TestProxy/README.md)
- Azure Agents 2.2.0 `ClientTestBase.java` and `ResponsesTests.java` at SDK commit `ee28e96b521`.

### Source and tests

- `pom.xml`
- `eng/common/testproxy/target_version.txt`
- `src/test/kotlin/com/konductor/foundry/FoundryTestBase.kt`
- `src/main/kotlin/com/konductor/foundry/project/deployment/FoundryDeploymentCatalog.kt`
- `src/test/kotlin/com/konductor/foundry/project/deployment/AzureFoundryDeploymentCatalogTest.kt`
- Existing connection and PromptAgent `*LiveTest.kt` classes.

## Decisions and constraints

- Follow the Java framework directly: unset/invalid `AZURE_TEST_MODE` means PLAYBACK; RECORD adds the interceptor record
  policy; LIVE bypasses the proxy; `@LiveOnly` runs only in LIVE.
- Use local `src/test/resources/session-records/*.json`; do not add `assets.json`.
- Keep one non-parameterized camelCase test so the recording path is stable and readable.
- The recorded class follows the repository `*Test` naming convention and participates in ordinary local test runs.
  Linux CI proxy startup remains the separate #72 follow-up.
- Keep sanitizers specific to the one deployment operation and manually inspect the complete recording before commit.

## Validation

```bash
./mvnw -Dtest=AzureFoundryDeploymentCatalogTest test
AZURE_TEST_MODE=RECORD ./mvnw -Dtest=AzureFoundryDeploymentCatalogTest test
AZURE_TEST_MODE=LIVE ./mvnw -Dtest=AzureFoundryDeploymentCatalogTest test
./mvnw test
./mvnw package
node scripts/validate-docs.mjs
```

## Completion

Record the final PR and keep full deployment scenarios (#73), connection migration (#70), PromptAgent migration (#71),
and CI playback (#72) in focused child issues.
