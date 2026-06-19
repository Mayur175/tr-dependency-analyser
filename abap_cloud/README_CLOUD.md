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

| Classic operation | Cloud replacement | Loss of fidelity? |
|---|---|---|
| `SELECT trkorr FROM e070 WHERE strkorr = @tr` | gCTS REST `GET /sap/bc/cts_abapvcs/repository/.../commits/...` via `cl_http_destination_provider` | **Yes** — gCTS exposes commit-level info, not classic Workbench TR / task hierarchy. Some TRs in cloud are software-component releases, no child tasks. |
| `SELECT … FROM e071 WHERE trkorr = @task` | gCTS REST `GET /repository/.../objects` per commit | Partial — pgmid/object/obj_name maps; ranges (`R3TR/LIMU`) don't. |
| `SELECT … FROM dd03l WHERE tabname = @tab` | `xco_cp_abap_dictionary=>database_table( tab )->fields->all->get( )` | None — XCO is the official replacement. |
| `SELECT domname FROM dd04l WHERE rollname = @ro` | `xco_cp_abap_dictionary=>data_element( ro )->content( )->get_domain( )` | None. |
| `SELECT refclsname FROM seometarel WHERE clsname = @cl AND reltype = '0'` (superclass) | `xco_cp_abap_repository=>object->oo_class->for( cl )->content( )->get_super_class( )` | None. |
| `SELECT refclsname FROM seometarel WHERE clsname = @cl AND reltype IN ('1','2')` (interfaces) | `xco_cp_abap_repository=>object->oo_class->for( cl )->content( )->get_interfaces( )` | None. |
| `SELECT funcname FROM tfdir WHERE pname = @group` | `xco_cp_abap_repository=>object->function_group->for( gp )->function_modules->all->get( )` | None. |
| `cl_demo_output=>write_text( … )` | Internal `out( )` method that just appends to a string buffer; HTTP response carries it back | None for HTTP callers. CLI/F9 demo output is not available in cloud (by design — there is no SAP GUI). |
| `IF_HTTP_EXTENSION` + SICF node | `IF_HTTP_SERVICE_EXTENSION` + HTTP Service object (cloud-released) | None functionally; URL still `/sap/bc/zgcts/analyze` if you keep the path mapping. |
| `CL_CI_TEST_OBJECT` ATC check | `CL_CI_TEST_ROOT` released cloud variant + ATC Custom Check Provider | Identical findings, different base class. |

---

## Feature parity matrix

| Capability | Classic (`abap/`) | Cloud (`abap_cloud/`) |
|---|---|---|
| Resolve TR → child tasks | ✅ via E070-STRKORR | ⚠️ Limited — gCTS commits don't always have a 1:1 task model |
| Inventory objects in a TR/task | ✅ E071 read | ⚠️ Via gCTS REST per commit; coverage depends on what gCTS recorded |
| Where-used: data element → tables | ✅ DD03L | ✅ XCO_CP_ABAP_DICTIONARY |
| Where-used: domain → data elements | ✅ DD04L | ✅ XCO_CP_ABAP_DICTIONARY |
| Class hierarchy (super + interfaces) | ✅ SEOMETAREL | ✅ XCO_CP_ABAP_REPOSITORY |
| Function module → group → siblings | ✅ TFDIR | ✅ XCO_CP_ABAP_REPOSITORY |
| Risk scoring | ✅ in-class logic | ✅ identical (no DB) |
| History persistence | ✅ ZGCTS_HIST custom table | ✅ same custom table (DDIC tables ARE allowed if you create them yourself) |
| HTTP endpoint | ✅ `IF_HTTP_EXTENSION` | ✅ `IF_HTTP_SERVICE_EXTENSION` |
| ATC integration | ✅ `CL_CI_TEST_OBJECT` | ✅ `CL_CI_TEST_ROOT` (cloud) |
| Console (`cl_demo_output`) | ✅ | ❌ Not available in cloud (replaced by HTTP response body) |

---

## Object map (what to create in SAP)

| # | Object | Type | Source file | Purpose |
|---|---|---|---|---|
| 1 | `ZGCTS_HIST` | DDIC table | `../abap/src/zgcts_hist.tabl.xml` (reuse — same table works in cloud) | Audit log of every analysis run |
| 2 | `ZCL_GCTS_TR_ANALYZER_CLOUD` | Global class | `src/zcl_gcts_tr_analyzer_cloud.clas.abap` | The cloud-clean analyser (XCO + gCTS) |
| 3 | `ZCL_GCTS_HTTP_HANDLER_CLOUD` | Global class | `src/zcl_gcts_http_handler_cloud.clas.abap` | Cloud HTTP service handler (`IF_HTTP_SERVICE_EXTENSION`) |
| 4 | `ZGCTS_HTTP_SERVICE_CLOUD` | HTTP Service | `src/zgcts_http_service_cloud.srvb.xml` | Cloud HTTP Service binding (`/sap/bc/zgcts/analyze`) |
| 5 | `ZCL_GCTS_DEP_ATC_CHK_CLOUD` | Global class | `src/zcl_gcts_dep_atc_chk_cloud.clas.abap` | ATC custom check, cloud-released base class |

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

1. `01_ZGCTS_HIST.tabl.txt` (same as classic — DDIC table)
2. `02_ZCL_GCTS_TR_ANALYZER_CLOUD.clas.txt`
3. `03_ZCL_GCTS_HTTP_HANDLER_CLOUD.clas.txt`
4. `04_ZGCTS_HTTP_SERVICE_CLOUD.srvb.txt` (HTTP service binding metadata)
5. `05_ZCL_GCTS_DEP_ATC_CHK_CLOUD.clas.txt`

---

## Test the cloud HTTP endpoint

```bash
curl -u <user>:<token> -X POST \
  -H 'Content-Type: application/json' \
  --data '{"input":[{"id":"GMWK900691"}]}' \
  https://<your-tenant>.abap.<region>.hana.ondemand.com/sap/bc/zgcts/analyze
```

Expected: HTTP 200 with the same JSON shape as the classic analyser produces. If the gCTS read returns empty (no objects in the commit), the response will be `{"objects":[],"deps":[],"clusters":[]}` — that's not an error, just an empty TR.

---

## Known limits in Public Cloud

1. **No SAP GUI / no SE38 / no F9 demo run.** The `cl_demo_output` path simply doesn't exist. All output goes through the HTTP response or the ATC result list.
2. **gCTS coverage gaps.** Customer-developed objects in software components are visible; SAP-shipped objects are not (allow-list). The "external dependency" detection is therefore narrower than on-prem.
3. **No `STRKORR` hierarchy.** gCTS uses commit hashes / tags, not parent-child TR structure. The `resolve_input` step in cloud treats every input as a leaf commit; "TR with child tasks" is a classic-only concept.
4. **Authority checks.** Public Cloud uses business roles (IAM Apps / restrictions). The `c_enforce_auth` flag from classic is replaced by a cloud-released authorization concept — see `zcl_gcts_http_handler_cloud` for the pattern.

---

## Roadmap for the cloud variant

| Stage | Status | What it delivers |
|---|---|---|
| 1. Skeleton + XCO DDIC reads | ✅ shipped | Data-element / domain / class / FM dependency detection works |
| 2. gCTS REST integration | 🟡 stub with clear extension point | Reads commit objects via `cl_http_destination_provider` |
| 3. HTTP service binding | ✅ shipped | `/sap/bc/zgcts/analyze` reachable from cloud |
| 4. ATC check | ✅ shipped | Findings appear in standard ATC result list |
| 5. Eclipse plugin auto-detect | ⏳ planned | Plugin probes both endpoints and uses whichever responds |

---

## Maintenance note

When you fix a bug in the **classic** analyser (`../abap/`), check whether the same bug exists in the cloud variant — many helpers (risk scoring, JSON serialisation, history persistence) are duplicated. The `CODE_REVIEW.md` checklist in `../manual_install/` applies to both.