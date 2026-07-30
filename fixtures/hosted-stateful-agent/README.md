# Stateful Hosted lifecycle fixture

This is a minimal Azure AI Agent Server Responses container used only to prove Konductor's durable Hosted-session
isolation and reconnect behavior. It does not call a model.

## Protocol

The response text is deterministic:

| Input | Response |
|---|---|
| `remember <marker>` | `remembered <marker>` |
| `recall` before a marker is stored | `none` |
| `recall` after a marker is stored | `<marker>` |
| anything else | `error: expected 'remember <marker>' or 'recall'` |

The marker is stored at a single fixed path inside the Hosted session sandbox. There is deliberately no process-wide
map keyed by `agent_session_id`, caller ID, or any other caller-controlled value. Isolation therefore comes from
Foundry assigning distinct session sandboxes, while reconnect evidence comes from reattaching the same sandbox and
reading its existing file.

## Local protocol tests

The command parser and filesystem behavior use only the Python standard library:

```bash
python -m unittest -v test_marker_protocol.py
```

The tests use separate temporary sandbox directories to verify that fixture instances do not share state.

## Build and deployment handoff

Build from this directory:

```bash
docker build -t <registry>/konductor-hosted-stateful:<tag> .
```

Push that immutable tag to a registry readable by Azure AI Foundry, then set `FOUNDRY_AGENT_CONTAINER_IMAGE` to the
pushed image and choose a dedicated `KONDUCTOR_HOSTED_AGENT_NAME`. Run the live proof explicitly:

```bash
AZURE_TEST_MODE=LIVE ./mvnw -Dtest=HostedSessionLifecycleLiveTest test
```

Do not reuse the existing `responses-echo-agent`: it is stateless and cannot satisfy the marker assertions. Do not
record this resource in the work-resource index, I028 completion evidence, or Hosted service feedback until the
stateful live test has passed against the deployed image.

The pinned Agent Server package versions match the Hosted Responses fixture used by Azure Agents Java SDK 2.2.0 at
the I028 reference commit (`ee28e96b521`).
