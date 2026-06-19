# Mock SAP Data Simulation — TR Analyser

This document captures a **deterministic, runnable simulation** of the
TR Analyser pipeline against mock data shaped exactly like the real SAP tables
(`E070`, `E071`, `SEOMETAREL`, `DD03L`, `DD04L`, `TFDIR`).

It answers the user's request:

> *"Create sample data of SAP — one TR has multiple tasks and the same object
> has been locked in different tasks of the same TR. Second example: multiple
> TRs with dependent objects locked. Create mock data and simulate."*

To re-run:

```bash
cd "TR dependency/verification"
python3 mock_sap_data.py
```

Exits 0 on success. Both scenarios PASS today.

---

## Mock SAP tables

### `E070` (transport request header)

| TRKORR | FUNC | STAT | PARENT (`STRKORR`) | OWNER |
|---|---|---|---|---|
| **GMWK900800** | K | D |  | I_ALICE |
| GMWK900801 | S | D | GMWK900800 | I_ALICE |
| GMWK900802 | S | D | GMWK900800 | I_BOB |
| GMWK900803 | S | D | GMWK900800 | I_CAROL |
| **DEVK900100** | K | D |  | I_DAN |
| DEVK900110 | S | D | DEVK900100 | I_DAN |
| **DEVK900101** | K | D |  | I_EVE |
| DEVK900111 | S | D | DEVK900101 | I_EVE |
| **DEVK900102** | K | D |  | I_FRANK |
| DEVK900112 | S | D | DEVK900102 | I_FRANK |
| **DEVK900103** | K | D |  | I_GINA |
| DEVK900113 | S | D | DEVK900103 | I_GINA |

`FUNC=K` is "Workbench TR", `FUNC=S` is "Repair task".

### `E071` (objects locked in each task)

| TRKORR | POS | PGMID | OBJECT | OBJ_NAME |
|---|---|---|---|---|
| GMWK900801 | 001 | R3TR | CLAS | **ZCL_ORDER_API** |
| GMWK900801 | 002 | R3TR | INTF | ZIF_ORDER |
| GMWK900802 | 001 | R3TR | CLAS | **ZCL_ORDER_API** ⚠ same as above |
| GMWK900802 | 002 | R3TR | TABL | ZTBL_ORDER_HEADER |
| GMWK900803 | 001 | R3TR | CLAS | ZCL_ORDER_REPORT |
| DEVK900110 | 001 | R3TR | TABL | ZTBL_CUSTOMER |
| DEVK900111 | 001 | R3TR | DTEL | ZDE_CUSTID |
| DEVK900112 | 001 | R3TR | CLAS | ZCL_CUSTOMER_API |
| DEVK900112 | 002 | R3TR | FUGR | ZFG_CUSTOMER |
| DEVK900113 | 001 | R3TR | INTF | ZIF_CUSTOMER |

### `SEOMETAREL` (class/interface relationships)

`RELTYPE='1'` → INHERITS, `RELTYPE='2'` → IMPLEMENTS

| CLSNAME | REFCLSNAME | RELTYPE |
|---|---|---|
| ZCL_ORDER_API    | ZIF_ORDER     | 2 (IMPLEMENTS) |
| ZCL_ORDER_REPORT | ZCL_ORDER_API | 1 (INHERITS)   |
| ZCL_CUSTOMER_API | ZIF_CUSTOMER  | 2 (IMPLEMENTS) |

### `DD03L` (table fields → data element)

| TABNAME | FIELDNAME | ROLLNAME |
|---|---|---|
| ZTBL_ORDER_HEADER | ORDER_ID    | ZDE_ORDERID |
| ZTBL_ORDER_HEADER | CREATED_BY  | USERNAME (external — out of scope) |
| ZTBL_CUSTOMER     | CUST_ID     | **ZDE_CUSTID** |
| ZTBL_CUSTOMER     | NAME        | NAME1 (external — out of scope) |

### `DD04L` (data element → domain)

| ROLLNAME | DOMNAME | DATATYPE | LENG |
|---|---|---|---|
| ZDE_CUSTID  | ZDOM_CUSTID  | CHAR | 10 |
| ZDE_ORDERID | ZDOM_ORDERID | CHAR | 12 |

### `TFDIR` (function modules)

| FUNCNAME       | PNAME (function group) |
|---|---|
| Z_GET_CUSTOMER | SAPLZFG_CUSTOMER |
| Z_PUT_CUSTOMER | SAPLZFG_CUSTOMER |

---

## Scenario 1 — Same object locked in two tasks of the SAME TR (CRITICAL)

**Input the developer types in the Eclipse dialog:** `GMWK900800`

Pipeline expands the parent TR via `E070-STRKORR` to its three child tasks:

```
GMWK900801, GMWK900802, GMWK900803
```

### Edges produced by Stage 2 / 2b

| Kind | From task | From object | To task | To object |
|---|---|---|---|---|
| IMPLEMENTS | GMWK900802 | CLAS/ZCL_ORDER_API     | GMWK900801 | INTF/ZIF_ORDER     |
| INHERITS   | GMWK900803 | CLAS/ZCL_ORDER_REPORT  | GMWK900801 | CLAS/ZCL_ORDER_API |
| **CONFLICT** | GMWK900801 | ZCL_ORDER_API     | GMWK900802 | ZCL_ORDER_API |

The CONFLICT edge is the same-object detection (Stage 2b): `ZCL_ORDER_API`
appears in both `GMWK900801` and `GMWK900802`.

### Cluster (Stage 3) and pull-order (Stage 4)

```
[CRITICAL] GMWK900801 + GMWK900802 + GMWK900803

Step 1: COORDINATE -> GMWK900801, GMWK900802, GMWK900803
```

All three tasks collapse into one cluster because:
- 801 ↔ 802 are joined by CONFLICT (both touch `ZCL_ORDER_API`).
- 803 → 801 is joined by INHERITS (`ZCL_ORDER_REPORT` extends `ZCL_ORDER_API`).
- Carol's task is therefore transitively pulled into the same release set.

### Recommendation

> **Step 1 [CRITICAL]:** tasks `['GMWK900801', 'GMWK900802', 'GMWK900803']`
> touch the SAME object. Owners **I_ALICE, I_BOB, I_CAROL** MUST coordinate
> before either is released. Releasing one alone overwrites the other's
> changes in QA.

### ICF wire JSON

```json
{
  "version": "1.0",
  "input": ["GMWK900800"],
  "tasks": ["GMWK900801", "GMWK900802", "GMWK900803"],
  "edges": [
    {"source_task":"GMWK900802","source_object":"CLAS/ZCL_ORDER_API",
     "target_task":"GMWK900801","target_object":"INTF/ZIF_ORDER",
     "kind":"IMPLEMENTS","detail":"ZCL_ORDER_API -> ZIF_ORDER"},
    {"source_task":"GMWK900803","source_object":"CLAS/ZCL_ORDER_REPORT",
     "target_task":"GMWK900801","target_object":"CLAS/ZCL_ORDER_API",
     "kind":"INHERITS","detail":"ZCL_ORDER_REPORT -> ZCL_ORDER_API"},
    {"source_task":"GMWK900801","source_object":"ZCL_ORDER_API",
     "target_task":"GMWK900802","target_object":"ZCL_ORDER_API",
     "kind":"CONFLICT","detail":"ZCL_ORDER_API owned by both GMWK900801 and GMWK900802"}
  ],
  "clusters": [
    {"tasks":["GMWK900801","GMWK900802","GMWK900803"],"risk":"CRITICAL"}
  ],
  "pull_order": [
    {"step":1,"action":"COORDINATE",
     "tasks":["GMWK900801","GMWK900802","GMWK900803"]}
  ]
}
```

✅ **PASS** — top action `COORDINATE` matches the expected outcome.

---

## Scenario 2 — Multiple TRs with dependent objects locked across TR boundaries

**Input the developer types in the dialog:**
`DEVK900100, DEVK900101, DEVK900102, DEVK900103`

Each id is a parent TR. Pipeline expands each via `E070-STRKORR` to its
single child task:

```
DEVK900110, DEVK900111, DEVK900112, DEVK900113
```

### Edges produced by Stage 2

| Kind | From task | From object | To task | To object |
|---|---|---|---|---|
| TYPE_REF   | DEVK900110 | TABL/ZTBL_CUSTOMER    | DEVK900111 | DTEL/ZDE_CUSTID    |
| IMPLEMENTS | DEVK900112 | CLAS/ZCL_CUSTOMER_API | DEVK900113 | INTF/ZIF_CUSTOMER  |

(The data element → domain link `ZDE_CUSTID → ZDOM_CUSTID` would have been an
edge too, but the domain is not in any of the four input TRs, so it falls
outside scope and is correctly skipped — this is the "external dependency"
behaviour.)

### Clusters and pull-order

```
[HIGH    ] DEVK900112 + DEVK900113
[MEDIUM  ] DEVK900110 + DEVK900111

Step 1: TOGETHER             -> DEVK900112, DEVK900113
Step 2: TOGETHER_RECOMMENDED -> DEVK900110, DEVK900111
```

The HIGH cluster comes first because `IMPLEMENTS` is an *activation*
dependency: if `DEVK900102` (the TR holding `ZCL_CUSTOMER_API`) is imported
to QA before `DEVK900103` (the TR holding `ZIF_CUSTOMER`), activation will
fail with "interface ZIF_CUSTOMER does not exist". The MEDIUM cluster is the
table → data element relationship — a structural dependency that is also
strict (table activation fails without the data element) but the analyser
classifies it MEDIUM by convention because it is a pure type reference.

### Recommendations

> **Step 1 [HIGH]:** tasks `['DEVK900112', 'DEVK900113']` share an activation
> dependency (IMPLEMENTS / INHERITS / CALLS). Owners **I_FRANK, I_GINA** must
> release them in the SAME import buffer or activation will fail in QA.

> **Step 2 [MEDIUM]:** tasks `['DEVK900110', 'DEVK900111']` share a TYPE_REF
> (table column type or data element domain). Owners **I_DAN, I_EVE** should
> release them together; if not, release the referenced object first.

### ICF wire JSON

```json
{
  "version": "1.0",
  "input": ["DEVK900100","DEVK900101","DEVK900102","DEVK900103"],
  "tasks": ["DEVK900110","DEVK900111","DEVK900112","DEVK900113"],
  "edges": [
    {"source_task":"DEVK900110","source_object":"TABL/ZTBL_CUSTOMER",
     "target_task":"DEVK900111","target_object":"DTEL/ZDE_CUSTID",
     "kind":"TYPE_REF","detail":"ZTBL_CUSTOMER -> ZDE_CUSTID"},
    {"source_task":"DEVK900112","source_object":"CLAS/ZCL_CUSTOMER_API",
     "target_task":"DEVK900113","target_object":"INTF/ZIF_CUSTOMER",
     "kind":"IMPLEMENTS","detail":"ZCL_CUSTOMER_API -> ZIF_CUSTOMER"}
  ],
  "clusters": [
    {"tasks":["DEVK900110","DEVK900111"],"risk":"MEDIUM"},
    {"tasks":["DEVK900112","DEVK900113"],"risk":"HIGH"}
  ],
  "pull_order": [
    {"step":1,"action":"TOGETHER",
     "tasks":["DEVK900112","DEVK900113"]},
    {"step":2,"action":"TOGETHER_RECOMMENDED",
     "tasks":["DEVK900110","DEVK900111"]}
  ]
}
```

✅ **PASS** — top action `TOGETHER` matches the expected outcome.

---

## What this proves and what it does NOT prove

| Claim | Status |
|---|---|
| The 4-stage algorithm correctly classifies the intra-TR same-object conflict | ✅ Proven on this fixture |
| The 4-stage algorithm correctly classifies cross-TR activation + type dependencies | ✅ Proven on this fixture |
| `E070-STRKORR` based parent → child task expansion produces the right task set | ✅ Proven on this fixture |
| Risk priority `CRITICAL > HIGH > MEDIUM > NONE` is applied to step ordering | ✅ Proven |
| The JSON wire contract round-trips through an equivalent of the ABAP `to_json` | ✅ Proven (see also `verify_json_contract.py`) |
| The ABAP `SELECT` statements actually return these row shapes on a real SAP system | ❌ NOT proven — requires live SAP system |
| `xco_cp_oo` / `xco_cp_abap_dictionary` produce equivalent metadata on Public Cloud | ❌ NOT proven — requires live BTP tenant |
| `AUTHORITY-CHECK OBJECT 'S_TRANSPRT'` blocks unauthorised callers | ❌ NOT proven — requires live system |
| The Eclipse plugin renders this JSON correctly in `DependencyResultView` | ⚠ Plugin compiles & packages, but UI click-through has not been executed in ADT |

This is the same honesty boundary as `VERIFICATION_REPORT.md`: the algorithm,
the data shape, and the wire protocol are verified on the workstation; the
SQL execution, the ABAP runtime, and the in-Eclipse UI behaviour still need a
live system to be 100 % proven.

---

## TR-level release sequence (DEV → QA → PROD)

The original pipeline returned a *task-level* "pull order" optimised for
gCTS one-task-at-a-time pulls. For classic CTS (the on-prem case) the unit
that actually moves between systems is the **parent TR**. The simulator
now answers the user's follow-up question:

> *"If object dependency exists across multiple TRs, can we get the sequence
> in which the TRs should be moved to QA and then PROD?"*

### Algorithm

1. Aggregate every task-level dependency edge `s_task → t_task` into a
   TR-level edge `target_TR → source_TR`. Direction is "target must reach
   QA before source" because the source's object cannot activate until the
   target's object exists.
2. Detect strongly-connected components (Tarjan) — TRs that are mutually
   dependent. Merge them into one **release group** that must move in the
   same import buffer.
3. Merge **CONFLICT** pairs (same object in two TRs) into release groups
   too — these need human coordination, not ordering.
4. Topologically sort the resulting condensed DAG (Kahn's algorithm).
5. Number the steps 1…N. Each step is one release group; groups in the same
   step have no dependency on each other (they may move in any order or in
   parallel).

The sequence is emitted as part of the ICF JSON under
`tr_release_sequence[]` and the human report under
"Recommended DEV → QA → PROD release sequence".

### Scenario 1 — one TR, one release step (no cross-TR ordering needed)

```
Step 1: G1 -> GMWK900800  [RELEASE_ALONE]
```

Single parent TR; coordination among its three tasks is task-level, not
TR-level.

### Scenario 2 — four TRs, two ordered chains

```
Recommended DEV -> QA -> PROD release sequence:
  Step 1: G2 -> DEVK900101  [RELEASE_ALONE]
  Step 2: G1 -> DEVK900100  [RELEASE_ALONE]  (waits on: G2)
  Step 3: G4 -> DEVK900103  [RELEASE_ALONE]
  Step 4: G3 -> DEVK900102  [RELEASE_ALONE]  (waits on: G4)
```

Reading: I_EVE's data-element TR (`DEVK900101`) must reach QA before
I_DAN's table TR (`DEVK900100`) — otherwise table activation fails.
Independently, I_GINA's interface TR (`DEVK900103`) must reach QA before
I_FRANK's class TR (`DEVK900102`) — otherwise class activation fails.

The two chains are independent of each other, so Steps 1+3 can be released
in parallel and Steps 2+4 can be released in parallel after their
respective predecessors.

### Scenario 3 — five TRs forming two clearly ordered chains

The new fixture demonstrates the topo-sort end-to-end:

| TR | Owner | Object | Depends on |
|---|---|---|---|
| `DEVK900200` | I_HEIDI | DOMA `ZDOM_ARTID`     | (none)            |
| `DEVK900201` | I_IVAN  | DTEL `ZDE_ARTID`      | `DEVK900200`      |
| `DEVK900202` | I_JUDY  | TABL `ZTBL_ARTICLE`   | `DEVK900201`      |
| `DEVK900203` | I_KAREN | INTF `ZIF_ARTICLE`    | (none)            |
| `DEVK900204` | I_LEO   | CLAS `ZCL_ARTICLE_API`| `DEVK900203`      |

Output:

```
Recommended DEV -> QA -> PROD release sequence:
  Step 1: G1 -> DEVK900200  [RELEASE_ALONE]
  Step 2: G2 -> DEVK900201  [RELEASE_ALONE]  (waits on: G1)
  Step 3: G3 -> DEVK900202  [RELEASE_ALONE]  (waits on: G2)
  Step 4: G4 -> DEVK900203  [RELEASE_ALONE]
  Step 5: G5 -> DEVK900204  [RELEASE_ALONE]  (waits on: G4)
```

The Basis team reads this top-to-bottom:

1. Release **`DEVK900200`** to QA (and later PROD) **first** — domain has
   no upstream deps. (Step 4 `DEVK900203` may be released at the same time;
   the two chains are independent.)
2. Release **`DEVK900201`** **after** `DEVK900200` is in QA — data element
   needs the domain.
3. Release **`DEVK900202`** **after** `DEVK900201` is in QA — table needs
   the data element.
4. Release **`DEVK900203`** independently — interface has no upstream deps.
5. Release **`DEVK900204`** **after** `DEVK900203` is in QA — class needs
   the interface to compile.

### Cycles and CONFLICTs collapse into "must-move-together" groups

If two TRs were to mutually depend on each other (say `TR_A` holds class X
that calls function in `TR_B`'s function group, and `TR_B` holds a type
that comes from `TR_A`'s table), Tarjan's SCC step would merge them into
one group whose `must_move_together` flag is `true`. The action becomes
`RELEASE_TOGETHER (same import buffer)` and the Basis team is told to put
both TRs into the same STMS import.

The same merging happens for CONFLICT pairs — when the same object exists
in two TRs (Scenario 1's pattern but at TR level), neither TR can be
released in isolation, so they are merged into one group with the
"coordinate, then release together" message.

### JSON schema additions (version `1.1`)

The wire payload now carries two extra arrays:

```json
{
  "version": "1.1",
  ...
  "tr_release_groups": [
    {"group_id":"G1","trs":["DEVK900200"],
     "must_move_together":false,"reason":["independent"]},
    ...
  ],
  "tr_release_sequence": [
    {"step":1,"group_id":"G1","trs":["DEVK900200"],
     "action":"RELEASE_ALONE","blocks_on":[],"reason":["independent"]},
    {"step":2,"group_id":"G2","trs":["DEVK900201"],
     "action":"RELEASE_ALONE","blocks_on":["G1"],"reason":["independent"]},
    ...
  ]
}
```

`blocks_on[]` carries the predecessor group IDs, so Eclipse can render the
sequence as a numbered list with arrows back to the gating TR ("waits on
G1") without re-deriving the DAG client-side.
