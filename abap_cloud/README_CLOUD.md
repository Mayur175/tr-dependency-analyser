# `abap_cloud/` — Public-Cloud variant of the TR Analyser

This folder contains the **SAP Public Cloud / BTP ABAP Environment / S/4HANA Cloud Public** variant of the analyser.

| Variant | Folder | When to install | Allow-list |
|---|---|---|---|
| Classic (full features) | `../abap/` | Private Cloud (S/4HANA Cloud Private), on-prem NW 7.5x / S/4HANA on-prem | None — direct E070/E071/DD03L/DDIC table reads |
| **Cloud-clean (this folder)** | `abap_cloud/` | **Public Cloud** (Steampunk, S/4HANA Cloud Public, BTP ABAP Environment) | Strict ABAP Cloud — only **C1-released** APIs |

You install **one or the other**, not both. Pick by landscape, not by preference.

---

## Why a separate codebase?

The ABAP Cloud development model rejects, at compile time, every direct read of:

- `E070`, `E071` (transport headers / objects)
- `DD03L`, `DD04L`, `DD02L`, `TADIR` (DDIC tables)
- `SEOMETAREL` (class-relations)
- `TFDIR` (function-module directory)
- `CL_DEMO_OUTPUT` and other internal-only classes
- Any unescaped host variable inside OpenSQL (`WHERE x = lv_y` → must be `WHERE x = @lv_y`)

The classic analyser uses every one of these. There is no flag, no pragma, no `#EC NEEDED` that bypasses the cloud allow-list — these are blocked by the syntax checker before activation.

---

## What changes from classic to cloud

| Classic operation | Cloud variant behaviour | Status |
|---|---|---|
| `SELECT trkorr FROM e070 / e071 WHERE …` | gCTS REST `GET /sap/bc/cts_abapvcs/repository/<repo>/commits/<id>/objects` via `cl_http_destination_provider` + `cl_web_http_client_manager` | ✅ Shipped (`read_gcts_commit_objects( )`) |
| `SELECT * FROM dd03l / dd04l / seometarel / tfdir` | Per-tenant XCO extension (see Roadmap) | 🟡 Not in MVP — XCO content struct shapes differ between BTP ABAP Environment SP levels |
| `cl_demo_output=>write_text( … )` | Internal `out( )` buffer exposed via `get_log( )`; HTTP response carries log | ✅ Shipped |
| `IF_HTTP_EXTENSION` + SICF node | `IF_HTTP_SERVICE_EXTENSION` + HTTP Service binding wizard | ✅ Shipped |
| `CL_CI_TEST_OBJECT` ATC check | Per-tenant wrapper around `lo_an->get_deps( )` | 🟡 Not in MVP — the cloud-released ATC base class varies by tenant |
| JSON serialise / parse | Hand-rolled string concatenation + `FIND REGEX SUBMATCHES` | ✅ Shipped — no library dependency |
| Custom Z-table persistence | `INSERT zgcts_hist FROM TABLE @lt_rows` (escaped host vars) | ✅ Shipped |

---

## Feature parity matrix

| Capability | Classic (`abap/`) | Cloud MVP (`abap_cloud/`) |
|---|---|---|
| Inventory objects in a TR/commit | ✅ via E071 SELECT | ✅ via gCTS REST (object list per commit) |
| Class superclass + interfaces | ✅ SEOMETAREL | 🟡 Per-tenant extension (XCO patterns documented) |
| Function module → group → siblings | ✅ TFDIR | 🟡 Per-tenant extension (XCO patterns documented) |
| DDIC where-used (DTEL→DOMA, TABL→DTEL) | ✅ DD03L / DD04L | 🟡 Per-tenant extension (XCO patterns documented) |
| Risk scoring | ✅ in-class logic | ✅ same logic; rows currently classified as `INVENTORIED` until deeper walks added |
| History persistence | ✅ `ZGCTS_HIST` | ✅ same table, identical field mapping (`tr_id`, `src_task`, `src_obj`, `tgt_task`, `tgt_obj`, `kind`, `risk`, `detail`, `pull_step`, `pull_action`) |
| HTTP endpoint | ✅ `IF_HTTP_EXTENSION` + SICF | ✅ `IF_HTTP_SERVICE_EXTENSION` + HTTP Service binding |
| ATC integration | ✅ `CL_CI_TEST_OBJECT` | 🟡 Per-tenant wrapper — analyzer exposes `get_deps( )` as the integration point |
| Console (`cl_demo_output`) | ✅ | ❌ Not available in cloud (replaced by `get_log( )` + HTTP response) |

---

## Object map (what to create in SAP)

| # | Object | Type | Source file | Purpose |
|---|---|---|---|---|
| 1 | `ZGCTS_HIST` | DDIC table | `../abap/src/zgcts_hist.tabl.xml` (reuse — same table works in cloud) | Audit log of every analysis run |
| 2 | `ZCL_GCTS_TR_ANALYZER_CLOUD` | Global class | `src/zcl_gcts_tr_analyzer_cloud.clas.abap` | Cloud-clean analyser (gCTS REST + hand-rolled JSON, no XCO) |
| 3 | `ZCL_GCTS_HTTP_HANDLER_CLOUD` | Global class | `src/zcl_gcts_http_handler_cloud.clas.abap` | Cloud HTTP service handler (`IF_HTTP_SERVICE_EXTENSION`) |
| 4 | `ZGCTS_HTTP_SERVICE_CLOUD` | HTTP Service | (wizard-driven, no source file) | Cloud HTTP Service binding |

> **No ATC check class shipped.** The cloud-released ATC base class varies by BTP ABAP Environment SP level (`CL_CI_TEST_ROOT` vs `CL_CI_TEST_ABAP` vs tenant-specific). Rather than ship speculation, the cloud variant exposes results via the HTTP endpoint only. To wire findings into ATC, write a tenant-specific wrapper that inherits from your tenant's released base class and iterates `lo_an->get_deps( )`. See the install README for the pattern.

---

## Install

### Option 1 — abapGit Online (recommended)

1. In your Public Cloud / BTP ABAP system, open **abapGit** (`Z_ABAPGIT_STANDALONE` if installed, or the dev tool).
2. **New Online Repository** → URL: `https://github.com/Mayur175/tr-dependency-analyser.git`
3. Set **Folder logic**: `FULL`, **Starting folder**: `/abap_cloud/src/`
4. Pull → activate.

> abapGit's "Cloud" edition runs in BTP ABAP Environment. If your tenant doesn't have it, fall back to Option 2.

### Option 2 — Manual install (copy-paste)

See `../manual_install_cloud/` for paste-ready text files in the right order:

1. `01_ZGCTS_HIST.tabl.txt` (shared with classic — DDIC table)
2. `02_ZCL_GCTS_TR_ANALYZER_CLOUD.clas.txt`
3. `03_ZCL_GCTS_HTTP_HANDLER_CLOUD.clas.txt`
4. `ZGCTS_HTTP_SERVICE_CLOUD` HTTP Service — wizard-driven, no source file (see install README)

---

## Test the cloud HTTP endpoint

```bash
curl -u <user>:<token> -X POST \
  -H 'Content-Type: application/json' \
  --data '{"input":[{"id":"GMWK900691"}]}' \
  https://<your-tenant>.abap.<region>.hana.ondemand.com/sap/bc/http/sap/zgcts_http_service_cloud
```

Expected: HTTP 200 with body shape:

```json
{
  "label": "GMWK900691",
  "objectCount": 12,
  "depCount": 12,
  "deps": [
    {
      "sourceTask": "GMWK900691",
      "sourceObject": "CLAS/ZCL_X",
      "targetTask": "",
      "targetObject": "",
      "kind": "INVENTORIED",
      "detail": "pgmid=R3TR",
      "risk": "NONE"
    }
  ]
}
```

If the gCTS read returns empty (no objects, or destination not configured), the response is `objectCount:0, depCount:0, deps:[]` — that is not an error, just an empty inventory.

---

## Known limits in Public Cloud

1. **No SAP GUI / no SE38 / no F9 demo run.** The `cl_demo_output` path simply doesn't exist. All output goes through the HTTP response or the ATC result list.
2. **gCTS coverage gaps.** Customer-developed objects in software components are visible; SAP-shipped objects are not (allow-list). The "external dependency" detection is therefore narrower than on-prem.
3. **No `STRKORR` hierarchy.** gCTS uses commit hashes / tags, not parent-child TR structure. The `resolve_input` step in cloud treats every input as a leaf commit; "TR with child tasks" is a classic-only concept.
4. **Authority checks.** Public Cloud uses business roles (IAM Apps / restrictions). The `c_enforce_auth` flag from classic is replaced by a cloud-released authorization concept — see `zcl_gcts_http_handler_cloud` for the pattern.

---

## Confirmed-released APIs the cloud variant uses

Every method call in `abap_cloud/src/` is on this list:

| API | Purpose |
|---|---|
| `cl_abap_context_info=>get_system_date` | Replace classic `sy-datum` |
| `cl_abap_context_info=>get_system_time` | Replace classic `sy-uzeit` |
| `cl_http_destination_provider=>create_by_destination` | Outbound HTTP destination |
| `cl_web_http_client_manager=>create_by_http_destination` | Cloud HTTP client factory |
| `if_web_http_client=>get` (constant) | HTTP method enum |
| `if_web_http_request->set_uri_path / set_header_field / get_text / get_method` | HTTP request manipulation |
| `if_web_http_response->set_status / set_header_field / set_text / get_status / get_text` | HTTP response manipulation |
| `if_http_service_extension~handle_request` | Cloud HTTP service entry point |
| `cl_abap_char_utilities=>newline / cr_lf` | Line endings |
| `INSERT zgcts_hist FROM TABLE @lt_rows` | Standard cloud OpenSQL |
| `FIND FIRST OCCURRENCE OF REGEX ... SUBMATCHES` | Standard ABAP, no library needed |
| `REPLACE ALL OCCURRENCES OF` | Standard ABAP |
| Standard control flow (`LOOP`, `IF`, `TRY/CATCH cx_root`, `READ TABLE`) | Always allowed |

Deliberately **NOT** used (because their cloud-release status varies by tenant):

| Avoided API | Why |
|---|---|
| `xco_cp_abap_repository=>...` | Content struct shapes differ between SP levels |
| `xco_cp_abap_dictionary=>...` | Same |
| `xco_cp_json` | The transformation API has changed across releases; we hand-roll JSON instead |
| `cl_demo_output` | Not on the cloud allow-list |
| `/ui2/cl_json` | Not a released cloud API |
| `cl_abap_format=>e_json_string` | Format constants vary; `json_escape( )` helper used instead |
| `cl_ci_test_*` ATC base classes | Cloud-release status varies — ATC integration is per-tenant |

## Roadmap for the cloud variant

| Stage | Status | What it delivers |
|---|---|---|
| 1. Object inventory via gCTS REST | ✅ shipped | Pulls the object list of any commit/TR id passed in |
| 2. Hand-rolled JSON serialise/parse | ✅ shipped | No library dependency, predictable format |
| 3. ZGCTS_HIST persistence | ✅ shipped | Field-name-correct, `@`-escaped INSERT |
| 4. HTTP service binding | ✅ shipped | `IF_HTTP_SERVICE_EXTENSION` handler |
| 5. Deep dependency walking (XCO) | 🟡 per-tenant extension | Documented patterns in this README; customer adds against their tenant's XCO version |
| 6. ATC custom check | 🟡 per-tenant extension | Customer-written wrapper inheriting their tenant's released ATC base class |
| 7. Eclipse plugin auto-detect | ⏳ planned | Plugin probes both endpoints and uses whichever responds |

---

## Maintenance note

When you fix a bug in the **classic** analyser (`../abap/`), check whether the same bug exists in the cloud variant — many helpers (risk scoring, JSON serialisation, history persistence) are duplicated. The `CODE_REVIEW.md` checklist in `../manual_install/` applies to both.