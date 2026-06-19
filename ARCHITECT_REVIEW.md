# Tri-Architect Review — TR Dependency Analyser

This review is signed off by three architects acting in their respective roles:

- **SAP Architect** — reviews ABAP source, DDIC choices, ICF security, and the
  overall transport-correctness model (`abap/src/*.abap`).
- **Python Architect** — reviews the verification harness, simulator, and
  contract round-trip (`verification/*.py`).
- **Java Architect** — reviews the Eclipse plugin (`eclipse/com.gmw.gcts.analyzer/`).

Every finding is graded **P0** (block release), **P1** (next sprint), **P2**
(backlog). Recommendations cite the exact file and line where applicable.

---

## Executive summary (cross-layer)

| Layer | Files reviewed | Highest severity open | Recommendation |
|---|---|---|---|
| SAP / ABAP   | `zcl_gcts_tr_analyzer.clas.abap` (912 LoC), `zgcts_analyze_handler.clas.abap` (251 LoC), `zcl_gcts_dep_atc_check.clas.abap`, `zgcts_hist.tabl.xml` | **P0** — design correctness in `stage3_clusters` (commas in tasks string) and recursive `uf_find` | Refactor cluster storage and Union-Find before any pilot |
| Python       | `simulate_pipeline.py` (468 LoC), `mock_sap_data.py` (~620 LoC), `verify_json_contract.py` | **P1** — no test runner / no CI hook; one O(N×M) loop in TR aggregation | Wrap simulator in `pytest`, gate it in CI |
| Java         | `AnalyzerHttpClient.java` (219 LoC), `AnalysisResult.java` (275 LoC), `AnalyzeTRHandler.java`, `DependencyResultView.java`, `TrDetector.java`, `AnalyzerPreferencePage.java` | **P0** — hand-rolled JSON parser is unsafe (already on the project's known-gap list E6); single-TR API only | Replace parser with org.json or Jackson; introduce `analyze(List<String>)` |

The project is **not production-ready in any of the three layers** despite
the algorithm being mathematically sound (Python simulator confirms). Two P0
defects exist on the critical path; both are fixable in days, not weeks.

---

# 1. SAP / ABAP Architect Review

**Reviewer hat:** Senior SAP Technical Architect & Basis Consultant
**Files in scope:** `TR dependency/abap/src/*`

## 1.1 Strengths

- ✅ Uses **only public, release-stable DDIC tables** (`E070`, `E071`,
  `SEOMETAREL`, `DD03L`, `DD04L`, `TFDIR`). Documented since R/3 4.6C, so
  works on every NetWeaver release the customer can have.
- ✅ Constructor exposes `it_input TYPE tt_input` — multi-TR / multi-task
  signature is in place. The legacy `gv_tr_id` static is kept only as a
  back-compat shim and is documented as deprecated (line 29).
- ✅ ICF handler enforces `AUTHORITY-CHECK OBJECT 'S_TRANSPRT'` with
  `TTYPE=CUST`/`ACTVT=03` (lines 167-174). This matches the project's own
  Gap S1 mitigation plan.
- ✅ Sets `Cache-Control: no-store` and `X-Content-Type-Options: nosniff`
  on every response (handler lines 211-214) — small but correct security hygiene.
- ✅ ICF handler runs the analyser *inside* `TRY ... CATCH cx_root` (line 154);
  any unhandled ABAP exception is converted to HTTP 500 with a JSON body.
- ✅ Input parser validates the regex before passing ids to the analyser.

## 1.2 Findings

### 🔴 P0 — Cluster store uses a delimited string ⇒ false positives via `CS`

**File:** `zcl_gcts_tr_analyzer.clas.abap` line 80-85, 530, 617, 671, 771, 783, 799

The `ty_cluster.tasks` field is a single comma-separated string built with
`tasks = tasks && ',' && task`. Membership tests are then done with the
string-search operator `CS` (e.g. line 617 `CHECK ls_cl-tasks CS ls_dep-source_task`).

Real SAP TR / task ids are 10 characters and share a common prefix (e.g.
`GMWK900691`, `GMWK900692`). `CS` is a **substring** test, not a token test,
so:

- `'GMWK90069' CS 'GMWK9006'` is `TRUE` even though it is not a real task.
- `'GMWK900691,GMWK900692' CS 'GMWK90069'` is `TRUE` for any prefix.

In production this leads to false-positive cluster membership and **wrong
risk classification**. The defect is silent — no exception, just incorrect
output.

**Fix:** store `tasks` as `TYPE STANDARD TABLE OF trkorr` and replace every
`CS` with `line_exists( ... )`. ~30 lines of change, no API impact.

### 🔴 P0 — Recursive `uf_find` blows the stack on large TRs

**File:** `zcl_gcts_tr_analyzer.clas.abap` line 560-572

```abap
METHOD uf_find.
  ...
  rv_root = uf_find( EXPORTING iv_task = ls_node-parent CHANGING ct_uf = ct_uf ).
  ct_uf[ task = iv_task ]-parent = rv_root.
  ...
ENDMETHOD.
```

This is recursive. ABAP has a **call stack of ~256 frames** before
`CX_SY_NESTING_RECURSION` is raised. A degenerate union-find chain on a
mass-import TR (10 000 objects, all in one task chain) would crash the
analyser. The Python version does the same job iteratively with
path-compression in a `while` loop — which is what the ABAP method should
do.

**Fix:** convert to an iterative `WHILE`, same shape as the Python
`UnionFind.find` (verification/simulate_pipeline.py line 149-154).

### 🟠 P1 — `to_json` builds the payload by string concatenation

**File:** `zcl_gcts_tr_analyzer.clas.abap` line 643-728

For a TR with 1 000 edges this allocates ~10 000 short strings and re-copies
them repeatedly (string concatenation is O(N²) on most ABAP kernels).
ICF response timeout (30 s default) becomes reachable on big TRs.

**Fix:** use `string_table` + `concat_lines_of` (already imported elsewhere
in the same class for `mv_label`). Same algorithm, single allocation.

### 🟠 P1 — `deps_for_ddls` / `deps_for_ddlx` / `deps_for_bdef` are stubs

**File:** lines 415-434, all bodies are `RETURN`.

The project documents this honestly (line 417 comment) but every customer
running RAP / CDS will silently get incomplete results. The Eclipse view
will show "no edge" for an obvious CDS dependency.

**Fix:** implement using `DDLDEPENDENCY` (rename `dependent_object` /
`base_object` per release; check SE11 first). Behind a feature flag
controlled by `cl_abap_classdescr=>describe_by_name` so the code degrades
gracefully on releases that don't have the table.

### 🟠 P1 — `task_of_object` matches by `obj_name` only, ignoring object type

**File:** line 734-740

If two different object types share the same name (rare but legal — e.g. a
function group `ZFG_FOO` and a class `ZCL_FOO` use distinct namespaces, but
a class and a CDS view can collide on `ZSOMETHING`), the lookup returns the
first match.

**Fix:** the lookup key must be `(obj_type, obj_name)`. Today the call sites
already know the target type (it's a string literal in every `add_dep`), so
plumbing the type through is mechanical.

### 🟠 P1 — `ZGCTS_HIST` does not include a sequence column

**File:** `abap/src/zgcts_hist.tabl.xml` (referenced from analyser
line 887-899)

`run_ts` is the only ordering key. Two analyses at the same second clobber
each other. With multi-TR analysis, this is no longer a hypothetical: a
dev releasing 4 TRs in 4 seconds with `?persist=true` can lose rows.

**Fix:** add a `SEQNR` numeric column to the key. Existing rows migrate to
`SEQNR = 0`.

### 🟡 P2 — `mv_label` is reused as the "TR id" persisted to history

**File:** line 887

For multi-TR runs `mv_label = "DEVK900042,DEVK900043,..."`. That string is
written to a column typed `TRKORR` (10 chars) — which truncates without
warning. **Verified empirically?** No. The project has not run this on a
live system, so we don't know whether the SQL layer truncates or raises a
short-dump.

**Fix:** persist `mt_tasks` row-per-task instead of cramming the label into
one row's `tr_id`.

### 🟡 P2 — `iv_include_external` is wired through but only `add_external_dep`
references it

**File:** lines 96, 755-765

The `ext_*` edges feature is half-built — the analyser never calls
`add_external_dep` from any of the `deps_for_*` helpers. The flag is
effectively dead code.

**Fix:** either delete the flag and the method, or wire it into each
`deps_for_*` so external (out-of-input) dependencies are emitted.

### 🟡 P2 — Hard-coded `as4local = 'A'` in DDIC reads

**File:** lines 376, 400

`A` filter for "active version only" is correct for the analysis purpose,
but worth a one-line comment because it's not self-evident to a junior
ABAPer reading the code.

### 🟢 Verdict (SAP)

The class is **functionally complete for the scenarios that have been
simulated** (CLAS/INTF/TABL/DTEL/FUGR), but the two P0 defects break
correctness on real-world TR sizes, and the missing CDS/RAP extractors
make the tool partially blind on modern S/4HANA. **Do not pilot with real
TRs until the P0s are fixed**; they are 1-2 days of work.

---

# 2. Python Architect Review

**Reviewer hat:** Senior Python Architect (12+ yrs)
**Files in scope:** `TR dependency/verification/*.py`

## 2.1 Strengths

- ✅ **Pure Python 3, zero external dependencies** — runs anywhere a stock
  interpreter exists, including locked-down enterprise laptops with no PyPI
  access.
- ✅ Uses `dataclasses(frozen=True)` for `Obj` and `Edge` — equality,
  hashability, and immutability come for free.
- ✅ Type hints on every public function, `from __future__ import annotations`
  so PEP 604 syntax (`list[str]` etc.) would also work if the project
  upgrades.
- ✅ `__main__` guard, deterministic output, exit code reflects test result —
  CI-friendly.
- ✅ Algorithmic correctness is **separated** from the SAP-shaped fixture data:
  `simulate_pipeline.py` exposes pure functions (`stage2_dependencies`,
  `stage3_clusters`, etc.) that `mock_sap_data.py` re-uses by import. This
  is exactly the layering an architect would specify.
- ✅ The TR-level sequence uses **Tarjan's SCC** (correct algorithm choice
  for cycle detection in a directed graph) followed by **Kahn's topological
  sort** (correct for the resulting DAG). Both implementations are
  textbook-faithful.

## 2.2 Findings

### 🟠 P1 — No test runner; `assert` statements double as tests

**Files:** `simulate_pipeline.py` lines 370-436, `mock_sap_data.py` `main()`

`verify_*` and `run_scenario` functions raise `AssertionError` on failure.
Works, but:
- No `pytest` integration → no test discovery, no parameterisation, no
  coverage report.
- A failure in scenario 2 hides scenarios 3+ if the harness short-circuits
  (currently it does *not* — `main()` continues — good — but the design is
  fragile).
- No fixture marker → re-runs against the same data are not cached.

**Fix:** rename `simulate_pipeline.py::main` → `test_*` functions, drop in a
`conftest.py`, run via `pytest verification/`. ~30 LoC added, gives
coverage measurement and CI-grade output for free.

### 🟠 P1 — `_parent_tr` is O(N) per call; `tr_release_sequence` is therefore O(E·N)

**File:** `mock_sap_data.py` lines 313-318

```python
def _parent_tr(task_id: str) -> str:
    for r in E070_ROWS:
        if r["TRKORR"] == task_id:
            ...
```

Linear scan of `E070_ROWS` for every task in every edge. With N=10 000 tasks
and E=5 000 edges, that's 50 000 000 dict comparisons. The current fixture
has 12 rows so it's invisible.

**Fix:** build a one-time `dict[str, str]` of `task → parent_tr` at module
load. One line, O(1) lookup.

### 🟡 P2 — `tr_release_sequence` rebuilds the per-cluster reason text by
nested loop

**File:** `mock_sap_data.py` lines 421-432

```python
for a in trs:
    for b in trs:
        if a >= b: continue
        for k in (a, b), (b, a):
            for txt in edge_reasons.get(k, []):
                reasons.append(...)
```

O(|group|² × 2 × len(reasons)). Acceptable for groups of 2-3 TRs but
defensive coding would replace this with a dict of pre-collected reasons
keyed by group_id.

### 🟡 P2 — `Tarjan strongconnect` is recursive in Python (sys.setrecursionlimit)

**File:** `mock_sap_data.py` lines 360-388

Python's default recursion limit is 1 000. A deep dependency chain (1 000+
TRs along one chain) would raise `RecursionError`. Same shape as the ABAP
P0, just less likely because Python defaults to 1 000 vs ABAP's ~256.

**Fix:** iterative Tarjan using an explicit stack. The standard
`networkx.strongly_connected_components` does this and is one `pip install`
away — acceptable to take the dependency in a verification-only script.

### 🟡 P2 — Mock-data scripts share state via `sys.path` hack

**File:** `mock_sap_data.py` lines 43-49

```python
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from simulate_pipeline import (...)
```

Works, but a real Python project would put both files under a package
(`verification/__init__.py`) and use relative imports. Future readers will
fight this when running the tests from a different working directory.

### 🟡 P2 — `to_json` produces `version: "1.1"` here but ABAP `to_json`
emits `version: "1.1"` too — coupling is by string, not schema

**File:** `mock_sap_data.py` line 478, ABAP class line 724

There is no JSON Schema document. The two implementations agree only by
convention. The project's own Gap W4 is the same observation.

**Fix:** publish a `tr-analyser-schema.json` (Draft-2020-12) under
`verification/` and validate both producers against it in CI. ~50 LoC.

### 🟢 Verdict (Python)

The Python code is **clean, type-safe, and algorithmically correct**. The
weaknesses are operational (no pytest, no CI hook, no schema lock-in) not
correctness defects. Acceptable to ship as-is for verification purposes;
recommend wrapping in pytest before declaring it the project's regression
suite.

---

# 3. Java / Eclipse Architect Review

**Reviewer hat:** Senior Java / Eclipse RCP Architect
**Files in scope:** `TR dependency/eclipse/com.gmw.gcts.analyzer/src/**`

## 3.1 Strengths

- ✅ Modern `java.net.http.HttpClient` (JDK 11+), not the deprecated
  `HttpURLConnection`. Correct timeouts (`connectTimeout` and
  per-request `timeout`).
- ✅ Password is loaded from **Eclipse Secure Storage**
  (`SecurePreferencesFactory`) — the right place. Not stored in plain
  preferences.
- ✅ HTTP error handling distinguishes 401 / 403 / 404 with actionable user
  messages (`AnalyzerHttpClient.java` lines 99-111).
- ✅ `Thread.currentThread().interrupt()` is correctly re-raised on
  `InterruptedException` (line 125, 173) — interrupt-status preserved.
- ✅ Domain model (`AnalysisResult`) is **immutable** (`final` fields,
  `Collections.unmodifiableList`) — thread-safe by construction.
- ✅ Modern Java pattern usage: `switch` expression with arrows
  (`Cluster.riskLabel`, `PullStep.label`).

## 3.2 Findings

### 🔴 P0 — Hand-rolled JSON parser is unsafe and is the project's known-broken Gap E6

**File:** `AnalysisResult.java` lines 199-274 (`JsonReader`)

The parser uses `String.indexOf` to walk braces and quotes. Concrete
failure modes (already empirically confirmed in
`verify_json_contract.py::known_parser_limits`):

1. **Unicode escapes ignored.** Line 217-219:
   ```java
   .replace("\\\"", "\"")
   .replace("\\n", "\n")
   .replace("\\\\", "\\");
   ```
   No handling of `\uXXXX`, `\t`, `\r`, `\b`, `\f`, `\/`. A non-ASCII
   description from ABAP (e.g. `"detail":"Z_ÄÖÜ → Z_BAR"`) survives transit
   but a `\u00C4` in the wire produces literal text.

2. **`stringField` walks past the next `}` if the value contains `}`.** Line
   211-215 finds the closing `"` by skipping `\"`, but the simple
   `src.indexOf('"', q1 + 1)` followed by the back-walk does not handle
   strings that contain `}` characters (legal in JSON). A detail string
   containing `... { 5 of 6 } ...` will not crash but the **enclosing**
   `splitObjects` (line 264-273) will, because it counts `{` `}` literally
   without respecting strings.

3. **Numbers in scientific notation are silently zero.** Line 222-236
   accepts only `Character.isDigit`. Any `1e5`, `-1`, or `1.0` from a
   future ABAP serialiser becomes `0`.

4. **`null` values become empty string.** Line 208 `if (ki < 0) return null`
   is followed by `if (q1 < 0) return null` for missing fields, but a
   present `"foo": null` has no quotes after the colon, so the parser
   walks past it and returns the **next** string field's value.

This is the project's already-flagged Gap E6 (top-10 priority list, item 4).

**Fix:** replace with a real parser. Three viable choices, in order:

| Library | Pros | Cons |
|---|---|---|
| `org.json` | 70 KB, stable since 2009, MIT, no transitive deps | API is mutable, not type-safe |
| Jackson Databind | The de-facto JVM standard, can deserialise straight into `AnalysisResult` | 5 MB transitive footprint |
| Built-in `javax.json` (Jakarta JSON-P) | OSGi-native | Not present in stock Eclipse — needs a feature dependency |

For an Eclipse plugin the right answer is **`org.json`** as an OSGi bundle
(`org.json.simple` already ships in many Eclipse distributions). Adds a
single line to `MANIFEST.MF` `Require-Bundle`. ~80 LoC removed,
~50 LoC of typed binding added. Project-wide risk drops from "first prod
bug will be a parse error" to nil.

### 🔴 P0 — `analyze(String tr)` is the only public entry point — no multi-TR signature

**File:** `AnalyzerHttpClient.java` line 67

```java
public AnalysisResult analyze(String tr) { ... }
```

The ABAP backend already accepts comma-separated lists. The handler that
calls this client (`AnalyzeTRHandler`) prompts for one ID. Scenario B
(cross-TR) which the project's plan calls "the primary fix" is therefore
not exposed to the user, even though the wire is ready.

**Fix:** add `analyze(List<String> trIds)` that joins on `,` (URL-encoded)
and keep `analyze(String)` as a 1-line delegate
`return analyze(List.of(tr))`. Then update the dialog regex (`TrDetector`)
to permit a comma-separated list — the plan already defines
`TR_LIST_PATTERN`. This is project Gap E2 / A2, item #2 on the top-10.

### 🟠 P1 — `new AnalyzerHttpClient()` reads Secure Storage in the constructor

**File:** lines 43-57

Every `new AnalyzerHttpClient()` triggers `loadPassword()` which hits the
OS keychain. On macOS this can prompt the user to unlock the keychain;
on Linux it can prompt for the master password. Today the handler only
constructs the client once per analyse, but a future refactor (e.g.
"analyse all my open tasks") would create N clients in a loop and N
keychain probes.

**Fix:** lazy-initialise `authHeader` on first request, and cache.

### 🟠 P1 — `Job` (in `AnalyzeTRHandler`) does not check `monitor.isCanceled()`

**File:** `AnalyzeTRHandler.java` (project's own Gap E10)

User clicks Cancel → still waits for HTTP timeout. `analyze()` should
accept an `IProgressMonitor` and check it between the request build and
`httpClient.send`.

**Fix:** Java's `HttpClient` supports cancellation via
`CompletableFuture.cancel(true)`; rewrite the call site to use
`sendAsync` + `monitor.isCanceled()` polling.

### 🟠 P1 — No retry on transient `ConnectException`

**File:** `AnalyzerHttpClient.java` lines 115-119 (Gap E8)

SAP systems behind a Web Dispatcher sometimes drop the first request after
idle. Single failure → user sees error, retries manually.

**Fix:** wrap `httpClient.send` in a 1-retry loop with 1-second back-off.
~10 LoC.

### 🟠 P1 — Plain text `Authorization: Basic` over the wire — HTTPS not enforced

**File:** lines 43-57

`systemUrl` from preferences is used as-is. Nothing in the client rejects
`http://`. Basic auth over plain HTTP leaks the credential to anyone on
the network. Project's Gap S2.

**Fix:**
```java
if (this.systemUrl.startsWith("http://")) {
    throw new IllegalStateException(
        "Refusing to use HTTP: configure HTTPS in preferences");
}
```
Two lines, no functional impact for any correctly-configured customer.

### 🟡 P2 — `intField` returns `0` on parse failure (silent)

**File:** `AnalysisResult.java` line 234

```java
catch (NumberFormatException e) { return 0; }
```

Going to be replaced when the parser is replaced (P0), but worth flagging:
silent zero is worse than a thrown exception because it produces a
"successful" result with bad data.

### 🟡 P2 — `stringArray` splits on raw `,` and ignores commas inside string
values

**File:** lines 238-248

`"tasks":["A,B","C"]` would become `["A", "B", "C"]` — three elements
instead of two. Today the producer (ABAP) doesn't emit commas inside
strings, but the parser is still wrong. Same fix as P0 above.

### 🟡 P2 — Bundle SymbolicName / package still says `gcts` while user-facing
text says "TR Analyser"

**File:** `MANIFEST.MF`, all `package com.gmw.gcts.analyzer.*`

Project's Gap N2. Acceptable not to break installed customers (IDs must
not change in a minor release), but the next major release should rename.

### 🟡 P2 — `icons/dependency.png` is missing

**File:** `plugin.xml` references it; `icons/` only has `README.md`. Eclipse
renders the default broken-icon glyph. Cosmetic but visible. Project's
Gap E18 / Parity Gap E.

### 🟢 Verdict (Java)

The Eclipse plugin is **well-structured for an OSGi bundle** (clean
separation of UI / handler / client / model, immutable result, Eclipse
Secure Storage). Two P0 defects are blockers for production: the JSON
parser must be replaced, and the multi-TR API must be plumbed through. Both
are 1-day fixes. The remaining P1s are operational hardening that gates a
"production-ready" badge but not a pilot.

---

# 4. Cross-cutting recommendations (all three architects agree)

| # | Recommendation | Owner | Effort |
|---|---|---|---|
| 1 | Lock the JSON wire format with a published JSON Schema, validate both producers in CI | Python | 0.5 day |
| 2 | Introduce a contract-test harness that runs the Python simulator + the Eclipse plugin's parser against the same fixture, and asserts byte-equal output where applicable | Java + Python | 1 day |
| 3 | Add a single-page "before / after" demo deck (the cross-TR sequence) so non-developer stakeholders can see the value | All | 0.5 day |
| 4 | Live-system smoke test: deploy the abapGit repo to a sandbox, run the Eclipse plugin against it, prove `?tr=GMWK900800,DEVK900100` returns the expected JSON | SAP + Java | 1 day, but blocked on a sandbox tenant |
| 5 | Replace recursion with iteration (ABAP `uf_find`, Python `Tarjan`) | SAP + Python | 0.5 day |
| 6 | Switch ABAP `tasks` from string-with-commas to a typed table | SAP | 1 day |
| 7 | Make `AnalyzerHttpClient.analyze` accept `List<String>` | Java | 0.5 day |
| 8 | Replace JSON parser with `org.json` | Java | 0.5 day |

**Total to clear all P0 and the most painful P1s:** ~5 engineering days
across the three layers. After that the project is genuinely ready for a
piloted rollout with the original MVP scope (Phase 0 + Phase 1 from
`SOLUTION_ARCHITECTURE.md`).

---

# 5. What the three architects refuse to sign off on today

| Item | Reason |
|---|---|
| **Production rollout** | P0 ABAP correctness defects (cluster `CS` substring match, recursive `uf_find`) ⇒ wrong results on real-world TR sizes |
| **Public marketplace listing** | Java JSON parser will fail on the first non-ASCII description from a real customer |
| **A claim of cross-TR support to end users** | The wire is ready, the ABAP side accepts the list, but the Java client and the dialog UI both still take a single TR string |
| **A claim of "tested"** | Python scenarios pass, Tycho build is green, but no live-system smoke test has ever been executed (`VERIFICATION_REPORT.md` confirms) |

Once the eight items above are closed, the same three architects will
sign off on a **piloted rollout** to one developer team for two sprints,
with the explicit understanding that Phases 3-10 of the solution
architecture are still open.