# Service feedback

Friction we hit while **dog-fooding** the Foundry / Azure SDKs to build Konductor. One file per service
feature. This is arguably the most valuable output of the exercise: concrete, reproducible SDK/service
rough edges with the workaround we used and a suggested fix, written for the SDK/service teams.

Keep entries **specific** — name the exact SDK type/method and version, describe the impact, and record the
workaround Konductor adopted so the note stays actionable even after the code moves on.

| Feature | File | Evidence version(s) |
|---------|------|---------------------|
| Responses convenience clients | [responses_clients.md](responses_clients.md) | Agents 2.2.0; API rechecked 2.3.0 |
| Hosted agents | [hosted_agents.md](hosted_agents.md) | Agents 2.2.0; API + LIVE rechecked 2.3.0 |
| Persisted Prompt agents | [prompt_agents.md](prompt_agents.md) | Agents 2.2.0; API + LIVE rechecked 2.3.0 |
| Foundry project connections | [project_connections.md](project_connections.md) | Projects 2.2.0; API rechecked 2.3.0 |
| Java test-proxy standalone setup | [java_test_proxy.md](java_test_proxy.md) | `com.azure:azure-core-test` 1.27.0-beta.16 |

Add a new file when a distinct service feature accumulates feedback; add a row here.
