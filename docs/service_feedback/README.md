# Service feedback

Friction we hit while **dog-fooding** the Foundry / Azure SDKs to build Konductor. One file per service
feature. This is arguably the most valuable output of the exercise: concrete, reproducible SDK/service
rough edges with the workaround we used and a suggested fix, written for the SDK/service teams.

Keep entries **specific** — name the exact SDK type/method and version, describe the impact, and record the
workaround Konductor adopted so the note stays actionable even after the code moves on.

| Feature | File | SDK |
|---------|------|-----|
| Hosted agents (versions, sessions, log stream, agent-scoped Responses) | [hosted_agents.md](hosted_agents.md) | `com.azure:azure-ai-agents` 2.2.0 |

Add a new file when a distinct service feature accumulates feedback; add a row here.
