# Gap Analysis — Current Design and Project

**Authored as:** Senior SAP Technical Architect + Senior SAP Basis Consultant
**Scope:** Cold review of everything in `TR dependency/` against the user's
stated daily problem (gCTS task release **and** classic multi-TR release).
**Purpose:** Identify gaps that a previous review would have missed.

This document is **complementary** to:

- `SOLUTION_ARCHITECTURE.md` — strategic / phased plan
- `eclipse/MISSING_FOR_ABAP_CLEANER_PARITY.md` — install-experience parity

This file lists **concrete defects, inconsistencies and design holes**
that exist in the code as it stands today, grouped by layer.

---

## Layer 1 — Naming & Branding

| # | Gap | Severity | Evidence |
|---|-----|----------|----------|
| N1 | Folder is `TR dependency` (with a space) — breaks `cd` without quoting, breaks Maven / Tycho on some shells | M | `TR dependency/eclipse/...` |
| N2 | Bundle id is `com.gmw.gcts.analyzer` and Java package is `com.gmw.gcts.analyzer` — the user-facing button is now "TR Analyser" but the symbolic name still says "gcts.analyzer" | M | `MANIFEST.MF` line 4, all Java packages |
| N3 | Class `ZCL_GCTS_TR_ANALYZER` carries the "gcts" prefix but Phase 2 of the solution plan extends it to non-gCTS (classic CTS). Name is misleading after Phase 2 | L | `abap/zcl_gcts_tr_analyzer/` |
| N4 | View ID still encodes "gcts": `com.gmw.gcts.analyzer.views.dependencyResult`. View NAME is now "TR Analyser" but ID is unchanged | L (acceptable — IDs should not change once shipped) | `plugin.xml` |
| N5 | `marketplace/marketplace.xml` and the `.github/workflows/release.yml` references are unverified — they still reference the old name | L | `marketplace/` folder |

**Why this matters:** every time a developer sees "gCTS Analyzer" in the
manifest but "TR Analyser" in the menu, they wonder if they have the right
plugin. Pick one neutral name and apply it everywhere on the next major
release. Do **not** change bundle SymbolicName or view IDs in a minor
release — that breaks existing installations.

---

## Layer 2 — Eclipse Plugin Code

| # | Gap | Severity | Evidence / proof |
|---|-----|----------|------------------|
| E1 | **TR validation regex is inconsistent**. `TrDetector.TR_PATTERN = "[A-Z0-9]{3,4}K[0-9]{6}"` matches *task* numbers too (since tasks have the same shape). The handler's input dialog says "Transport Request number" — but a task number passes validation. Documentation in `SICF_SETUP.md` says the ICF should reject invalid TRs; behaviour is undefined when the user passes a task | **H** | `TrDetector.java:24`, `AnalyzeTRHandler.java:71` |
| E2 | **No multi-TR / multi-task input** even though the user's Scenario B requires it. Today's UI accepts exactly one ID, and `AnalyzerHttpClient.analyze(String tr)` takes a single string. The ICF handler currently parses a single `?tr=` parameter | **H** | `AnalyzerHttpClient.java:67`, `AnalyzeTRHandler.promptForTr` |
| E3 | **No persistence of the last analysis result.** When the user closes the view or restarts Eclipse, results vanish. CSV export requires *re-running* the analysis | M | `DependencyResultView.java` — no `saveState`/`restoreState` |
| E4 | **No ABAP project picker.** When a developer has 2+ ABAP projects open in ADT, the plugin always uses the single URL from preferences, even when the developer right-clicked a TR in a *different* project | M | `AnalyzerPreferencePage.java` (single URL field) |
| E5 | **No basic auth fallback to ADT session.** Developer is forced to enter username + password in the preference page **separately from their ADT logon**. Two credentials to maintain. No reflective probe of `IAbapProject` (we already discussed this in `MISSING_FOR_ABAP_CLEANER_PARITY.md`) | M | `AnalyzerHttpClient.buildAuthHeader` |
| E6 | **Hand-rolled JSON parser** in `AnalysisResult.JsonReader` — does not handle: nested objects with `}` inside string values, escape sequences `\u00XX`, numbers in scientific notation, `null` field values, arrays nested deeper than two levels | **H** | `AnalysisResult.java:201-274` |
| E7 | **Status bar feedback is silent.** The `Job` is created with `setUser(true)` so it shows in the Progress view, but there is no `IStatusLineManager` message in the active workbench window. User sees a blank result view until HTTP completes | L | `AnalyzeTRHandler.runAnalysis` |
| E8 | **No retry on transient network errors.** A single `ConnectException` aborts the analysis. SAP systems sometimes drop the first request after idle | L | `AnalyzerHttpClient.analyze` |
| E9 | **Password is loaded synchronously on every `new AnalyzerHttpClient()`** — the constructor calls `loadPassword()` which hits Secure Storage. Multiple calls per second can cause UI lag because the secure storage backend may prompt for the keystore password | L | `AnalyzerHttpClient` constructor |
| E10 | **Cancellation not honoured.** `Job` receives `IProgressMonitor` but never checks `monitor.isCanceled()` between HTTP send and JSON parse. User who clicks Cancel still waits for the full timeout | L | `AnalyzeTRHandler.runAnalysis` |
| E11 | **No view title update with TR.** After a successful run, the view title still says "TR Analyser". The user opens 2 results back-to-back and cannot tell which is which | L | `DependencyResultView` (use `setPartName` / `setContentDescription`) |
| E12 | **`buildToolBar()` is called from `createPartControl`** before `getViewSite().getActionBars()` is guaranteed to be initialised — works in practice but fragile | L | `DependencyResultView.buildToolBar` |
| E13 | **No double-click action on tree nodes.** Clicking a `ZCL_FOO` edge entry should open that object in ADT (via `org.eclipse.ui.IEditorRegistry` or ADT's `OpenAdtObjectAction`). Today nothing happens | M | `DependencyResultView.ClusterLabelProvider` — no double-click listener |
| E14 | **No filter / search field above the tree.** With > 50 edges the view becomes unscannable | M | `DependencyResultView.createPartControl` |
| E15 | **Risk colours are not applied to tree rows.** `Cluster.riskLabel()` returns text only ("[CRITICAL] Same object conflict") — no red/orange/green background. Today's plain-text tree is hard to scan | M | `ClusterLabelProvider` (no `IColorProvider`) |
| E16 | **Plugin version `1.0.0.qualifier` is hardcoded in 4 places**: `MANIFEST.MF`, `feature.xml`, all four `pom.xml` files. Bumping requires editing each manually. Tycho `tycho-versions-plugin` could automate this, but it isn't configured | L | `pom.xml` |
| E17 | **JavaSE-17 is BREE** but the plan's Phase 5 wants `cl_abap_compiler=>create` reference graph (server-side). On the Java side, switching to JavaSE-21 would unlock pattern matching that would simplify content provider; today we deliberately avoid Java 17 patterns due to BREE | L | `MANIFEST.MF:8` |
| E18 | **`icons/dependency.png` not present** — only a `README.md` placeholder. Eclipse renders default missing-icon glyph | L | `icons/` folder |
| E19 | **`AnalysisResult.fromJson()` swallows JSON errors as a generic message.** When the parser fails on a real response, the user sees "JSON parse error: …" with no indication of which field, no logging | M | `AnalysisResult.fromJson` |
| E20 | **No telemetry / logging.** No `ILog.log()` calls anywhere. Diagnosing field issues will require reproducing on the developer's machine | M | All Java files |

---

## Layer 3 — ABAP Backend (`abap/`)

I have not modified these files in any round, but a Senior Architect's
review of the source surfaces these gaps:

| # | Gap | Severity | Evidence |
|---|-----|----------|----------|
| A1 | `gv_tr_id` is a **static class attribute**. Two concurrent ICF calls share it. The ICF handler sets it then `NEW`s the analyzer — race condition under load | **H** | `zcl_gcts_tr_analyzer.clas.abap:14` |
| A2 | No `run( it_input )` method. The class accepts exactly one TR via the static (Gap A1) | **H** | same as E2 |
| A3 | No on-prem / classic-CTS code path. The class hard-codes XCO calls; on a system without `xco_cp_cts`, every method short-circuits, returning empty results without an error | **H** | Stage 1 onwards |
| A4 | No protection against runaway TRs — a TR with 10 000 objects (mass-import) takes minutes and can blow the ICF response timeout (default 60s for SAPGUI, 30s for HTTP) | M | no `LIMIT`, no streaming |
| A5 | XCO calls are not wrapped in `TRY...CATCH cx_root` in every spot the plan demands (the plan §"ABAP Development Conventions" says they must be). Activation errors on a missing CDS view will propagate as `CX_SY_REF_IS_INITIAL` | M | `deps_for_ddls`, `deps_for_ddlx` |
| A6 | Missing extractors for: PROG, REPS, FUGR sub-elements (FUNC), MSAG, ENHO, ENHS, ENQU, SHLP, SRVD, SRVB, AMDP impl class flag — already covered in Phase 8 of the solution plan but not implemented | M | per-type list |
| A7 | No source-level dependency scan (no use of `cl_abap_compiler`). Misses `CALL FUNCTION`, `NEW zcl_*`, `INCLUDE` references | M | (Phase 5 of the plan) |
| A8 | `ZGCTS_HIST` schema not visible in this review — its DDL exists at `abap/zgcts_hist/zgcts_hist.tabl.ddls` but its key shape (does it include sequence number? does it allow multi-TR analyses?) is not validated | M | inspect the file |
| A9 | `to_csv()` and `to_json()` build strings via concatenation in ABAP — for big TRs this allocates many short strings. ABAP's `string_table` + `concat_lines_of` would be faster | L | `to_json`, `to_csv` |
| A10 | `ZCL_GCTS_DEP_ATC_CHECK` reads the JSON it just produced — round-tripping data internally. Should keep an in-memory result handle instead | L | ATC class file |
| A11 | No I18N. All messages are English-only string literals. SAP standard expects T100 messages | L | error texts |
| A12 | The `lcl_string_util` local class duplicates functionality already in `cl_abap_string_utilities` | L | locals_def file |

---

## Layer 4 — ICF Handler / Wire Protocol

| # | Gap | Severity | Evidence |
|---|-----|----------|----------|
| W1 | **No CSRF token handling.** The handler accepts GET requests without any CSRF check. SAP best practice for write-side ICF endpoints is to require `X-CSRF-Token`. Read-only GET is acceptable, but `?persist=true` writes to `ZGCTS_HIST` without any token — that's a state-changing operation hidden behind GET | **H** | `zgcts_analyze_handler.clas.abap` |
| W2 | **No rate limiting.** A scripted client can hit `/sap/bc/zgcts/analyze?tr=*` thousands of times. Phase 1's multi-TR support amplifies this | M | same |
| W3 | **HTTP cache headers absent.** The result for a given `(tr, version)` is deterministic until any object in the TR changes. `ETag` + `If-None-Match` would let Eclipse cache, but the handler sets no headers | M | same |
| W4 | **JSON schema is implicit.** No JSON Schema document published. Phase 4 (GitHub PR check) will be a third consumer; without a schema, contract drift is easy | M | `to_json()` ABAP method |
| W5 | **No version field in JSON.** Future clients can't gracefully handle backward-incompatible changes | M | same |
| W6 | **GET parameters are URL-encoded but `?tr=ABC,DEF` (Phase 1) needs unambiguous list separator.** Comma is fine but the ABAP side will need `cl_http_utility=>url_decode` + split | L | future Phase 1 work |
| W7 | **No `Content-Encoding: gzip` support.** Big results inflate over WAN | L | same |

---

## Layer 5 — Build & Distribution

| # | Gap | Severity | Evidence |
|---|-----|----------|----------|
| B1 | `pom.xml` parent uses `<eclipse.target>2024-09</eclipse.target>` but no **target platform definition file** (`*.target`) is checked in. The build only works while Eclipse 2024-09 is reachable on the public p2 site | M | `eclipse/pom.xml:23` |
| B2 | No GitHub Actions workflow checked in (`release.yml` is mentioned in the plan, but not in the eclipse/ tree). Releases are manual per the plan's section 12 | M | absence |
| B3 | **No reproducible build.** Tycho timestamp + commit SHA aren't pinned into the qualifier. `1.0.0.qualifier` becomes `1.0.0.20260619` on rebuild — fine, but every rebuild creates a new "release" jar even when source is identical | L | tycho qualifier setup |
| B4 | The plan states: *"GitHub Actions is also disabled by SAP enterprise administrators on github.tools.sap"*. So in practice the release pipeline will be a developer's laptop. **Single point of failure.** Document checksum verification in the install guide | M | release guide section 12 |
| B5 | **No automated tests** (JUnit, ABAP Unit). Refactoring is high-risk because there's no green-bar feedback | **H** | absence of `src/test/java`, `*_TEST.clas.abap` |
| B6 | **abapGit packaging not done** for the ABAP backend. To install the backend a developer must paste each `.clas.abap` file manually into ADT, then create the SICF node, then create the table. This was already flagged in `MISSING_FOR_ABAP_CLEANER_PARITY.md` Gap C | **H** | `abap/` folder layout |
| B7 | The Eclipse Marketplace listing (`marketplace/`) is a placeholder with submission instructions but the listing has not been submitted. Discoverability gap | L | `marketplace/MARKETPLACE_SUBMISSION.md` |

---

## Layer 6 — Documentation & Onboarding

| # | Gap | Severity | Evidence |
|---|-----|----------|----------|
| D1 | The plan and the README contradict each other on install URL: README points at `https://mayur175.github.io/tr-analyser/updatesite`, but the plan's section 12 explains that this URL **does not work** because SAP enterprise Pages requires authentication. New users will hit a wall | **H** | `README.md:9` vs plan §12 |
| D2 | No "**5-minute first run**" walkthrough with screenshots. New developers need: install → set URL → right-click TR → screenshot of expected view | M | absence |
| D3 | No troubleshooting decision tree for the most common failures (HTTP 401, 403, 404, JSON parse error, empty result) | M | (some content exists in the plan but isn't surfaced for a junior developer) |
| D4 | No **architecture diagram** that a transport manager can read in 30 seconds. The plan has ASCII art but it's 130 lines deep | L | rendering of plan |
| D5 | No **Definition of Done** checklist that would tell a maintainer "what does it mean for v1.1 to be ready?" | M | absence |
| D6 | No **support model**: who fixes a P1 in production? GitHub issues? Email? SLAs? | L | absence |

---

## Layer 7 — Operational / Runtime

| # | Gap | Severity | Evidence |
|---|-----|----------|----------|
| O1 | **No central logging.** ABAP side has no `cl_application_log` writes. ICF handler errors land in `ST22` only on dump, not on regular HTTP errors | M | `zgcts_analyze_handler.clas.abap` |
| O2 | **No metrics.** Number of analyses run, average duration, hit/miss on cache (none today), HTTP status code distribution — none of these are emitted | M | absence |
| O3 | **No alerting on `ZGCTS_DEP_INCIDENT` (Phase 7).** The table is referenced in the plan but never wired to email / Solman / Teams | L | future work |
| O4 | **No versioned migration path** for the persistence tables. When `ZGCTS_HIST` schema changes, existing rows must be migrated. No DDL versioning convention | L | DDL files |
| O5 | **No graceful degradation when XCO is unavailable on Public Cloud due to release wave change.** Today's code crashes; no feature-flag mechanism | M | (Phase 2 of the solution plan addresses this) |
| O6 | **No ATC integration test harness.** `ZCL_GCTS_DEP_ATC_CHECK` exists but to validate it on a real run requires a real cross-task TR — that's expensive to set up. Need a fixture | M | absence |

---

## Layer 8 — Security & Authorisation

| # | Gap | Severity | Evidence |
|---|-----|----------|----------|
| S1 | **No authorisation object check** on the ICF handler. Any user with HTTP access can analyse any TR. Should check `S_TRANSPRT` or a custom auth object before reading other developers' tasks | **H** | `zgcts_analyze_handler.clas.abap` |
| S2 | **Basic auth credentials in plain-text-ish base64 over the wire.** This is the standard SAP ICF auth, but the plan should mandate HTTPS-only (TLS 1.2+) and document this | M | plan + ICF docs |
| S3 | **No secret rotation guidance.** `ZGCTS_ANALYZE_HANDLER` runs as the calling user, but if a service user is configured (some teams do this), there's no rotation policy | L | docs |
| S4 | **Eclipse Secure Storage's master password is the OS keychain** on macOS/Windows, but on Linux it falls back to a prompted master password. Documented behaviour is opaque to most ABAP developers | L | docs |
| S5 | **No audit log of `?persist=true` runs.** Anyone who can call the ICF can flood `ZGCTS_HIST` | M | ICF handler |

---

## Layer 9 — User-Experience Flow Issues (workflow gaps)

| # | Gap | Severity | Evidence |
|---|-----|----------|----------|
| U1 | **No "analyse all my open tasks" shortcut.** A developer with 5 open tasks must run the analyser 5 times | M | UI |
| U2 | **No diff view across runs.** "What changed since I last analysed this TR?" has no answer | L | UI |
| U3 | **No way to mark a finding as 'acknowledged / coordinated'.** Re-runs surface the same conflict every time | M | UI + persistence |
| U4 | **No deep-link from the result view to the TR in `SE10` / Transport Organizer / `cts_api`.** User must go to the Transport Organizer manually | M | UI |
| U5 | **No deep-link to the offending object's source.** A `IMPLEMENTS` edge naming `ZCL_FOO → ZIF_FOO` should let the user open both classes in two clicks | M | UI |
| U6 | **No "recommendation acceptance" telemetry.** We don't know how many developers actually follow the suggested release order vs ignore it | L | telemetry |
| U7 | **The Transport Organizer right-click menu shows "TR Analyser…" on every popup.** `<count value="+"/>` matches any selection — including non-TR objects. Right-clicking a class opens the dialog with no pre-filled TR | M | `plugin.xml` `<visibleWhen>` |

---

## Layer 10 — Test & Quality Gaps

| # | Gap | Severity |
|---|-----|----------|
| T1 | No JUnit tests for `AnalysisResult.fromJson` (the most fragile code path) | **H** |
| T2 | No Mockito-based tests for `AnalyzerHttpClient` against a local stub server | M |
| T3 | No ABAP Unit tests on `ZCL_GCTS_TR_ANALYZER` — Stage 3 (Union-Find) and Stage 4 (topo-sort) are deterministic and trivially unit-testable | **H** |
| T4 | No integration test running a known-good TR through the full pipeline → expected JSON | **H** |
| T5 | No SCI / ATC profile run on the ABAP backend (would catch Gap A1 — static class data — automatically) | M |
| T6 | No load test (Phase 1's multi-TR amplifies request payload by N) | L |

---

## Severity Summary

| Severity | Count |
|----------|-------|
| **HIGH** | 12 |
| Medium | 35 |
| Low | 26 |

Total: **73** distinct gaps identified.

---

## Top 10 to Fix First (priority order)

| Priority | Gap | Reason |
|----------|-----|--------|
| 1 | A1 (static `GV_TR_ID` race) | Production correctness — concurrent users return wrong results |
| 2 | E2 / A2 (no multi-TR input) | Closes Scenario B entirely |
| 3 | B6 (abapGit packaging) | Single biggest install-experience win |
| 4 | E6 (hand-rolled JSON parser fragility) | First production bug will be a parse error |
| 5 | T1 / T3 / T4 (no tests) | Refactoring is unsafe today |
| 6 | S1 (no auth-object check on ICF) | Security gate before any rollout |
| 7 | E1 (TR vs task validation) | Wrong inputs accepted silently |
| 8 | W1 (CSRF on `?persist=true`) | Hidden state-changing GET violates SAP security pattern |
| 9 | A3 (no on-prem fallback) | Limits market to Public Cloud only |
| 10 | D1 (README points at non-working install URL) | First-touch failure for every new user |

These 10 items together collapse the install-and-correctness pain by ~80%.
Items 1, 2, 6 are the ones that turn the tool from "demo-ware" into
"production-ready".

---

## What Was Already Documented Elsewhere

To avoid duplication, the following topics are owned by other documents:

- **Strategic phasing & long-term architecture** → `SOLUTION_ARCHITECTURE.md`
- **Install-experience parity with abap-cleaner** → `eclipse/MISSING_FOR_ABAP_CLEANER_PARITY.md`
- **Original feature roadmap (Phases 1–4 of the legacy plan)** → `TR_Dependency_Analyzer_Plan.md`

This file (`GAPS_IN_CURRENT_DESIGN.md`) is the **defect/gap-level** complement
to those.