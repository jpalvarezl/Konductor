# Configuration

> **Status:** Stub — outline only. Author this for the hackathon. See [index.md](index.md).

**Purpose:** how Konductor is configured — environment variables, settings, and precedence.

## Environment variables

TODO: document each — `FOUNDRY_PROJECT_ENDPOINT`, `FOUNDRY_MODEL_NAME`, `FOUNDRY_AGENT_CONTAINER_IMAGE` (hosted),
plus any auth-related vars. Endpoint format: `https://{resource}.ai.azure.com/api/projects/{project}`.

## Authentication

TODO: `DefaultAzureCredential` (Entra ID); AAD scope `https://ai.azure.com/.default`; local dev sign-in.

## Settings

TODO: compaction (`enabled`, `reserveTokens`, `keepRecentTokens`), tool allowlists, provider/agent-kind selection,
thinking/temperature. Where settings live and precedence (CLI > env > file > defaults).

## References

- [index.md](index.md) · [providers.md](providers.md) · [compaction.md](compaction.md) · [hosted-agents.md](hosted-agents.md)
