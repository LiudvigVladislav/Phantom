# `docs/project/` — public project planning

This directory holds the **public-facing** project planning artefacts for PHANTOM. Anything tracked here is intended to be read by external contributors, security researchers, grant reviewers, and anyone else evaluating the project at the planning / governance layer.

## What lives here

| File | What |
|---|---|
| [`MASTER_TIMELINE_2026.md`](MASTER_TIMELINE_2026.md) | Single source of truth for track status (Reliability / Security / Grant-Readiness / Alpha-2 Features). Updated on every meaningful merge. |
| [`PHANTOM_ROADMAP_2026.md`](PHANTOM_ROADMAP_2026.md) | Formal product roadmap — phases, milestones, scope boundaries. |
| [`Roadmap_2.0_to_Execution_Map.md`](Roadmap_2.0_to_Execution_Map.md) | Mapping of the strategic roadmap onto operational engineering phases. |
| [`Alpha0_Milestone.md`](Alpha0_Milestone.md) | Alpha-0 scope and acceptance criteria (historical, preserved). |
| [`Alpha2_Migration.md`](Alpha2_Migration.md) | Migration spec for the Alpha-1 → Alpha-2 cutover. |
| [`Monorepo_Structure.md`](Monorepo_Structure.md) | Top-level repo layout: KMP shared modules, Android app, Rust relay, deploy configs. |
| [`DECISIONS_LOG.md`](DECISIONS_LOG.md) | Append-only governance log: every product / architectural decision with date, rationale, and cross-references to ADRs / PRs / commits. |
| [`ARCHITECTURAL_DECISIONS_TODO.md`](ARCHITECTURAL_DECISIONS_TODO.md) | Queue of pending architectural decisions and the ADR template. Discipline-level documentation, not scratchpad. |
| [`PROJECT_STATUS_SNAPSHOT_2026_05_09.md`](PROJECT_STATUS_SNAPSHOT_2026_05_09.md) | Point-in-time status snapshot (May 2026). Preserved as a historical baseline. |

## Related docs elsewhere in the repo

- [`docs/PROJECT_LOG.md`](../PROJECT_LOG.md) — reverse-chronological session journal. Records what was done and decided in each working session. Tracked publicly.
- [`docs/adr/`](../adr/) — formal Architectural Decision Records (ADR-001 through ADR-027). Each is a 1–3-page document fixing one decision.
- [`docs/threat-model/`](../threat-model/) — security threat model.
- [`docs/research/`](../research/) — research notes that produced ADRs (Tor feasibility, transport investigations, Xray REALITY integration, etc.).
- [`KNOWN_ISSUES.md`](../../KNOWN_ISSUES.md) at the repo root — open issues by ID with status, root-cause analysis, and mitigation notes.

## What does **NOT** live here

Sprint-level execution detail (per-week planning, daily working notes, in-flight task lists, agent-prompt scratch, in-progress execution plans) is kept **local-only** in `docs/internal/`, which is listed in `.gitignore`. The split exists so this public directory shows the planning surface a contributor or reviewer needs to evaluate the project, without raw daily scratchpad noise.

If you maintain a fork and need an internal-planning analogue, create your own `docs/internal/` locally — it stays out of git automatically.
