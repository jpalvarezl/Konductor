---
name: docs-nav
description: Build the smallest useful Konductor context — route implementation through its GitHub issue and delivery packet, stable design questions to the owning spec, and planning questions to GitHub. Use before reading Konductor docs/source or planning roadmap work.
---

# Konductor docs navigation (docs-nav)

The canonical, harness-neutral workflow lives in **`docs/index.md`** and **`docs/iterations/README.md`**. This skill
is a thin router; do not duplicate project status or implementation plans here.

**Do this:**

1. For implementation work, open the referenced GitHub issue and linked **`docs/iterations/I###-*.md`** packet
   directly. Do not read `docs/iterations/index.md` first when the packet is already known.
   - The issue owns coordination, assignment, dependencies, blockers, and closure.
   - The packet owns scope, non-goals, acceptance, exact context, constraints, and validation.
   - Follow only its linked spec headings, source symbols, tests, and targeted searches.
   - If GitHub is unavailable, continue from the self-contained packet.
   - Do not scan all of `docs/` or `src/` unless the packet is incomplete or contradicted by source.
2. For roadmap, priority, or current-status questions, use open GitHub issues and milestones. Use
   **`docs/iterations/index.md`** only to locate versioned packets or historical evidence; it is not a status board.
3. For stable design questions outside a delivery packet, open **`docs/index.md`**, choose the owning spec, and
   inspect its headings before reading a subsection:

   ```bash
   rg -il "<concept>" docs/spec docs/*.md
   rg -n "^#{1,3} " docs/spec/<owner>.md
   ```

4. For unscheduled direction, use **`docs/future.md`**. Create a focused issue when an idea needs discussion or
   prioritization; create a delivery packet only after it is accepted and scoped.

**Remember:** `src/` and tests are implementation truth; `docs/spec/` is the intended contract; issues and milestones
own coordination/planning; `docs/iterations/` owns offline delivery contracts; `docs/burndown.md` is foundations
history.
