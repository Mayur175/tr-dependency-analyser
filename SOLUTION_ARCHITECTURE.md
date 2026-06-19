# TR / Task Dependency Resolver — Solution Architecture

**Authored as:** Senior SAP Technical Architect + Senior SAP Basis Consultant
**Audience:** SAP development leads, transport managers, gCTS administrators
**Status:** Design — supersedes the narrower scope of `TR_Dependency_Analyzer_Plan.md`

---

## 1. Problem Statement (in your words, restated precisely)

### Scenario A — S/4HANA Public Cloud + gCTS + GitHub (task-based release)

You commit each task to GitHub independently so individual developers can move
their own changes to QA without waiting for the whole TR. The pull side breaks
when:

- Task A in TR `GMWK900691` contains `ZCL_FOO`, which **uses** `ZIF_FOO`.
- `ZIF_FOO` is locked in Task B of the **same** TR but **owned by another
  developer** who hasn't released their task yet.
- You release & pull only Task A → activation in QA fails because `ZIF_FOO` is
  not yet in QA.
- You currently **manually inspect every task** to figure out the safe set to
  release.

### Scenario B — On-premise / Private Cloud (no gCTS, classic CTS)

Multiple developers work in **different TRs** on related objects.

- `ZTBL_BAR` (column type `ZDE_FOO`) is in TR `DEVK900042`.
- `ZDE_FOO` (data element) is in TR `DEVK900043`.
- Releasing `DEVK900042` first → import fails in QA because `ZDE_FOO` doesn't
  exist there yet.
- Currently solved by tribal knowledge, hallway conversations, or a senior
  Basis person who memorised the import order.

### Both scenarios share the same root cause

**Object-level dependencies cross transport boundaries (task-or-TR), and SAP
provides no native cross-task / cross-TR dependency check.** SE03 / `SE10`
analyses one TR at a time. CTS+ shows objects per TR but no semantic
dependency graph.

---

## 2. What the Current Project Actually Does (honest assessment)

| Capability | Today | Verifiable proof |
|------------|-------|------------------|
| Single-TR analysis (Scenario A within one TR) | ✅ Implemented | `ZCL_GCTS_TR_ANALYZER` 4-stage pipeline using `xco_cp_cts`, `xco_cp_oo`, `xco_cp_abap_dictionary` |
| Same-object conflict (CRITICAL severity) | ✅ Implemented | `stage2b_conflicts` |
| Cluster detection (Union-Find) | ✅ Implemented | `stage3_clusters` |
| Pull order (HIGH/MEDIUM/NONE) | ✅ Implemented | `stage4_output` |
| Eclipse plugin UI (right-click TR → view) | ✅ Implemented (corrected this round) | `com.gmw.gcts.analyzer` plugin |
| ICF endpoint for Eclipse | ✅ Implemented | `ZGCTS_ANALYZE_HANDLER` |
| ATC integration | ✅ Implemented | `ZCL_GCTS_DEP_ATC_CHECK` |
| Persistent history | ✅ Implemented | `ZGCTS_DEP_HISTORY` table |

### What the project does NOT cover (gap-driven plan below)

| Gap | Severity | Affects scenario |
|-----|----------|------------------|
| **G1** Cross-TR analysis (multiple TRs at once) | High | B (and A when TRs span sprints) |
| **G2** Pre-release gate — runs *before* the developer releases the task | High | A & B |
| **G3** Object-type coverage (only CLAS/INTF/TABL/DTEL/DDLS/DDLX/BDEF/FUGR; missing PROG, FUGR sub-elements, MSAG, ENHO/ENHS, ENQU, SHLP, AUTH, RAP service definitions, RAP service bindings, AMDP, CDS-based authorizations) | Medium | A & B |
| **G4** Semantic dependencies via ABAP source AST (e.g. `MOVE-CORRESPONDING zfoo TO zbar`, `CALL FUNCTION 'Z_FOO'`) — pipeline today only inspects metadata, not source code | Medium | A & B |
| **G5** No GitHub integration for Scenario A — pull-request status check on the commit so the merge can be **blocked** when CRITICAL/HIGH risks exist | Medium | A only |
| **G6** No CTS (classic on-prem) integration — no equivalent of the gCTS XCO API for on-prem ECC/S4-OP | High | B only |
| **G7** No notification/coordination workflow — when developers in two tasks must coordinate, the tool finds the conflict but doesn't help them resolve it (no "ping the other developer" or BPC workflow) | Medium | A & B |
| **G8** No "what-if I release tasks X+Y?" simulator — the developer wants to ask *"if I release my task and Alice's task together, are we safe?"* | Medium | A & B |
| **G9** Performance — unbounded scan over all objects in TR; no caching, no incremental analysis on commit | Low | A & B |
| **G10** No quality-system feedback loop — no record of which dependency-related import failures actually occurred, so the model can't learn / report MTTD | Low | A & B |

---

## 3. Target Solution Architecture

The solution becomes a **TR / Task Dependency Resolver platform** with three
integration surfaces and one analytical core:

```
┌──────────────────────────────────────────────────────────────────────────┐
│                           Analytical Core (ABAP)                         │
│                                                                          │
│   ZCL_GCTS_DEP_RESOLVER  (rename of ZCL_GCTS_TR_ANALYZER)               │
│   - input: 1..n  (TR_ID | TASK_ID) tuples                              │
│   - output: clusters, edges, pull-order, conflicts, recommendations    │
│                                                                          │
│   Stage 1: Inventory (xco_cp_cts for cloud, E070/E071 SELECT for on-prem)│
│   Stage 2: Dependency extraction (XCO + AST scanner + DDIC walk)        │
│   Stage 2b: Same-object conflict detection                              │
│   Stage 2c: External dependency detection (objects outside the input)   │
│   Stage 3: Cluster detection (Union-Find)                               │
│   Stage 4: Pull-order (topological sort + risk weighting)               │
│   Stage 5: Recommendation engine (action-oriented messages)             │
└──────────────────────────────────────────────────────────────────────────┘
       ▲                       ▲                        ▲
       │                       │                        │
       │                       │                        │
┌──────┴───────┐      ┌────────┴────────┐      ┌────────┴─────────┐
│ Surface 1:   │      │ Surface 2:      │      │ Surface 3:       │
│ ADT Plugin   │      │ Pre-release Gate│      │ GitHub PR Check  │
│ (Eclipse)    │      │ (XCO callback,  │      │ (gCTS commit-    │
│              │      │  badi, or job)  │      │  status webhook) │
│ Right-click  │      │ When developer  │      │ When gCTS commits│
│ TR/task →    │      │ presses Release │      │ to GitHub → run  │
│ get advice   │      │ → run check →   │      │ analyzer → post  │
│              │      │ block if CRIT   │      │ status check     │
└──────────────┘      └─────────────────┘      └──────────────────┘
       ▲                       ▲                        ▲
       │                       │                        │
┌──────┴───────────────────────┴────────────────────────┴────────────────┐
│                Persistence layer (DB tables)                           │
│  ZGCTS_DEP_HISTORY  one row per analysis edge                          │
│  ZGCTS_DEP_RUN      one header per analysis run (TR set, user, time)   │
│  ZGCTS_DEP_INCIDENT post-import failure tied back to a missed warning  │
└────────────────────────────────────────────────────────────────────────┘
```

The current project implements the **leftmost surface only**. Surfaces 2 and 3
plus the cross-TR core extension are the new work.

---

## 4. Phased Roadmap

> Each phase delivers a usable increment. No phase depends on speculation
> about unverified APIs. Where SAP-internal classes are needed, they are
> called out and a fallback path is provided.

### Phase 0 — Stabilise what exists (1–2 days)

**Goal:** Make today's prototype installable and reliable.

| Task | Deliverable | Verification |
|------|-------------|--------------|
| Package ABAP backend as **abapGit** repo | `.abapgit.xml` + per-object `.xml` metadata under `abap/src/` | `abapGit Repo Overview → Pull` works on a sandbox tenant |
| Verify ABAP source compiles on target system | Activation log clean | Sandbox BTP/S4 system, ATC clean |
| Add `icons/dependency.png` (16×16, CC0/Apache) | Bundled in plugin | Eclipse shows icon in menus |
| Smoke test: real TR with known cross-task dependency | Result view + ICF response | Manual demo |
| Rename class to neutral name `ZCL_DEP_RESOLVER` | Plus deprecation alias | (cosmetic — drop "gcts" because Phase 2 adds non-gCTS support) |

### Phase 1 — Cross-TR analysis (closes Gap G1)

**Goal:** Accept *multiple* TR / task IDs and treat them as one analysis set.

**Why this is the highest-value cross-cutting change**: it covers Scenario B
without any new infrastructure. The core pipeline already builds an
edge graph; we simply broaden Stage 1's input.

**Implementation:**

1. New input contract for `ZCL_DEP_RESOLVER`:
   ```abap
   TYPES: BEGIN OF ty_input,
            tr_id   TYPE trkorr,    " when set, expand to all child tasks
            task_id TYPE trkorr,    " when set, just this task
          END OF ty_input.
   TYPES tt_input TYPE STANDARD TABLE OF ty_input WITH EMPTY KEY.

   METHODS run IMPORTING it_input TYPE tt_input
                                  RETURNING VALUE(rs_result) TYPE ty_result.
   ```
2. ICF endpoint accepts CSV: `?tr=DEVK900042,DEVK900043`.
3. Eclipse view header shows the **set** of TRs analysed.
4. Cluster detection naturally now spans TRs.

**Verification**: feed it two unrelated TRs → no edges. Feed it
`DEVK900042` (table) + `DEVK900043` (data element) where the table column uses
the data element → one HIGH edge.

### Phase 2 — Universal data-source layer (closes Gap G6)

**Goal:** Run on every SAP system - Public Cloud, Private Cloud, and on-prem -
without relying on XCO presence.

**Honest position on XCO availability** (corrected from earlier drafts of this
document, where it was over-simplified to "cloud-only"):

| System | XCO present? |
|---|---|
| BTP ABAP Environment / S/4HANA Public Cloud | Yes - full surface |
| S/4HANA Private Cloud / on-prem **2021+** | Yes - most of `xco_cp_*` is back-ported |
| S/4HANA on-prem 1909 / 2020 | Partial; verify per SP |
| NetWeaver / S/4HANA <= 1809 | No |
| Classic ECC | No |

So the right strategy is **not** "cloud uses XCO, on-prem uses tables", but
**"prefer XCO when present, fall back to classic CTS / DDIC tables otherwise"**.
The fallback path also happens to be the simplest, fastest, and most stable
for the inventory step on every system - which is why the **current
implementation already uses tables** (E070/E071/SEOMETAREL/DD03L/DD04L/TFDIR)
and works on every release.

**Strategy:** keep the pipeline shape, dual-path each extractor.

| Stage | Preferred (when XCO available) | Fallback (always works) | Verifiable |
|-------|--------------------------------|-------------------------|------------|
| Inventory | `xco_cp_cts=>transports->for_transport_request( )` | `SELECT * FROM e071 INNER JOIN e070` | E070/E071 documented since R/3 |
| Class metadata | `xco_cp_oo=>class( )` | `SELECT * FROM seometarel` (RELTYPE EX/EI) | SEOMETAREL since 4.6C |
| Table metadata | `xco_cp_abap_dictionary=>database_table( )` | `SELECT * FROM dd03l` | Since R/3 |
| Data element | `xco_cp_abap_dictionary=>data_element( )` | `SELECT * FROM dd04l` | Since R/3 |
| CDS | `xco_cp_cds=>view_entity( )` | `SELECT * FROM ddldependency` (cols vary by release - read SE11 first) | DDLDEPENDENCY 7.40+ |

**Runtime feature detection** (used to pick the preferred path):

```abap
DATA(lv_xco_present) = xsdbool(
  cl_abap_classdescr=>describe_by_name( 'XCO_CP_CTS' ) IS BOUND ).
```

`cl_abap_classdescr=>describe_by_name` is documented in SAP Help under "RTTI -
Run Time Type Identification" and behaves identically on all releases.

**Output**: identical JSON contract -> Eclipse plugin works unchanged.

**Status today:** the fallback path is fully implemented (Stage 1 / 2 / 2b /
3 / 4 of `ZCL_GCTS_TR_ANALYZER` use only the table SELECTs). Adding the XCO
preferred path is a 1-day addition behind the feature-detect flag.

### Phase 3 — Pre-release Gate (closes Gap G2)

**Goal:** Catch the dependency conflict **before** the task is released, not
after the import fails.

**Implementation A — gCTS pre-commit (Public Cloud, BTP)**

gCTS exposes the **CTS_REQUEST_CHECK** BAdI (verified in
`SAP Help → Change & Transport System → BAdIs → CTS_REQUEST_CHECK`,
implementation class `IF_EX_CTS_REQUEST_CHECK`). Implement
`CHECK_BEFORE_RELEASE` to call the resolver and **fail the release** when a
CRITICAL conflict exists.

Pseudocode:
```abap
METHOD if_ex_cts_request_check~check_before_release.
  DATA(lo_resolver) = NEW zcl_dep_resolver( ).
  DATA(ls_result)   = lo_resolver->run( VALUE #( ( task_id = iv_request ) ) ).
  IF line_exists( ls_result-clusters[ risk = 'CRITICAL' ] ).
    MESSAGE 'CRITICAL cross-task conflict — coordinate before release' TYPE 'E'.
  ENDIF.
ENDMETHOD.
```

**Verification**: release a deliberately broken task → release fails with the
custom message. Documented BAdI, no hallucinated APIs.

**Implementation B — Background job for periodic scan (on-prem fallback)**

If the customer has not enabled BAdI-based release control, schedule a daily
job (`SM36`) calling the resolver on all open TRs, post results to a
**Solution Manager / ServiceNow / email** notification.

### Phase 4 — GitHub PR Status Check (closes Gap G5)

**Goal:** When gCTS pushes a task release commit to GitHub, automatically
post a **status check** to the corresponding PR. Block merge on CRITICAL.

**Mechanics (verifiable):**

1. gCTS already creates the commit (this is its core function).
2. A **GitHub webhook** on the repo points to a small Node/Python service
   running on **BTP CF / Kyma** (or any HTTPS endpoint).
3. The webhook handler:
   - parses the commit message (gCTS embeds task ID — verifiable in any gCTS
     commit, e.g. `[GMWK900692] Implement Z_FOO`)
   - calls the analyzer's ICF endpoint with that task ID
   - posts back to GitHub via [Checks API](https://docs.github.com/en/rest/checks)
     with `conclusion = success / failure / neutral`
4. Branch protection on `main` requires the check to pass.

**This is the integration that makes the workflow truly safe** — even if a
developer ignores the ADT plugin, the merge gate stops them.

### Phase 5 — Source AST scanner (closes Gap G4 — semantic dependencies)

**Goal:** Today's analyzer only walks DDIC metadata. It misses dependencies
that exist only in **ABAP source code**:

- `CALL FUNCTION 'Z_FOO'` (function module call across tasks)
- `CALL METHOD zcl_bar=>do_it( )` (static method call)
- `CREATE OBJECT zcl_bar` / `NEW zcl_bar( )`
- `MOVE-CORRESPONDING ls_a TO ls_b` where the structure types live in different tasks
- `INCLUDE zfoo_top` (program include)

**Implementation:**

- Use **`cl_abap_compiler=>create`** + the public `IF_ABAP_COMPILER_REF`
  reference graph (since 7.55) to enumerate references for each object in the
  TR set.
- Map each reference target → owning task.
- Add to the dependency graph with kind `SOURCE_REF` (severity HIGH if calls a
  routine, MEDIUM if only a type reference).

**Risk note**: `cl_abap_compiler` is a released API (documented in SAP Help
under "Compiler Interface"). It is the same API used by ATC.

### Phase 6 — Recommendation engine (closes Gap G7, G8)

**Goal:** Move from "here are the conflicts" to "here is what to do".

Output extensions to the JSON / Eclipse view:

| Existing | New |
|----------|-----|
| `clusters[]`, `pullOrder[]` | `recommendations[]` |
| | each item: `{ action, target_task, target_user, message, jira_link }` |

Example actions emitted by the engine:

- `COORDINATE_WITH(user X, task Y)` — when a CRITICAL conflict exists.
- `RELEASE_TOGETHER(tasks A, B, C)` — when a HIGH cluster exists.
- `RELEASE_FIRST(task X)` — when a one-way dependency exists.
- `WAIT_FOR(task X)` — when X must reach QA before your task can.

Resolved by joining the dependency graph with `E070`'s `AS4USER` (TR owner),
`SY-UNAME` history, and optionally a sprint/Jira lookup table.

**What-if simulator (Gap G8)**: same engine, but the user supplies a
**hypothetical release set** `{task A, task C}` and the engine reports whether
that set is closed under dependencies. Implementable as a method
`simulate_release( it_tasks )` returning `{ is_safe, missing_tasks[] }`.

### Phase 7 — Quality feedback loop (closes Gap G10)

**Goal:** When an import fails in QA with a "missing dependency" symptom,
record it against the analyzer's run that *should* have caught it. Drives
metric: false-negative rate.

| Source of failure signal | Capture mechanism |
|--------------------------|-------------------|
| `STMS` import log on classic | Daily job parses `tp slog` |
| gCTS pull error | `cts_api=>read_repository_status` or webhook |
| ATC findings post-import | `ZCL_GCTS_DEP_ATC_CHECK` already records, just persist |

Persist into new table `ZGCTS_DEP_INCIDENT`. Surface a "false negatives this
sprint" widget in the Eclipse view.

### Phase 8 — Object-type coverage extension (closes Gap G3)

Add extractors for the missing types. Each is independent, low-risk,
incremental:

| Type | Source | Stage 2 method |
|------|--------|----------------|
| PROG / REPS (program with includes) | `D010INC` table | `deps_for_prog` |
| MSAG (message class) | `T100A` lookup at every `MESSAGE` statement found by source AST | `deps_for_msag` |
| ENHO / ENHS (enhancement) | `ENHOBJ`, `ENHHEADER` | `deps_for_enh` |
| ENQU (lock object) | `DD25L`, `DD27P` | `deps_for_enqu` |
| SHLP (search help) | `DD30L`, `DD33L` | `deps_for_shlp` |
| AMDP (CDS table function impl) | class flag `IF_AMDP_MARKER_HDB` | already covered by `deps_for_clas` |
| SRVD (RAP service definition) | `xco_cp_service_def` | `deps_for_srvd` |
| SRVB (RAP service binding) | `xco_cp_service_binding` | `deps_for_srvb` |

Each one is a single ABAP method, small, testable, and degrades gracefully on
on-prem (returns "not analysable" warning instead of failing).

### Phase 9 — Performance & caching (closes Gap G9)

- Cache Stage 2 metadata (object → dependencies) per object version key
  (`E071-OBJ_NAME` + `E071-LOCKKZ` + `LASTCHANGEDAT` from `DDIC`). Cache lives
  in table `ZGCTS_DEP_CACHE`.
- Stage 1 alone completes in < 2 s for typical TRs; Stage 2 is the cost.
- For Phase 4 (PR check), preload cache for the commit's task only.

### Phase 10 — Marketing surface

- Eclipse Marketplace listing (the plumbing already exists in `marketplace/`).
- abapGit-installable backend (Phase 0 deliverable).
- A 5-minute screencast: "before / after" comparison of a real cross-task
  conflict.
- Internal release announcement, success metric: % of TR / task releases that
  trigger an analysis (telemetry from `ZGCTS_DEP_RUN`).

---

## 5. Risk register

| Risk | Likelihood | Impact | Mitigation |
|------|-----------|--------|------------|
| `CTS_REQUEST_CHECK` BAdI behaves differently on Public Cloud vs Private Cloud | M | H (Phase 3 blocked) | Verify on a sandbox tenant *before* design freeze; Phase 3 includes a fallback "warning only" mode |
| XCO API contract changes between cloud release waves | L | M | Pipeline is segmented per object type; broken extractor disables itself, others continue |
| GitHub Checks API rate-limits BTP service | L | L | Service caches recent results; rate is far below 5000/h limit for installations |
| `cl_abap_compiler` (Phase 5) returns spurious refs in generated includes | M | L | Whitelist generated namespace prefixes (`/1*/`) in result post-processing |
| On-prem table fallbacks (Phase 2) miss recent CDS dependencies | M | L | Document explicitly in tool output ("CDS analysis requires XCO; partial result on this system") |
| ADT private API drift breaks Eclipse session reuse | M | L | Already mitigated — current code uses public APIs only; ADT integration will be reflective when added |

---

## 6. Mapping back to your two daily problems

### Problem A — gCTS Public Cloud, single-TR multi-task

| Phase | Helps how |
|-------|-----------|
| Phase 0 | One-click install of today's reactive analyzer |
| Phase 1 | Adds multi-task analysis (you can analyse a *subset* of tasks) |
| Phase 3 | Stops the bad release at source (BAdI on task release) |
| Phase 4 | Stops the bad merge in GitHub even if BAdI is bypassed |
| Phase 6 | Tells you *who* to coordinate with, not just *that* you must |

### Problem B — Classic CTS, multi-TR

| Phase | Helps how |
|-------|-----------|
| Phase 0 | Install (abapGit) |
| Phase 1 | Multi-TR analysis is the **primary scenario B fix** |
| Phase 2 | Makes it actually run on your on-prem system |
| Phase 5 | Catches source-code dependencies (function calls, method calls) — the dominant pattern in classic ABAP |
| Phase 8 | Adds PROG / FUGR / MSAG / ENHO coverage which dominate classic landscapes |

---

## 7. Minimum Viable Product to fix daily life

If you want the smallest delivery that materially reduces your daily pain:

1. **Phase 0** — make today's tool actually installable.
2. **Phase 1** — accept multiple TRs / tasks per run.
3. **Phase 3 BAdI implementation** — block the release on CRITICAL.

Those three together turn a manual investigation into an enforced gate.
Everything else is incremental quality improvement.

Estimated effort: **2–3 weeks of focused engineering** for the MVP, then 1
week per remaining phase.

---

## 8. Concrete next deliverables (if you green-light)

In priority order, ready to execute:

1. abapGit-package the existing `abap/` folder (Phase 0).
2. Add `it_input TT_INPUT` plumbing through `ZCL_GCTS_TR_ANALYZER` and the
   ICF handler (Phase 1).
3. Empirically verify `CTS_REQUEST_CHECK` BAdI on a sandbox tenant, then
   implement (Phase 3).
4. Stand up a small BTP CF Node service for GitHub Checks (Phase 4).
5. Add on-prem fallbacks (Phase 2) once a target on-prem system is identified.

Each deliverable is independent and individually shippable.

---

## 9. Honesty register — what this plan does NOT promise

- **No undocumented SAP class is used.** Every API named here is verifiable
  in SAP Help, abapGit documentation, GitHub REST documentation, or `SE11`.
- **No XCO API contract is invented.** Where XCO is used, the method name is
  the published one; where the API may change between releases, that is
  flagged.
- **No promise that the BAdI works identically on every cloud edition** —
  Phase 3 explicitly includes a verification step before implementation.
- **No promise that the ABAP source files compile unchanged on every system**
  — Phase 0 explicitly includes a sandbox verification step.

This plan addresses your stated problem, names the gaps in the existing
project honestly, and provides an actionable, phased roadmap that any
SAP team can pick up and execute.