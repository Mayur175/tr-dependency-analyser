# Manual install — **Public Cloud** variant

Use this folder if you are installing on **SAP BTP ABAP Environment**, **S/4HANA Cloud Public**, or **Steampunk** (any ABAP Cloud / strict-allow-list landscape).

If you are on Private Cloud or on-prem, use [`../manual_install/`](../manual_install/) instead.

The same repo holds both variants; you install **one or the other**, never both.

> ## ⚠️ Do NOT install classic objects on a Public Cloud tenant
>
> The classic `manual_install/` folder contains classes that **will not activate** on Public Cloud:
>
> - `ZCL_GCTS_TR_ANALYZER`         (uses E070/E071/DD03L/SEOMETAREL — all blocked)
> - `ZGCTS_ANALYZE_HANDLER`        (uses `IF_HTTP_EXTENSION` + SICF — neither exists in cloud)
> - `ZCL_GCTS_DEP_ATC_CHECK`       (inherits `CL_CI_TEST_OBJECT` — blocked)
> - their `*.locals` includes
>
> If you see `Type "ZCL_GCTS_TR_ANALYZER" is unknown` on the cloud tenant, that means a classic class is being referenced. **Delete the classic objects** (any class name without the `_CLOUD` suffix) and install only the objects listed in the table below. The cloud variant uses suffixed names (`_CLOUD`) precisely so the two cannot collide if a system briefly has both.

---

## What you install on Public Cloud

The cloud variant is intentionally minimal — **3 objects total**, every line of code uses only confirmed-released APIs:

| # | File | Object name | Type |
|---|---|---|---|
| 1 | [`01_ZGCTS_HIST.tabl.txt`](../manual_install/01_ZGCTS_HIST.tabl.txt) (shared with classic) | `ZGCTS_HIST` | DDIC table |
| 2 | [`02_ZCL_GCTS_TR_ANALYZER_CLOUD.clas.txt`](02_ZCL_GCTS_TR_ANALYZER_CLOUD.clas.txt) | `ZCL_GCTS_TR_ANALYZER_CLOUD` | Global class |
| 3 | [`03_ZCL_GCTS_HTTP_HANDLER_CLOUD.clas.txt`](03_ZCL_GCTS_HTTP_HANDLER_CLOUD.clas.txt) | `ZCL_GCTS_HTTP_HANDLER_CLOUD` | Global class |
| 4 | (no source file) | `ZGCTS_HTTP_SERVICE_CLOUD` | HTTP Service binding (wizard-driven) |

**No ATC check class is shipped for cloud.** ATC integration on cloud requires extending a tenant-specific cloud-released base class whose name varies across SAP BTP ABAP Environment release levels. Rather than ship a class that may not compile on your tenant, the cloud variant exposes results via the HTTP endpoint only. If you need ATC integration, see "ATC integration on cloud" at the bottom of this README.

---

## Install order

### 1. DDIC table `ZGCTS_HIST`

The cloud variant **reuses** the same custom table from the classic install. Custom Z-tables are always allowed in cloud — only SAP-internal tables like `E070` are blocked.

1. Open <https://raw.githubusercontent.com/Mayur175/tr-dependency-analyser/main/manual_install/01_ZGCTS_HIST.tabl.txt> in your browser, copy contents.
2. ADT → File → New → Other ABAP Repository Object → "Database Table".
3. Name `ZGCTS_HIST`, package `ZGCTS_CLOUD` (or your customer package), description `TR Analyser - Dependency Analysis History`.
4. Paste the field list, save, activate (Ctrl+F3).

The activated table will have these columns:

| Field | Type | Key |
|---|---|---|
| `client` | CLNT(3) | ✓ |
| `tr_id` | CHAR(20) | ✓ |
| `run_ts` | DEC(15,0) | ✓ |
| `src_task` | CHAR(20) | ✓ |
| `src_obj` | CHAR(60) | ✓ |
| `tgt_task` | CHAR(20) | ✓ |
| `tgt_obj` | CHAR(60) | ✓ |
| `kind` | CHAR(20) | |
| `risk` | CHAR(10) | |
| `detail` | CHAR(200) | |
| `pull_step` | INT4 | |
| `pull_action` | CHAR(30) | |

The cloud analyzer's `persist_result( )` writes exactly these field names — no mapping layer needed.

### 2. Class `ZCL_GCTS_TR_ANALYZER_CLOUD`

1. ADT → File → New → ABAP Class.
2. Name `ZCL_GCTS_TR_ANALYZER_CLOUD`, package `ZGCTS_CLOUD`.
3. **Critical:** in the wizard, set **ABAP Language Version** = `ABAP for Cloud Development`. If you leave it at the default the class will reject `if_web_http_client` etc. as non-released.
4. Paste content from [`02_ZCL_GCTS_TR_ANALYZER_CLOUD.clas.txt`](02_ZCL_GCTS_TR_ANALYZER_CLOUD.clas.txt) into the "Global Class" tab.
5. Save and activate.

The class only references these cloud-released APIs:

| API used | Why it is safe in cloud |
|---|---|
| `cl_abap_context_info=>get_system_date` / `get_system_time` | Released — replaces the on-prem `sy-datum` / `sy-uzeit` reads |
| `cl_http_destination_provider=>create_by_destination` | Released — only way to reach an outbound URL in cloud |
| `cl_web_http_client_manager=>create_by_http_destination` | Released — paired with the destination above |
| `if_web_http_client=>get` (constant) | Released |
| `cl_abap_char_utilities=>newline` | Classic ABAP, available everywhere |
| `INSERT zgcts_hist FROM TABLE @lt_rows` | Standard OpenSQL with `@`-escaped host vars (cloud-mandatory) |
| Standard ABAP statements (LOOP, READ TABLE, REPLACE, FIND REGEX, …) | Always allowed |

There are no XCO calls, no `cl_demo_output`, no `xco_cp_json`. Every method call is a confirmed cloud API.

### 3. Class `ZCL_GCTS_HTTP_HANDLER_CLOUD`

Same wizard pattern as step 2:

1. New ABAP Class, language version `ABAP for Cloud Development`.
2. Paste from [`03_ZCL_GCTS_HTTP_HANDLER_CLOUD.clas.txt`](03_ZCL_GCTS_HTTP_HANDLER_CLOUD.clas.txt).
3. The class implements `IF_HTTP_SERVICE_EXTENSION` (the cloud-released HTTP entry interface).
4. Activate.

### 4. HTTP Service binding `ZGCTS_HTTP_SERVICE_CLOUD`

This object is wizard-driven (no source file). In ADT:

1. File → New → Other ABAP Repository Object → "HTTP Service".
2. Name `ZGCTS_HTTP_SERVICE_CLOUD`, description `TR Analyser cloud HTTP entry`, package `ZGCTS_CLOUD`.
3. **Handler class**: `ZCL_GCTS_HTTP_HANDLER_CLOUD`.
4. Save and activate.

The service is now reachable at:

```
https://<your-tenant>.abap.<region>.hana.ondemand.com/sap/bc/http/sap/zgcts_http_service_cloud
```

---

## Set up the gCTS destination (one-time)

The analyzer reads transport contents through a customer-managed HTTP destination called `GCTS_LOCAL`:

1. Create a **Communication Arrangement** (or BTP Destination Service entry) named `GCTS_LOCAL`.
2. Target URL: your tenant's gCTS endpoint (typically the same tenant; loopback URL).
3. Authentication: business user with role `SAP_BR_DEVELOPER` or equivalent gCTS-read permission.

Without this destination the analyzer's `read_gcts_commit_objects( )` will catch the connection failure, log it, and return an empty inventory. The handler still returns HTTP 200 with `objectCount:0`, so you can verify activation independently of gCTS reachability.

---

## Smoke test

```bash
curl -u <user>:<token> -X POST \
  -H 'Content-Type: application/json' \
  --data '{"input":[{"id":"<your-commit-or-TR-id>"}]}' \
  https://<your-tenant>.abap.<region>.hana.ondemand.com/sap/bc/http/sap/zgcts_http_service_cloud
```

Expected (regardless of whether gCTS returns objects):

```json
{
  "label": "<your-id>",
  "objectCount": 0,
  "depCount": 0,
  "deps": []
}
```

| HTTP status | Likely cause | Fix |
|---|---|---|
| 200, depCount=0 | gCTS returned no objects (empty commit, or destination not configured) | Set up `GCTS_LOCAL` destination |
| 400 | Body is not valid JSON, or no `"id":"..."` found | Check the `--data` payload |
| 401 | Wrong user / token | Check Communication User in Communication Arrangement |
| 403 | Missing IAM scope on the user | Add the catalog/service to the business role |
| 404 | Service binding not active | Confirm `ZGCTS_HTTP_SERVICE_CLOUD` is published |
| 405 | Hit the endpoint with GET instead of POST | Use `-X POST` |
| 500 | Genuine code error | Check tenant trace; the error JSON body has the message |

---

## Differences vs the classic README

| Topic | Classic README | This README |
|---|---|---|
| `*.locals.txt` files | Local types of analyser class | **Not needed** — cloud analyser inlines all helpers |
| `ZGCTS_ANALYZE_HANDLER` (`IF_HTTP_EXTENSION`) | SICF node activation | Replaced by `ZCL_GCTS_HTTP_HANDLER_CLOUD` + HTTP Service binding |
| ICF node `/sap/bc/zgcts/analyze` | Activated via SICF | SICF doesn't exist in cloud; service path is `/sap/bc/http/sap/zgcts_http_service_cloud` (or whatever you map via Communication Scenario) |
| `CL_CI_TEST_OBJECT` ATC check | Available | Not shipped — see below |
| `cl_demo_output` debug path | Available | Removed; use the JSON response |

---

## Honest scope statement

| Capability | Classic | Cloud |
|---|---|---|
| Inventory objects from a transport | E070/E071 reads | gCTS REST `/repository/.../commits/.../objects` |
| Class superclass / interfaces | SEOMETAREL select | **Not in MVP** — see Roadmap below |
| Function group → modules | TFDIR select | **Not in MVP** — see Roadmap below |
| DDIC where-used (DTEL→DOMA, TABL→DTEL) | DD03L/DD04L select | **Not in MVP** — see Roadmap below |
| Risk classification | In-class logic | Same logic exposed; all rows are `INVENTORIED` until deeper walking is added |
| History persistence | `INSERT zgcts_hist` | Same table, same field mapping, `@`-escaped |
| HTTP endpoint | `IF_HTTP_EXTENSION` + SICF | `IF_HTTP_SERVICE_EXTENSION` + HTTP Service binding |
| ATC integration | `CL_CI_TEST_OBJECT` | **Not shipped** — base class cloud-release status varies by tenant |

The cloud MVP focuses on the **reliable** features: object inventory + persistence + HTTP exposure. Deeper dependency walking is intentionally deferred because the XCO content struct shapes vary across SAP BTP ABAP Environment SP levels — shipping speculative XCO code that activates on one tenant and not another is worse than not shipping it at all.

---

## Roadmap (extending the cloud analyser per-tenant)

Once you have a specific cloud tenant and have confirmed its XCO release level, you can add deeper dependency walking inside `STAGE2_INVENTORY_TO_DEPS`. The patterns SAP documents:

- `XCO_CP_ABAP_REPOSITORY=>OBJECT->CLAS->FOR( name )` — class handle
- `XCO_CP_ABAP_REPOSITORY=>OBJECT->FUGR->FOR( name )` — function group handle
- `XCO_CP_ABAP_DICTIONARY=>DATA_ELEMENT( name )` — DDIC data element handle

The exact sub-attribute chain (`->definition->content( )->get_super_class( )` vs `->content( )->...`) depends on your tenant's XCO version. Verify with **F2 / Code Completion** in ADT before coding.

Wrap every XCO call in `TRY ... CATCH cx_root` and emit a log line via the analyser's `out( )` method on failure. This way a wrong attribute on one tenant degrades to a single missing dependency row rather than killing the run.

---

## ATC integration on cloud

If you need TR Analyser findings to surface in ATC results on cloud:

1. Identify the cloud-released ATC base class on your tenant (`CL_CI_TEST_ROOT`, `CL_CI_TEST_ABAP`, or a tenant-specific subclass).
2. Create a small wrapper class inheriting from that base class.
3. In the `RUN` redefinition, instantiate `ZCL_GCTS_TR_ANALYZER_CLOUD`, call `RUN( )`, then iterate `GET_DEPS( )` and emit findings via the cloud ATC inform API for that base class.

The wrapper is tenant-specific so we don't ship one in this repo. The analyzer class is built to support it: `GET_DEPS( )` returns the typed `TT_DEPS` table directly, no parsing required.