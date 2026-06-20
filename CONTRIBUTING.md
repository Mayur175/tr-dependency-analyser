# Contributing to TR Dependency Analyser

Thank you for considering a contribution. This is an open-source SAP
tooling project; the process below keeps changes safe to land.

---

## Project conventions (read first)

Before opening a PR, please read:

- [README.md](README.md) — project overview, install methods, API surface.
- [CLAUDE.md](CLAUDE.md) — operating instructions; the **anti-hallucination
  rules (§2)** and **OOP + SOLID design philosophy (§4.0)** apply to
  *all* contributions, AI-assisted or not.
- [docs/architecture/](docs/architecture/) — `SOLUTION_ARCHITECTURE.md` and
  `ARCHITECT_REVIEW.md` are the authoritative design documents.
- [SECURITY.md](SECURITY.md) — security posture, especially the deliberate
  `AUTHORITY-CHECK = FALSE` default in the ICF handler.

---

## Repository layout

| Path | What lives here |
|---|---|
| [abap/](abap/) | Standard ABAP backend (on-prem / S/4 Private Cloud) |
| [abap_cloud/](abap_cloud/) | ABAP for Cloud Development backend (Public Cloud / BTP ABAP env) |
| [eclipse/](eclipse/) | Eclipse ADT plugin (Tycho + OSGi) |
| [verification/](verification/) | Smoke tests / curl scripts |
| [docs/](docs/) | Human-readable architecture + deployment + plan documents |
| [.claude/](.claude/) | Project-scoped Claude Code config (commands, agents, rules) |

Many other top-level directories exist as **template scaffolding** awaiting
real content. They are intentional placeholders; do not delete them
without discussion, but also do not add empty noise.

---

## Build & test

### Eclipse plugin (Java / Tycho / Maven)

```bash
cd eclipse
mvn clean package -DskipTests
```

Output:
- `eclipse/com.gmw.gcts.analyzer/target/com.gmw.gcts.analyzer-*.jar`
- `eclipse/com.gmw.gcts.analyzer.updatesite/target/repository/`

### ABAP backend

Install via [abapGit](https://docs.abapgit.org). The `.abapgit.xml` lives
in `abap/` (Standard ABAP) or `abap_cloud/` (Cloud) — pick the one that
matches your target landscape.

For the SICF service activation step (outside abapGit's scope), see
[`abap/docs/SICF_SETUP.md`](abap/docs/SICF_SETUP.md).

---

## Branching & commits

- **Default branch:** `main`. Direct pushes are permitted for the
  maintainer; external contributors should open a PR from a feature
  branch named `feature/<short-topic>` or `fix/<issue-number>`.
- **Commit messages:** match the existing `git log` style — one-line
  subject capped at ~72 chars, blank line, body explaining *why*.
  Example: `Eclipse plugin: add Cloud mode (POST + JSON) for BTP HTTP
  Service binding`.

---

## Code style

| Surface | Rules |
|---|---|
| **ABAP (Standard)** | Match the surrounding files in [abap/src/](abap/src/). `@`-escaped host vars in OpenSQL. `VALUE #(...)` constructor expressions where readable. |
| **ABAP for Cloud Development** | Strict allow-list. **No classic-table SELECTs.** Avoid `lines( )` inside expressions (parser quirk on some SP levels — see header of `zcl_gcts_tr_analyzer_cloud`); use a manual `LOOP-AT` counter. Use `\|{ lv_int }\|` template literals. |
| **Java (Eclipse plugin)** | Java 11 source level (Tycho config). Long-running work via `org.eclipse.core.runtime.jobs.Job`, never on the UI thread. HTTP via the existing `AnalyzerHttpClient`. |
| **Python** (when added for Phase 4 BTP service) | Python 3.11+, type-checked with `pyright`. FastAPI + Pydantic v2. Outbound HTTP via `httpx` with explicit timeouts. |

**Always match the surrounding style** — comment density, naming, idioms.
A patch that "looks like the surrounding code" is far easier to review.

---

## Anti-hallucination contract for SAP code (HARD)

This applies to every commit that contains ABAP or that integrates with
SAP APIs. It is non-negotiable.

1. **Cite the source** for every SAP API used (SAP Help Portal URL, SAP
   Note number, abapGit doc page, or *"verified in SE24/SE11 of system X"*).
   No source = the call is not approved.
2. **Distinguish Standard ABAP vs ABAP for Cloud Development.** Files in
   [abap_cloud/](abap_cloud/) cannot use classic table SELECTs.
3. **No invented method names.** If a method signature cannot be verified
   from an authoritative source, write a `TODO` and ask the maintainer
   to confirm.
4. **Schema-verify every DDIC SELECT.** State the release the schema was
   verified against, especially for tables whose columns vary by release
   (`DDLDEPENDENCY`, `ENHOBJ`, `D010INC`).

The full rules and authoritative source ranking are in
[CLAUDE.md §2](CLAUDE.md).

---

## Design contract: OOP + SOLID, extension-safe

Per [CLAUDE.md §4.0](CLAUDE.md), every design must follow SOLID so that
**future extensions do not break existing code**. Before merging a
change, mentally answer the four-question backwards-compatibility
checklist:

1. Did any existing public method signature change?
2. Did any existing JSON / CSV output field change name, type, or semantics?
3. Did any existing `ZGCTS_HIST` (or future) DB column change meaning?
4. Did any existing ICF query parameter change semantics?

If any answer is "yes", the change is a **major version bump** — flag it
in the PR description.

---

## Tests

- ABAP unit tests live alongside the classes (ABAP convention) under
  `abap/src/` / `abap_cloud/src/` once added. Run via ADT or `SE80`.
- Eclipse plugin tests run via Tycho Surefire; the current build skips
  them (`-DskipTests`) — please re-enable for any change touching plugin
  logic.
- Smoke tests for the ICF endpoint live in [verification/](verification/)
  (curl scripts).

---

## Security

If you discover a vulnerability, **do not file a public issue**. Follow
the reporting procedure in [SECURITY.md](SECURITY.md).

---

## Reporting bugs / requesting features

Open an issue on the GitHub repository:
https://github.com/Mayur175/tr-dependency-analyser/issues

Include:
- The SAP release / SP level (e.g. *S/4HANA Public Cloud 2025.Q2* or
  *S/4HANA on-prem 2023*).
- The Eclipse / ADT version.
- A minimal reproduction (TR / task ID structure, expected vs actual
  output).
- Relevant logs (`X-Auth-Bypass` header presence is fine to share;
  customer data is not).

---

## Code of conduct

Be direct, be kind, no harassment. Disagreement is welcome; personal
attacks are not.
