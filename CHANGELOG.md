# Changelog

All notable changes to the **TR Dependency Analyser** are documented here.

The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

---

## [Unreleased]

### Added
- Project operating instructions ([CLAUDE.md](CLAUDE.md)) covering role,
  anti-hallucination rules, standing GitHub-push workflow, OOP+SOLID
  design philosophy, and continuity-file rules.
- Continuity files [SESSION_SUMMARY.md](SESSION_SUMMARY.md) and
  [FEEDBACK_LOG.md](FEEDBACK_LOG.md).
- Sandbox repo `Mayur175/tr-dependency-analyser-v2` (full template tree
  scaffold). Production install URL remains the v1 repo.
- Standard repo files: `LICENSE` (Apache-2.0), `CHANGELOG.md`,
  `CONTRIBUTING.md`, `SECURITY.md`, `CODEOWNERS`, `.editorconfig`,
  `.gitattributes`, `.env.example`.
- Full template directory tree under `docs/`, `scripts/`, `systems/`,
  `applications/`, `services/`, `shared/`, `database/`, `transports/`,
  `integrations/`, `models/`, `prompts/`, `architecture/`,
  `performance/`, `security/`, `testing/`, `quality/`, `evaluation/`,
  `governance/`, `observability/`, `logs/`, `journal/`, `lessons/`,
  `context/`, `knowledge/`, `events/`, `notebooks/`, `infra/`,
  `meta-learning/`, and `.claude/`. Most directories contain only a
  scaffold `README.md` and are awaiting real content.

### Notes
- The full template tree is **scaffolding only** — most directories
  contain placeholder READMEs and have no real content yet. Sections
  will be populated as the corresponding workstream lands.

---

## [1.0.0] — 2026-06-19

### Added
- Single-TR and multi-TR/multi-task dependency analysis pipeline:
  Stage 1 inventory, Stage 2 dependency extraction, Stage 2b conflict
  detection, Stage 3 cluster detection (Union-Find), Stage 4 output.
- Standard ABAP backend ([abap/](abap/)) using classic CTS / DDIC tables
  (`E070`, `E071`, `SEOMETAREL`, `DD03L`, `DD04L`, `TFDIR`).
- ABAP for Cloud Development backend ([abap_cloud/](abap_cloud/)) with
  the same public API surface.
- ICF endpoint `/sap/bc/zgcts/analyze` with `?tr=`, `format=`, `persist=`,
  `external=` query parameters; identical contract on both backends.
- Persistent audit table `ZGCTS_HIST`.
- ATC integration class `ZCL_GCTS_DEP_ATC_CHECK`.
- Eclipse ADT plugin (`com.gmw.gcts.analyzer`): right-click context
  menu, top-level menu, toolbar button, `Ctrl+Alt+G` / `Cmd+Alt+G`
  keybinding, results view, preferences page, CSV export, `Check for
  Updates` handler.
- Three install methods: P2 update site (GitHub Pages), local archive
  ZIP, dropins JAR — modeled on `abap-cleaner`.
- abapGit packaging (`.abapgit.xml` + per-object metadata).
- Eclipse plugin `Cloud mode` toggle (POST + JSON for BTP HTTP Service
  binding).

### Security
- ICF handler ships with `AUTHORITY-CHECK` defaulting to **disabled**
  for sandbox/pilot use only. The handler emits `X-Auth-Bypass: yes` on
  every response so monitoring can detect the open posture. **Must be
  flipped to enabled before any non-sandbox deployment.** See
  [SECURITY.md](SECURITY.md) and the source-level comment in
  [zgcts_analyze_handler.clas.abap](abap/src/zgcts_analyze_handler.clas.abap).

---

[Unreleased]: https://github.com/Mayur175/tr-dependency-analyser/compare/v1.0.0...HEAD
[1.0.0]: https://github.com/Mayur175/tr-dependency-analyser/releases/tag/v1.0.0
