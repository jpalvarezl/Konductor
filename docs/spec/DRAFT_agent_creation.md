# DRAFT: Runtime Agent Creation & Switching

Status: Draft  
Last updated: 2026-07-09

## 1. Problem / Motivation
Konductor can *switch agents at runtime*, but today there is little-to-no infrastructure for **creating agents at runtime**. The system largely relies on sensible defaults and static configuration.

We want a first-class flow that:

- Lets users create agents interactively (prompt-driven) during a session.
- Supports both **prompt agents** (instant) and **hosted agents** (deployment may take time).
- Supports ACP-style template-driven creation where users can supply agent templates via a **YAML file path**.
- Produces a clear, validated **AgentSpec** that can be persisted and reused.

## 2. Goals
- Provide a consistent runtime agent creation UX across:
  - Local/prompt agents
  - Hosted agents
  - ACP template-based agents
- Define a minimal but extensible **AgentSpec** contract.
- Allow interactive prompting with:
  - sensible defaults
  - mandatory fields enforced
  - editing/confirmation before creation
- Handle hosted-agent provisioning asynchronously with progress/status.
- Store created agents so they can be switched to later in the same session and future sessions.

## 3. Non-Goals (for this draft)
- A complete hosted agent provider implementation (e.g., specific cloud APIs).
- Full-blown GUI builder for agents.
- A complete permission model / multi-tenant policy system.

## 4. Terminology
- **Prompt agent**: an agent created by specifying model/provider/system prompt/tools/etc. Assumed available immediately.
- **Hosted agent**: an agent whose runtime is deployed/provisioned (container/service) and later referenced by an endpoint/ID.
- **ACP agent**: an agent created from a template compatible with ACP (Agent Control Plane) flows. Template supplied inline or via YAML file.
- **AgentSpec**: the canonical configuration used to instantiate an agent.
- **Provisioning**: async creation/deployment of hosted agents.

## 5. User Experience (High-Level)
### 5.1 Entry Points
Agent creation should be available via at least one of:

- **Command**: `agent create` / `:agent create` in TUI/REPL
- **Interactive prompt**: shown when user asks to switch to an agent that does not exist
- **Config-driven**: user provides `--template <path>` or `--spec <path>`

### 5.2 Interactive Flow
1. User selects **type**: `prompt`, `hosted`, `acp-template`.
2. Konductor displays a **minimal form** with defaults.
3. User edits fields (inline editing, step-by-step prompts, or opens an editor).
4. Konductor validates required fields and shows a **summary**.
5. User confirms.
6. Konductor creates agent:
   - Prompt agent: created immediately.
   - Hosted agent: kicks off provisioning, shows status, and allows user to continue.
   - ACP-template: resolves template into an AgentSpec, then proceeds as prompt or hosted depending on template.

### 5.3 Sensible Defaults
Defaults should be derived from:

1. Current session agent (clone-ish behavior)
2. Global configuration (e.g., default provider/model)
3. Repo defaults (if present)

A recommended interaction:
- Offer: **Create from scratch** vs **Clone current agent** (and then edit).

## 6. AgentSpec (Conceptual)
AgentSpec is the structured representation the system can persist and instantiate.

### 6.1 Minimal Shape (Grounded in Current Code)
Konductor’s current runtime “agent creation” surface is split across two seams:

- **Persisted PromptAgents** (M2.5) via [`PromptAgentClient.createAgentVersion(name, model, instructions, tools)`]
  - This mints a new *version* of a PromptAgent in the backing service.
- **Hosted agents** via [`HostedAgentClient.selectOrCreateAgentVersion(agentName, containerImage)`], then sessions.

Because of that, the current **minimum viable “creation spec”** is smaller than a generic AgentSpec.

#### PromptAgent (persisted) creation inputs (today)
```yaml
kind: prompt
name: "konductor-myproj"          # required (service-legal name)
model: "gpt-4.1"                  # required
instructions: |-
  You are ...                       # required
tools:                              # required (can be empty list)
  - name: "functions.read"         # shape corresponds to ToolSpec
```

#### Hosted agent creation inputs (today)
```yaml
kind: hosted
agentName: "konductor-myproj"      # required
containerImage: "ghcr.io/...:tag"   # required
# Note: endpoint is configured/selected via client methods, not provided directly in a spec.
```

> The richer cross-provider `AgentSpec` shown elsewhere in this document is a **target design** for unifying
> creation across backends. It should be implemented as a compiler that lowers into the concrete calls above.

### 6.2 Required Fields (Today vs Target)
#### Today (ground truth in code)
- **Persisted PromptAgent version** (`PromptAgentClient.createAgentVersion`)
  - `name`
  - `model`
  - `instructions`
  - `tools` (list; can be empty)

- **Hosted agent version** (`HostedAgentClient.selectOrCreateAgentVersion`)
  - `agentName`
  - `containerImage`

#### Target (unified runtime creation)
When we introduce a unified `AgentSpec`, required fields will be validated by kind:

- **Prompt agent (unified spec)**
  - `name`
  - `kind=prompt`
  - `model`
  - `instructions`
  - `tools`

- **Hosted agent (unified spec)**
  - `name`
  - `kind=hosted`
  - `containerImage` OR `templateRef` that resolves to one

- **ACP-template agent**
  - `templateRef` (path) OR `rawTemplate` (inline)
  - Template must compile into either the prompt or hosted creation inputs above.

## 7. Hosted Agent Provisioning
Hosted agents require an asynchronous provisioning workflow.

### 7.1 Provisioning Inputs (Minimal)
- Provider / platform (e.g., `acp`, `kubernetes`, `fly`, etc.)
- Deployment name (default to sanitized agent name)
- Region (optional)
- Resource sizing preset (optional; `small|medium|large`)
- Image/template reference OR agent build context

### 7.2 Lifecycle States
- `draft` (spec captured, not submitted)
- `provisioning` (deployment requested)
- `ready` (endpoint available)
- `failed` (include reason, retry token)
- `deleted`

### 7.3 UX Requirements
- Non-blocking by default:
  - Show: *“Hosted agent provisioning started (id=...). You can continue; use `agent status <id>`.”*
- Optional blocking mode:
  - `agent create --wait` waits until `ready` or `failed`.
- Progress reporting:
  - Polling (MVP)
  - Events/streaming later

### 7.4 Output Artifacts
On `ready`, Konductor should persist:
- `runtime.endpoint`
- any `authRef`/connection metadata
- a stable agent identifier

## 8. ACP Template Support (YAML)
Users should be able to provide templates via a file path.

### 8.1 CLI / Command Examples
- `agent create --type acp-template --template ./agents/my_agent.yaml`
- `agent create --template ./agents/my_agent.yaml` (type inferred)

### 8.2 Template Resolution
- Load YAML from path
- Validate it matches expected ACP schema (or a subset)
- Compile into an AgentSpec
  - If template indicates hosted deployment: produce hosted AgentSpec + provisioning request
  - Else: produce prompt AgentSpec

### 8.3 Precedence Rules
If both interactive fields and template provide values:
1. Explicit CLI flags override
2. User interactive edits override template defaults
3. Template defaults override global defaults

## 9. Persistence & Discovery
### 9.1 Where Agents Live
Define an “agent registry” location (MVP):

- User-level config directory (recommended)
  - e.g., `~/.konductor/agents/*.yaml`
- Optional project-level registry
  - e.g., `.konductor/agents/*.yaml`

### 9.2 Agent Identity
- `id` should be stable and unique.
- `name` is human-friendly and can be non-unique, but UI should disambiguate.

### 9.3 Switching Agents
`agent switch <id|name>` should:
- Resolve from registry
- If hosted and not ready: offer to wait or switch later
- Set current session agent context

## 10. Validation & Safety
- Validate required fields before creation.
- Validate tool policies to avoid enabling dangerous tools unintentionally.
- For hosted provisioning: confirm potential costs (optional warning banner).

## 11. Suggested Commands / TUI Actions (MVP)
- `agent create` (interactive)
- `agent create --type prompt --name ... --model ...`
- `agent create --type hosted --name ... --platform acp --region ...`
- `agent create --template path/to/agent.yaml`
- `agent list`
- `agent show <id>`
- `agent status <id>`
- `agent switch <id|name>`
- `agent delete <id>` (with hosted deprovision option)

## 12. Implementation Sketch (Phased)
### Phase 1: Prompt Agents (local)
- Define AgentSpec structure and registry persistence.
- Implement interactive form + validation.
- Implement `agent create`, `agent list`, `agent switch`.

### Phase 2: Hosted Agents (async provisioning)
- Add provisioning state machine + persistence updates.
- Add `agent status`, `--wait`.
- Stub provider interface for provisioning backends.

### Phase 3: ACP Templates
- Add YAML template loader.
- Add compiler from ACP template → AgentSpec.
- Add precedence/override rules.

## 13. Open Questions
- What is the canonical AgentSpec schema location? (existing config vs new)
- Do we allow per-agent tool enablement overrides or only global policy?
- How do we store secrets (`authRef`) securely on Windows/macOS/Linux?
- Should cloning current agent be the default path?
- Do we need an “agent builder” that can be scripted (non-interactive) for CI?
