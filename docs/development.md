# Development

> **Status:** Stub — outline only. Author this for the hackathon. See [index.md](index.md).

**Purpose:** how to build, run, and debug Konductor locally against a Foundry project.

## Build & run

TODO: `mvn` (= `compile exec:java`); package a runnable jar with `mvn package`; JDK 21.

## Project layout

TODO: summarize `src/main/kotlin/com/konductor/{core,conversation,tui}` and where new provider/session/tool code lands.

## Point at a Foundry project

TODO: set `FOUNDRY_PROJECT_ENDPOINT` (+ `FOUNDRY_MODEL_NAME`, and `FOUNDRY_AGENT_CONTAINER_IMAGE` for hosted); sign in
for `DefaultAzureCredential`. See [configuration.md](configuration.md).

## Debugging

TODO: logging, running against a real vs mock provider, common auth/endpoint errors.

## References

- [index.md](index.md) · [configuration.md](configuration.md) · [architecture.md](architecture.md)
