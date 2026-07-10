---
name: docs-nav
description: Build the smallest useful Konductor context pack — route implementation work through the active iteration, stable design questions to the owning spec, and unscheduled ideas to the backlog. Use before reading Konductor docs/source or planning roadmap work.
---

# Konductor docs navigation (docs-nav)

The canonical, harness-neutral workflow lives in **`docs/index.md`** and **`docs/iterations/README.md`**. This skill
is a thin router; do not duplicate project status or implementation plans here.

**Do this:**

1. For implementation or current-status questions, open **`docs/iterations/index.md`**.
   - Read the one active or relevant ready iteration.
   - Follow only its linked spec headings, source entry points, tests, and targeted searches.
   - Do not scan all of `docs/` or `src/` unless the context pack is incomplete or contradicted by source.
2. For stable design questions outside a current iteration, open **`docs/index.md`**, choose the owning spec, and
   inspect its headings before reading a subsection:

   ```bash
   rg -il "<concept>" docs/spec docs/*.md
   rg -n "^#{1,3} " docs/spec/<owner>.md
   ```

3. For unscheduled ideas, use **`docs/future.md`**. When work is accepted, promote it into an iteration and remove
   the duplicated implementation plan from the backlog.
4. Use focused GitHub issues for defects and design discussion. Link issues from iterations rather than copying them.

**Remember:** `src/` and tests are implementation truth; `docs/spec/` is the intended contract;
`docs/iterations/` is current delivery; `docs/burndown.md` is foundations history.
