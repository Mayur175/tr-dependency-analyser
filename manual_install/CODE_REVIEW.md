# Senior SAP Technical Architect Review — ABAP backend objects

**Reviewer hat:** Senior SAP Technical Architect / Basis lead, ~15 years.
**Scope:** the four objects in `manual_install/` (and their canonical source under
`abap/src/`) — the table `ZGCTS_DEP_HISTORY` and three classes
(`ZCL_GCTS_TR_ANALYZER`, `ZGCTS_ANALYZE_HANDLER`, `ZCL_GCTS_DEP_ATC_CHECK`).
**Style:** every finding cites the file/line, gives the SAP convention being
violated (or improved on), and rates **P0** (block prod) / **P1** (next sprint) /
**P2** (backlog).

This review is **complementary** to `ARCHITECT_REVIEW.md` (which covers the
broader project). Here I focus narrowly on the four objects' definitions and
public surface.

---

## 0. Naming length compliance (SAP standards)

Audited against the user's stated limits:

- **Database tables:** ≤ **10 characters**
- **Class names and method names:** ≤ **30 characters**

### 0.1 Verdict — one violation

| Category | Count audited | Pass | Fail |
|---|---|---|---|
| Database table | 1 | 0 | **1** ❌ |
| Class names | 3 | 3 | 0 ✅ |
| Method names (across all 3 classes) | 42 | 42 | 0 ✅ |
| Field names in `ZGCTS_DEP_HISTORY` | 12 | 12 | 0 ✅ (SAP DDIC field limit is 16) |

### 0.2 The violation — `ZGCTS_DEP_HISTORY` is 17 characters

```
Z G C T S _ D E P _ H I S T O R Y
1 2 3 4 5 6 7 8 9 10 11 12 13 14 15 16 17    ← 17 chars, exceeds 10-char limit
```

**This will fail the company naming standard and may also exceed limits on
older SAP releases (the classic `DD02L-TABNAME` limit is 16 chars; some
governance teams enforce 10).**

### 0.3 Proposed renames (pick one)

| Option | New name | Length | Notes |
|---|---|---|---|
| A | `ZGCTS_HIST` | 10 | Keeps the existing `ZGCTS_*` prefix; minimum churn |
| B | `ZTR_DEPHIS` | 10 | Cleaner — drops the misleading `gcts` prefix |
| C | `ZTR_DEP_H` | 9 | Shorter still |
| D | `ZTRDH` | 5 | Aggressive — fits any namespace policy but unreadable |

**Recommendation:** **Option A (`ZGCTS_HIST`)** for v1 (minimum-churn rename to
fit the 10-char limit immediately) and **Option B (`ZTR_DEPHIS`)** for v2 when
the broader rename to drop `gcts/GCTS` happens (see §5.1 below).

Either way, the rename touches three places:

1. `manual_install/01_ZGCTS_DEP_HISTORY.tabl.txt` — file content + filename.
2. `abap/src/zgcts_dep_history.tabl.xml` — `<TABNAME>` element.
3. `abap/src/zcl_gcts_tr_analyzer.clas.abap` — `persist_result` method
   (the `INSERT zgcts_dep_history FROM TABLE …` statement).

Plus filename of the manual-install file (e.g.
`01_ZGCTS_HIST.tabl.txt`).

### 0.4 Class-name audit (all PASS, ≤ 30 chars)

| Class | Length | Status |
|---|---|---|
| `ZCL_GCTS_TR_ANALYZER` | 20 | ✅ |
| `ZGCTS_ANALYZE_HANDLER` | 21 | ✅ |
| `ZCL_GCTS_DEP_ATC_CHECK` | 22 | ✅ |

### 0.5 Method-name audit (all PASS, ≤ 30 chars)

The longest method names across the three classes:

| Method | Class | Length |
|---|---|---|
| `pull_action_of_task` | `ZCL_GCTS_TR_ANALYZER` | 19 |
| `stage2_dependencies` | `ZCL_GCTS_TR_ANALYZER` | 19 |
| `pull_step_of_task` | `ZCL_GCTS_TR_ANALYZER` | 17 |
| `stage2b_conflicts` | `ZCL_GCTS_TR_ANALYZER` | 17 |
| `get_tr_for_object` | `ZCL_GCTS_DEP_ATC_CHECK` | 17 |
| `add_external_dep` | `ZCL_GCTS_TR_ANALYZER` | 16 |
| `stage1_inventory` | `ZCL_GCTS_TR_ANALYZER` | 16 |
| `get_message_text` | `ZCL_GCTS_DEP_ATC_CHECK` | 16 |
| `escape_json_str` | `ZGCTS_ANALYZE_HANDLER` | 15 |
| (every other method ≤ 14 chars) | | |

Comfortably under the 30-char limit. **No renames needed.**

### 0.6 Field-name audit on `ZGCTS_DEP_HISTORY` (all PASS, ≤ 16 chars)

The longest field name is `pull_action` at 11 chars; SAP's DDIC field-name
limit is 16. **No renames needed.** *(Note: the field types still need
fixing per §1.2 — that's a separate issue from naming length.)*

### 0.7 Summary action item

| Action | Severity | Effort |
|---|---|---|
| Rename `ZGCTS_DEP_HISTORY` → `ZGCTS_HIST` (or `ZTR_DEPHIS`) | **P0** (10-char limit blocker) | 30 min — three find-and-replace touches |

This single rename clears the entire naming-compliance audit.

---

## Executive summary

| Object | Naming | Types & lengths | Methods | Settings | Verdict |
|---|---|---|---|---|---|
| `ZGCTS_DEP_HISTORY` (table) | ⚠ "gcts" prefix is now misleading | 🔴 wrong types for nearly every column | n/a | ⚠ missing technical settings | **Needs P0+P1 rework** |
| `ZCL_GCTS_TR_ANALYZER` | ⚠ same prefix issue | 🔴 internal types are all `TYPE string` instead of typed | 🟠 some hidden races and recursive walks | ⚠ no exception classes raised | **Needs P0+P1 rework** |
| `ZGCTS_ANALYZE_HANDLER` | ⚠ same | 🟠 acceptable | 🟠 weak input validation | 🔴 cloud-incompatible interface | **Needs P0 (cloud port) + P1** |
| `ZCL_GCTS_DEP_ATC_CHECK` | ⚠ same | 🔴 references missing message class | 🔴 string slicing crashes on short input | 🔴 inherits deprecated framework | **Block — do not install yet** |

**The 3 most important fixes before any pilot rollout:**

1. **Replace generic `CHAR/STRING` columns with proper SAP data elements** in `ZGCTS_DEP_HISTORY` (TRKORR for transport ids, SOBJ_NAME for objects, TIMESTAMPL for timestamps, domain-backed data elements for risk/kind/action).
2. **Stop using `cl_demo_output`** in `stage4_output` of `ZCL_GCTS_TR_ANALYZER` — this is a debug API, not a production output channel.
3. **Either ship the `ZGCTS_DEP_MSG` message class** referenced by `ZCL_GCTS_DEP_ATC_CHECK`, or remove the ATC class from the install set until it exists.

---

# 1. Database table `ZGCTS_DEP_HISTORY`

**Source:** `manual_install/01_ZGCTS_DEP_HISTORY.tabl.txt` (DDL), `abap/src/zgcts_dep_history.tabl.xml` (canonical).

## 1.1 Strengths

- ✅ `tableCategory = #TRANSPARENT` — correct (one row per dep edge, no clustering).
- ✅ `deliveryClass = #A` — correct (application data, modifiable in customer system).
- ✅ `enhancement.category = #NOT_EXTENSIBLE` — appropriate for a log table; prevents customers extending the schema in unpredictable ways.
- ✅ `key client : abap.clnt not null` — first key field is client; correct multi-tenant pattern.
- ✅ `BUFALLOW = N` (in canonical `.tabl.xml`) — no buffering, correct for an append-only history.

## 1.2 Findings

### 🔴 P0.T1 — Generic types on transport-identity columns

Every TR / task / object column uses generic `abap.char(20)` or `abap.char(60)`:

```abap
key tr_id    : abap.char(20)  not null;   -- ❌ should be TRKORR
key src_task : abap.char(20)  not null;   -- ❌ should be TRKORR
key tgt_task : abap.char(20)  not null;   -- ❌ should be TRKORR
key src_obj  : abap.char(60)  not null;   -- ❌ should be (PGMID, OBJECT, OBJ_NAME)
key tgt_obj  : abap.char(60)  not null;   -- ❌ same
```

**Why this is wrong:**

- `TRKORR` is the SAP-released data element for transport request numbers — `CHAR(10)`. **Using `CHAR(20)` allocates twice the storage**, breaks alignment with every other transport-related table (E070, E071, etc.), and prevents joins / `FOR ALL ENTRIES` against those standard tables.
- An object identity in SAP is **three fields**, not one concatenated string: `PGMID` (`CHAR 4`), `OBJECT` (`CHAR 4`), `OBJ_NAME` (`CHAR 40` — data element `SOBJ_NAME`). Concatenating them into `"CLAS/ZCL_FOO"` saves SQL work but loses the ability to join to `E071`, breaks future indexing, and makes the column 60 chars when 48 suffice.
- The values stored will look fine to humans, but a future query like *"all conflicts on object ZTBL_CUSTOMER"* requires either a `LIKE '%ZTBL_CUSTOMER'` (full table scan) or string parsing in ABAP.

**Fix:**

```abap
key client      : abap.clnt        not null;
key tr_id       : trkorr           not null;   -- 10
key run_ts      : timestampl       not null;   -- standard TIMESTAMPL, dec(21,7)
key seqnr       : abap.numc(4)     not null;   -- NEW: sequence within run
key src_task    : trkorr           not null;
key src_pgmid   : pgmid            not null;
key src_object  : trobjtype        not null;
key src_obj_name: sobj_name        not null;
key tgt_task    : trkorr           not null;
key tgt_pgmid   : pgmid;
key tgt_object  : trobjtype;
key tgt_obj_name: sobj_name;
kind            : zde_dep_kind;    -- NEW domain-backed
risk            : zde_dep_risk;    -- NEW domain-backed
detail          : abap.char(200);
pull_step       : abap.int4;
pull_action     : zde_dep_action;  -- NEW domain-backed
```

### 🔴 P0.T2 — Wrong timestamp type

```abap
key run_ts : abap.dec(15,0) not null;   -- ❌
```

`run_ts` is meant to be "when this analysis ran". The correct SAP type is `TIMESTAMPL` (`DEC(21,7)`) — second-precision plus 7 fractional digits, the SAP standard for timestamps. Using `DEC(15,0)` means:

- You lose sub-second precision → two analyses in the same second clobber each other's primary key.
- The class manually computes `lv_date * 1000000 + lv_time` (analyser line 879) — re-implementing what `cl_abap_tstmp=>create` does for free.
- ABAP RAP / CDS will refuse to use this column for `lastChangedAt` annotations.

**Fix:** use `key run_ts : timestampl not null;` and rewrite `persist_result` to call `GET TIME STAMP FIELD lv_run_ts`.

### 🔴 P0.T3 — Missing sequence number in primary key

The primary key today is `(client, tr_id, run_ts, src_task, src_obj, tgt_task, tgt_obj)`. With `run_ts` at second precision, **two persists in the same second** with the same edge throw `SY-SUBRC = 4` on `INSERT`. With the 4-TR / 5-TR multi-input feature, this happens.

**Fix:** add `key seqnr : abap.numc(4) not null;` and let `persist_result` populate it sequentially (1, 2, 3, … per run). Or move `run_ts` to TIMESTAMPL (P0.T2) which gets you sub-microsecond uniqueness for free.

### 🟠 P1.T4 — `kind`, `risk`, `pull_action` should be domain-backed data elements

Today these are free-form strings. The class writes well-defined values
(`CRITICAL`, `HIGH`, `MEDIUM`, `NONE`, `IMPLEMENTS`, `INHERITS`, `TYPE_REF`,
`USES`, `CALLS`, `CONFLICT`, `COORDINATE`, `TOGETHER`, `TOGETHER_RECOMMENDED`,
`ALONE`). A typo in the analyser will silently store an invalid value — no
DDIC check fails it.

**Fix:** create domains with fixed values:

```abap
" Domain ZDOM_DEP_KIND   CHAR 20  values: IMPLEMENTS, INHERITS, TYPE_REF, USES, CALLS, CONFLICT
" Domain ZDOM_DEP_RISK   CHAR 10  values: CRITICAL, HIGH, MEDIUM, NONE
" Domain ZDOM_DEP_ACTION CHAR 30  values: COORDINATE, TOGETHER, TOGETHER_RECOMMENDED, ALONE
```

Then data elements `ZDE_DEP_KIND`, `ZDE_DEP_RISK`, `ZDE_DEP_ACTION` typed by the
domains. Wrong values are caught at `INSERT` time with `SY-SUBRC = 4` — no
silent corruption.

### 🟠 P1.T5 — `detail` length and missing search field

`detail : abap.char(200);` — fine for the human-readable text, but:

- No index on `(src_obj, tgt_obj)` for "find all dependencies on object X"
  queries.
- The detail string is duplicated across multiple rows of the same edge across
  runs → wastes space.

**Fix:** add a secondary index in the DDL:

```abap
@AbapCatalog.indexes : [{
  fields: ['SRC_OBJ_NAME', 'TGT_OBJ_NAME'],
  unique: false
}]
```

(Not strictly P0; left as P1 because the table is small.)

### 🟡 P2.T6 — Missing technical settings annotations

Modern DDL supports:

```abap
@AbapCatalog.tableCategory : #TRANSPARENT
@AbapCatalog.deliveryClass : #A
@AbapCatalog.dataMaintenance : #LIMITED
@AbapCatalog.preserveKey : 'true'                   -- recommended for log tables
@AbapCatalog.compatibilityVersion : 1               -- ABAP cloud requirement
```

Today `01_ZGCTS_DEP_HISTORY.tabl.txt` is missing `preserveKey` and
`compatibilityVersion`. On BTP ABAP Environment activation will warn or fail
without `compatibilityVersion`.

### 🟡 P2.T7 — Missing `LOG_DATA_CHANGES` / change documentation

For a history table this is debatable — SAP's convention is to **not** log
changes on a log table (would log the logs). Just calling out that this was
deliberately omitted; OK as-is.

---

# 2. Class `ZCL_GCTS_TR_ANALYZER`

**Source:** `manual_install/02_ZCL_GCTS_TR_ANALYZER.clas.txt` (912 lines).
**Canonical:** `abap/src/zcl_gcts_tr_analyzer.clas.abap`.

## 2.1 Naming

### 🟠 P1.A1 — `gcts` prefix is now misleading

The tool started gCTS-only and is now generalised to classic CTS too. The
class name carries that history:

| Today | Honest name |
|---|---|
| `ZCL_GCTS_TR_ANALYZER` | `ZCL_TR_DEP_ANALYZER` or `ZCL_TR_ANALYSER_CORE` |
| `ZGCTS_ANALYZE_HANDLER` | `ZTR_ANALYSER_HTTP_HANDLER` |
| `ZGCTS_DEP_HISTORY` | `ZTR_DEP_HISTORY` |
| `ZCL_GCTS_DEP_ATC_CHECK` | `ZCL_TR_DEP_ATC_CHECK` |

This is a **breaking rename** — must wait for v2. For v1 just live with the
inconsistency. Document it.

### ✅ Method names follow SAP convention

- Public: `to_json`, `to_csv`, `persist_result`, `constructor` — verbs, lowercase, OK.
- Private: `stage1_inventory`, `stage2_dependencies`, etc. — descriptive, OK.
- Helpers: `add_dep`, `task_of_object`, `pull_step_of_task` — OK.

## 2.2 Data types

### 🔴 P0.A2 — Internal types use `string` everywhere instead of typed DDIC

**Lines 57-71:**

```abap
TYPES: BEGIN OF ty_object,
         task_id  TYPE string,    -- ❌ should be TRKORR
         obj_type TYPE string,    -- ❌ should be a fixed structure (PGMID + OBJECT)
         obj_name TYPE string,    -- ❌ should be SOBJ_NAME
       END OF ty_object.

TYPES: BEGIN OF ty_dep,
         source_task   TYPE string,    -- ❌ TRKORR
         source_object TYPE string,    -- ❌ structured
         target_task   TYPE string,    -- ❌ TRKORR
         target_object TYPE string,    -- ❌ structured
         kind          TYPE string,    -- ❌ ZDE_DEP_KIND
         detail        TYPE string,
       END OF ty_dep.
```

**Why this matters:** every comparison (`IF s1 = s2`, `READ TABLE … WITH KEY`)
becomes a Unicode string compare instead of a fast fixed-length compare. For
a TR with 5 000 edges this is measurable. Also means SQL `FOR ALL ENTRIES IN
mt_objects WHERE trkorr = mt_objects-task_id` won't work — needs a CONV.

**Fix:** rewrite as

```abap
TYPES: BEGIN OF ty_object,
         task_id  TYPE trkorr,
         pgmid    TYPE pgmid,
         object   TYPE trobjtype,
         obj_name TYPE sobj_name,
       END OF ty_object.
```

### 🔴 P0.A3 — `mv_label` collects all input ids into one string

**Line 89, 251:**

```abap
DATA mv_label TYPE string.
...
mv_label = concat_lines_of( table = lt_label_parts sep = `,` ).
```

For a 10-TR multi-input run, `mv_label` becomes
`"GMWK900691,DEVK900042,DEVK900043,…"` (~110 chars). It's then used as
`tr_id` in `persist_result` (line 887):

```abap
tr_id = mv_label
```

That column is `CHAR(20)` (or `TRKORR` if we apply P0.T1). **The string is
silently truncated**. Worse, if the column type is `TRKORR`, the truncation
might happen at column-assign time without `SY-SUBRC` being set.

**Fix:** persist **one row per task** with that task's `trkorr` in `tr_id`,
not the concatenated label.

### 🟠 P1.A4 — `mt_tasks` is `STANDARD TABLE WITH EMPTY KEY`

**Line 94:**

```abap
DATA mt_tasks TYPE STANDARD TABLE OF trkorr WITH EMPTY KEY.
```

The class reads it many times via `LOOP` and `FOR ALL ENTRIES`. Empty key
means every `READ TABLE WITH KEY table_line = …` is O(N). For a 10-TR pull
that's negligible; for a 1 000-task gCTS mass-import it matters.

**Fix:**

```abap
DATA mt_tasks TYPE SORTED TABLE OF trkorr WITH UNIQUE KEY table_line.
```

Same memory, O(log N) lookup.

## 2.3 Static class data — the race condition (P0)

### 🔴 P0.A5 — `gv_tr_id` and `gv_include_external` are CLASS-DATA

**Lines 31, 34:**

```abap
CLASS-DATA gv_tr_id TYPE string.
CLASS-DATA gv_include_external TYPE abap_bool VALUE abap_false.
```

On the SAP application server, **two ICF requests sharing the same work
process can overwrite each other's `gv_tr_id`** between `ICF set → constructor`
and `analyser run`. Production correctness defect.

**Fix:** delete both class-data attributes. The constructor already accepts
`it_input` — make that the only path. The legacy fallback (lines 187-189)
that reads the static must go.

```abap
" REMOVE entirely:
CLASS-DATA gv_tr_id TYPE string.
CLASS-DATA gv_include_external TYPE abap_bool VALUE abap_false.

" In constructor REMOVE:
ELSEIF gv_tr_id IS NOT INITIAL.
  APPEND VALUE #( id = CONV trkorr( gv_tr_id ) ) TO lt_input.
  mv_include_external = gv_include_external.
```

This is the same Gap A1 already in `ARCHITECT_REVIEW.md`.

## 2.4 Methods

### 🔴 P0.A6 — `uf_find` is recursive

**Lines 560-572:**

```abap
METHOD uf_find.
  TRY.
      DATA(ls_node) = ct_uf[ task = iv_task ].
      IF ls_node-parent = ls_node-task.
        rv_root = ls_node-task.
      ELSE.
        rv_root = uf_find( EXPORTING iv_task = ls_node-parent ... ).   -- recursive
        ct_uf[ task = iv_task ]-parent = rv_root.
      ENDIF.
  CATCH cx_sy_itab_line_not_found.
    ...
```

ABAP allows ~256 stack frames before `CX_SY_NESTING_RECURSION`. A degenerate
union-find chain on a mass-import TR (10 000 objects, all in one chain)
crashes. The Python version is iterative.

**Fix:** rewrite iteratively (path compression in a `WHILE`).

### 🔴 P0.A7 — Cluster store uses comma-string + `CS` substring test

**Lines 80-85, 530, 617, 671:**

```abap
TYPES: BEGIN OF ty_cluster,
         root  TYPE string,
         tasks TYPE string,        -- ❌ comma-joined
         risk  TYPE string,
       END OF ty_cluster.

" Lines 530, 617, 671 — substring check
IF ls_cl-tasks CS ls_dep-source_task.
```

`CS` is **substring**. `'GMWK900691,GMWK900692' CS 'GMWK90069'` is `TRUE`.
With real TRs sharing 9-character prefixes, every cluster check produces
false positives.

**Fix:** make `tasks` a `STANDARD TABLE OF trkorr` and replace `CS` with
`line_exists`.

### 🔴 P0.A8 — `stage4_output` writes to `cl_demo_output`

**Line 809:**

```abap
METHOD out.
  cl_demo_output=>write_text( iv_text ).
ENDMETHOD.
```

`cl_demo_output` is **for SAP demos**. It writes to a session-bound HTML
buffer that the ICF runtime cannot consume reliably and that doesn't exist
on BTP ABAP Environment. On Public Cloud activation will fail.

The good news is that none of the HTTP path actually consumes `out(…)` —
`to_json` and `to_csv` build the output strings directly. The `out(…)` calls
are only executed when the analyser is invoked from `cl_demo_output` /
`SE38` (a debug path).

**Fix:** delete the `out` method and all its call sites; or guard them
behind a feature flag (`mv_debug_output`) that's off by default.

### 🟠 P1.A9 — `task_of_object` matches on name only

**Line 734-740:**

```abap
METHOD task_of_object.
  TRY.
      rv_task = mt_objects[ obj_name = iv_name ]-task_id.
  CATCH cx_sy_itab_line_not_found.
      rv_task = ''.
  ENDTRY.
ENDMETHOD.
```

Two different SAP object types (`CLAS ZCL_FOO`, `INTF ZIF_FOO`) can never
collide because `Z*` namespace separates them, but `ZTBL_X` (table) and
`ZTBL_X` (CDS view) are perfectly possible. The lookup returns the first
match — wrong.

**Fix:** key on `(obj_type, obj_name)`. The call sites already know the
target type, so plumbing it through is mechanical.

### 🟠 P1.A10 — No exception classes raised, only swallowed

Every `CATCH cx_root` is silent (e.g. `deps_for_fugr` line 454,
`add_external_dep` line 766). A failing `xco_cp_*` call returns no edges and
no log entry — the user sees an analysis that is silently incomplete.

**Fix:** raise typed exceptions or at minimum write to `cl_application_log`
so the failure is recoverable post-hoc.

### 🟠 P1.A11 — `to_json` / `to_csv` build via `&&`

For a TR with 1 000 edges this allocates ~10 000 short strings and re-copies
them. ABAP `&&` is O(N²) on most kernels.

**Fix:** use `string_table` + `concat_lines_of`. Same idiom is already used
for `mv_label` at line 251.

### 🟡 P2.A12 — `deps_for_ddls`/`deps_for_ddlx`/`deps_for_bdef` are `RETURN` stubs

Documented as "skipped here; handled by Phase 2". RAP / CDS customers will
silently get incomplete results. Acceptable to ship with this gap **only if
the user-facing output explicitly tells the developer "CDS analysis not
performed"**. Today it doesn't.

## 2.5 Settings on the class

- `PUBLIC FINAL CREATE PUBLIC` — correct (no subclassing, public construction).
- No `RAISING` clauses on any method — should declare `RAISING cx_*` on the
  public methods if any exception flow is ever expected. Today everything is
  swallowed (P1.A10).
- No `cl_abap_unit_assert` test methods. **Zero ABAP Unit coverage** on a 912-line class.

---

# 3. Class `ZGCTS_ANALYZE_HANDLER`

**Source:** `manual_install/03_ZGCTS_ANALYZE_HANDLER.clas.txt` (251 lines).

## 3.1 Strengths

- ✅ Implements `IF_HTTP_EXTENSION` with the correct request-flow shape
  (auth → parse → validate → run → respond).
- ✅ All responses set `Cache-Control: no-store` and
  `X-Content-Type-Options: nosniff`.
- ✅ The whole analyser invocation is wrapped in `TRY ... CATCH cx_root` →
  HTTP 500 with JSON body.
- ✅ Input regex `[A-Z0-9]{3,4}K[0-9]{6}` matches the SAP TR/task convention.
- ✅ The `c_enforce_auth` toggle has a security-critical comment block.

## 3.2 Findings

### 🔴 P0.H1 — `IF_HTTP_EXTENSION` not allowed on Public Cloud / BTP ABAP Environment

ADT cloud-restricted editions reject `IF_HTTP_EXTENSION`. Activation will
say *"Interface IF_HTTP_EXTENSION is not released for cloud development"*.

**Fix:** add a parallel class `ZGCTS_ANALYZE_HANDLER_CLOUD` implementing
`IF_HTTP_SERVICE_EXTENSION` (the released cloud HTTP API). Keep both — the
ABAP build picks the right one based on edition feature flags.

### 🔴 P0.H2 — Default `c_enforce_auth = abap_false` (security)

```abap
CONSTANTS c_enforce_auth TYPE abap_bool VALUE abap_false.
```

This was changed at the user's request to unblock pilot. **It must NOT ship
to QA / PROD with this default.**

**Fix:** before promoting the TR upward, change to `abap_true`. The header
comment already says this; enforce via:
- a CTS pre-release BAdI that scans for the literal `abap_false`
- or an ATC check in the customer's profile

Also documented in the file header (lines 41-65). Acceptable for sandbox.

### 🟠 P1.H3 — `is_truthy` doesn't accept SAP's `'X'` convention

```abap
METHOD is_truthy.
  rv_yes = xsdbool( lv = '1' OR lv = 'true' OR lv = 'yes' ).
ENDMETHOD.
```

SAP convention for ABAP boolean is `X` and `' '`. A user typing
`?persist=X` will be ignored.

**Fix:** add `OR lv = 'x'` (lowercase, since `to_lower` is applied before).

### 🟠 P1.H4 — `escape_json_str` doesn't escape control chars

Only escapes `\`, `"`, newline, CR/LF. Missing: `\b`, `\f`, `\t`, and
`\u00XX` for any character < 0x20. A class description containing a tab
would break the JSON.

**Fix:** loop bytes < 0x20 and escape as `\u00XX`. Same gap on the Java side.

### 🟠 P1.H5 — No CSRF token check on `?persist=true`

`?persist=true` writes to `ZGCTS_DEP_HISTORY`. SAP standard says state-changing
GET endpoints should require `X-CSRF-Token`. Today an attacker who has the
target user's session cookie can flood the table.

**Fix:** require `X-CSRF-Token: Fetch` on first call, return token, validate
on subsequent `?persist=true` calls. ~30 LoC.

### 🟡 P2.H6 — `iv_format` validation is loose

```abap
DATA(lv_format) = to_lower( server->request->get_form_field( c_param_format ) ).
IF lv_format IS INITIAL. lv_format = 'json'. ENDIF.
" then:
IF lv_format = 'csv'. ... ELSE. ... ENDIF.
```

A user typing `?format=xml` silently gets JSON. Acceptable behaviour but a
log entry would help.

---

# 4. Class `ZCL_GCTS_DEP_ATC_CHECK`

**Source:** `manual_install/04_ZCL_GCTS_DEP_ATC_CHECK.clas.txt` (174 lines)
plus `04_ZCL_GCTS_DEP_ATC_CHECK.locals.txt` (109 lines).

## 4.1 Strengths

- ✅ Uses ATC priority mapping `CRITICAL→1`, `HIGH→2`, `MEDIUM→3` — sensible.
- ✅ All exception flows wrapped (lines 121-128, 152-154, 170-171) — the
  ATC framework forbids unhandled exceptions in checks.
- ✅ `get_message_text` redefined to provide a description.

## 4.2 Findings

### 🔴 P0.C1 — Inherits `cl_ci_test_root` (deprecated framework)

```abap
CLASS zcl_gcts_dep_atc_check DEFINITION
  PUBLIC
  INHERITING FROM cl_ci_test_root      -- ❌ Code Inspector legacy
  FINAL
  CREATE PUBLIC.
```

`cl_ci_test_root` is the **old Code Inspector** framework. Modern ATC uses
`cl_ci_test` (or for cloud: `cl_atc_test`). On BTP ABAP Environment
`cl_ci_test_root` is not released; the class will fail to activate.

**Fix:** rebase onto the modern framework:

```abap
CLASS zcl_tr_dep_atc_check DEFINITION
  PUBLIC
  INHERITING FROM cl_ci_test
  FINAL
  CREATE PUBLIC.
```

Then implement `if_ci_test~run` (or the cloud-specific equivalent). Several
methods will need re-signing.

### 🔴 P0.C2 — References missing message class `ZGCTS_DEP_MSG`

```abap
CONSTANTS:
  c_msg_critical TYPE string VALUE '001',
  c_msg_high     TYPE string VALUE '002',
  c_msg_medium   TYPE string VALUE '003',
  c_msg_ok       TYPE string VALUE '004'.
...
raise_finding( ... iv_msg_id = 'ZGCTS_DEP_MSG' ... ).
```

**There is no `ZGCTS_DEP_MSG` message class in the repo.** When this class
runs, `iv_msg_id = 'ZGCTS_DEP_MSG'` references a non-existent SE91 object.
The ATC finding will say *"Message text not found"* on every line.

**Fix:** ship `ZGCTS_DEP_MSG` as a fifth object. Manual-install file would be
e.g. `05_ZGCTS_DEP_MSG.msag.txt`. Until then, this class **must not be
installed**.

### 🔴 P0.C3 — String slicing `iv_detail(50)` and `iv_detail+50(50)` crashes on short input

```abap
errmsgv1 = iv_detail(50)
errmsgv2 = iv_detail+50(50)
```

If `iv_detail` is shorter than 100 characters (very common — most details are
20-80 chars), `iv_detail+50(50)` raises `CX_SY_RANGE_OUT_OF_BOUNDS`. The
`CATCH cx_root` on line 170 silently swallows it but the finding is lost.

**Fix:**

```abap
errmsgv1 = substring( val = iv_detail off = 0  len = nmin( val1 = strlen( iv_detail ) val2 = 50 ) ).
errmsgv2 = COND #( WHEN strlen( iv_detail ) > 50
                   THEN substring( val = iv_detail off = 50
                                   len = nmin( val1 = strlen( iv_detail ) - 50 val2 = 50 ) )
                   ELSE '' ).
```

### 🟠 P1.C4 — Uses the static `gv_tr_id` legacy path

```abap
ZCL_GCTS_TR_ANALYZER=>GV_TR_ID = lv_tr.
DATA(lo_analyzer) = NEW zcl_gcts_tr_analyzer( ).
```

Same race condition as P0.A5 in the analyser. Should pass `it_input`
explicitly:

```abap
DATA(lo_analyzer) = NEW zcl_gcts_tr_analyzer(
  it_input = VALUE #( ( id = CONV trkorr( lv_tr ) ) ) ).
```

### 🟠 P1.C5 — JSON re-parsing in the local class is wasteful

`lcl_atc_json_reader` parses JSON that the analyser just produced. The
analyser's internal data is right there — just expose it via a getter
(`get_clusters( )`) and skip JSON entirely.

**Fix:** add `METHODS get_clusters RETURNING VALUE(rt_clusters) TYPE tt_clusters`
to the analyser, drop the JSON parser in the ATC check.

### 🟡 P2.C6 — `xco_cp_cts=>transports->where(…)` syntax assumed, not verified

```abap
DATA(lt_trs) = xco_cp_cts=>transports->where(
  VALUE #( ( xco_cp_cts=>transport_request_filter->object(
               pgmid    = is_object-pgmid
               obj_type = is_object-object
               obj_name = is_object-obj_name ) ) ) )->all( ).
```

The exact factory syntax `xco_cp_cts=>transport_request_filter->object(...)`
hasn't been verified against any specific release — this is best-effort
written from memory of XCO's filter pattern. May activate, may not.

**Fix:** verify on a sandbox tenant. If the syntax differs, replace with the
correct release-specific form.

---

# 5. Cross-cutting findings

## 5.1 Naming consistency across all four objects

| Today | Proposal for v2 |
|---|---|
| `ZGCTS_DEP_HISTORY` | `ZTR_DEP_HISTORY` |
| `ZCL_GCTS_TR_ANALYZER` | `ZCL_TR_DEP_ENGINE` |
| `ZGCTS_ANALYZE_HANDLER` | `ZCL_TR_DEP_HTTP_HANDLER` |
| `ZCL_GCTS_DEP_ATC_CHECK` | `ZCL_TR_DEP_ATC_CHECK` |
| `ZGCTS_DEP_MSG` (missing) | `ZTR_DEP_MSG` |

**Acceptable for v1** to keep the inconsistent names (renaming is breaking).
**Mandatory for v2.**

## 5.2 No data dictionary objects (data elements / domains) for the analyser's
own vocabulary

A proper SAP design would ship:

| Object | Type | Purpose |
|---|---|---|
| `ZDOM_DEP_KIND` | Domain | Fixed values: IMPLEMENTS / INHERITS / TYPE_REF / USES / CALLS / CONFLICT |
| `ZDOM_DEP_RISK` | Domain | Fixed values: CRITICAL / HIGH / MEDIUM / NONE |
| `ZDOM_DEP_ACTION` | Domain | Fixed values: COORDINATE / TOGETHER / TOGETHER_RECOMMENDED / ALONE |
| `ZDE_DEP_KIND` | Data element on the domain | Used by `ty_dep-kind`, `ZGCTS_DEP_HISTORY-kind` |
| `ZDE_DEP_RISK` | Data element on the domain | Same |
| `ZDE_DEP_ACTION` | Data element on the domain | Same |
| `ZDE_DEP_CLUSTER_LBL` | Data element CHAR 30 | Cluster label |

Today the analyser uses `string` everywhere → no DDIC enforcement, no F1
help, no T100 message integration.

## 5.3 No T100 / message class

Beyond the missing `ZGCTS_DEP_MSG` for the ATC check, **all** error texts in
the codebase are English-only literals:

```abap
'Forbidden: S_TRANSPRT (TTYPE=CUST, ACTVT=03) is required.'
'Missing query parameter: tr'
'No SAP system URL configured.'
```

SAP convention is `MESSAGE eXXX(Z_MSG_CLASS) WITH …`. For an enterprise
rollout, T100 messages with translations are required.

## 5.4 No ABAP Unit tests

Zero `cl_abap_unit_assert` calls in the entire codebase. For a class that
implements core production logic (cluster detection, topo sort), ABAP Unit
should cover at minimum:

- `uf_find` / `uf_union` round-trip on a 5-task chain
- `stage2b_conflicts` detecting a deliberate same-object conflict
- `to_json` round-trip producing a parseable JSON document

These are deterministic, easy-to-write tests. Without them, every refactor
is uninsured.

---

# 6. The fix list, ordered by priority

## Must fix before any pilot beyond a single sandbox

| # | File | Issue | Severity |
|---|---|---|---|
| 1 | `ZGCTS_DEP_HISTORY` | Use TRKORR / SOBJ_NAME / TIMESTAMPL / domain-backed data elements instead of generic `CHAR(N)` | P0 |
| 2 | `ZCL_GCTS_TR_ANALYZER` | Delete `CLASS-DATA gv_tr_id` and `gv_include_external` (race condition) | P0 |
| 3 | `ZCL_GCTS_TR_ANALYZER` | `tasks` field of cluster: change from comma-string to typed table; fix `CS` substring matches | P0 |
| 4 | `ZCL_GCTS_TR_ANALYZER` | `uf_find` recursive → iterative | P0 |
| 5 | `ZCL_GCTS_TR_ANALYZER` | Stop using `cl_demo_output`; production HTTP path doesn't need it anyway | P0 |
| 6 | `ZGCTS_ANALYZE_HANDLER` | `IF_HTTP_EXTENSION` won't activate on BTP ABAP Env → ship parallel `*_CLOUD` class on `IF_HTTP_SERVICE_EXTENSION` | P0 |
| 7 | `ZGCTS_ANALYZE_HANDLER` | Default `c_enforce_auth` to `abap_true` before any non-sandbox release | P0 |
| 8 | `ZCL_GCTS_DEP_ATC_CHECK` | Either ship the missing `ZGCTS_DEP_MSG` message class or remove the ATC check from the install set | P0 |
| 9 | `ZCL_GCTS_DEP_ATC_CHECK` | Inherits deprecated `cl_ci_test_root` → migrate to modern ATC framework | P0 |
| 10 | `ZCL_GCTS_DEP_ATC_CHECK` | `iv_detail(50)` / `+50(50)` crashes on short detail strings | P0 |

## Next sprint (P1)

| # | File | Issue |
|---|---|---|
| 11 | `ZCL_GCTS_TR_ANALYZER` | Internal `ty_object` / `ty_dep` use typed DDIC fields, not strings |
| 12 | `ZCL_GCTS_TR_ANALYZER` | `task_of_object` keys on (type, name) not name-only |
| 13 | `ZCL_GCTS_TR_ANALYZER` | `to_json`/`to_csv` use `string_table` + `concat_lines_of` (perf) |
| 14 | `ZCL_GCTS_TR_ANALYZER` | Error logging via `cl_application_log` instead of swallow |
| 15 | `ZGCTS_ANALYZE_HANDLER` | `is_truthy` accepts `'X'` |
| 16 | `ZGCTS_ANALYZE_HANDLER` | `escape_json_str` handles control chars (`\b`, `\f`, `\t`, `\u00XX`) |
| 17 | `ZGCTS_ANALYZE_HANDLER` | CSRF token enforcement on `?persist=true` |
| 18 | All | Add ABAP Unit tests for `uf_find`, `stage2b_conflicts`, `to_json` round-trip |

## Backlog (P2)

| # | File | Issue |
|---|---|---|
| 19 | `ZGCTS_DEP_HISTORY` | Add secondary index on `(SRC_OBJ_NAME, TGT_OBJ_NAME)` |
| 20 | `ZGCTS_DEP_HISTORY` | Add `@AbapCatalog.compatibilityVersion : 1` annotation |
| 21 | `ZCL_GCTS_TR_ANALYZER` | Implement DDLS / DDLX / BDEF extractors |
| 22 | `ZCL_GCTS_DEP_ATC_CHECK` | Verify XCO `transport_request_filter->object(...)` syntax on real release |
| 23 | All | Migrate hard-coded English strings to T100 message class |
| 24 | All | Rename `gcts/GCTS` to neutral prefix in v2 |

---

# 7. What's acceptable to install today (sandbox / pilot)

If the user is on a personal sandbox, accepting the audit caveats above:

| Object | Install today? |
|---|---|
| `ZGCTS_DEP_HISTORY` | ⚠ Yes for sandbox, but plan a rebuild before QA |
| `ZCL_GCTS_TR_ANALYZER` | ⚠ Yes for sandbox; static-data race (P0.A5) and `CS` substring (P0.A7) defects can fire on real-world TRs but won't on small pilots |
| `ZGCTS_ANALYZE_HANDLER` | ⚠ Yes for sandbox if the edition supports `IF_HTTP_EXTENSION`; otherwise won't activate |
| `ZCL_GCTS_DEP_ATC_CHECK` | ❌ **Do not install yet** (missing `ZGCTS_DEP_MSG`, deprecated parent class) |

For QA / PROD, the P0 list above must be cleared first.

---

# 8. What I'd actually recommend

1. **Today / sandbox:** install table + analyser + HTTP handler. Skip the ATC class until P0.C1 / P0.C2 / P0.C3 are fixed. Test the analysis flow end-to-end on a real TR.
2. **Within 1 week:** address P0.A5 (race), P0.A7 (substring), P0.A8 (cl_demo_output), P0.H2 (default auth=true after pilot). Push to GitHub.
3. **Within 2-3 weeks:** rebuild `ZGCTS_DEP_HISTORY` with proper SAP types (P0.T1, P0.T2, P0.T3) — small data migration script needed if the table already has rows.
4. **Within 4 weeks:** ship the cloud-compatible `_CLOUD` handler, the modernised ATC class, and the missing `ZGCTS_DEP_MSG` message class.
5. **v2:** rename to `Z*TR_DEP_*` prefix, add proper data elements / domains, T100 messages, ABAP Unit tests.

This makes the tool genuinely production-ready in roughly 4 weeks of focused
work, building on what's already on GitHub today. Until then it's a working
sandbox tool with documented sharp edges — which is exactly what the README
and `ARCHITECT_REVIEW.md` already say.