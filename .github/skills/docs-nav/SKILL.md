---
name: docs-nav
description: Navigate the Konductor docs/ specification set — map a question to the authoritative spec/design doc and grep docs/ for specifics. Use when locating design/spec info (architecture, providers, tools, sessions, compaction, config, TUI, ACP, hosted agents), checking build/roadmap/status, or before reading Konductor source.
---

# Konductor docs navigation (docs-nav)

The canonical, harness-neutral navigation guide is **`docs/index.md`** — keep it the single source of truth (this
skill is a thin pointer so nothing duplicates; the same guide is reachable via `AGENTS.md` in any harness).

**Do this:**

1. Open **`docs/index.md`** → use its **Documentation map** table to pick the authoritative doc for the question,
   and its **"Finding things fast"** section for the orientation read-order and search recipes.
2. The corpus is small and precisely termed, so keyword-search beats reading whole files:

   ```bash
   rg -in "<term>"    docs/     # where a term / symbol is specified
   rg -il "<concept>" docs/     # which doc owns a concept (file names only)
   ```

3. Every doc opens with a one-line purpose statement — read the top of a file to confirm relevance.

**Remember:** `docs/` describes the **target** design (sketches, *not* committed code). Confirm what actually
exists by reading `src/` + `docs/burndown.md`.
