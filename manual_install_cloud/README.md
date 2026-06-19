# Manual install — **Public Cloud** variant

Use this folder if you are installing on **SAP BTP ABAP Environment**, **S/4HANA Cloud Public**, or **Steampunk** (any ABAP Cloud / strict-allow-list landscape).

If you are on Private Cloud or on-prem, use [`../manual_install/`](../manual_install/) instead.

The same repo holds both variants; you install **one or the other**, never both.

---

## Why a separate folder?

Public Cloud blocks every direct read of `E070`, `E071`, `DD03L`, `DD04L`, `SEOMETAREL`, `TFDIR`, plus `CL_DEMO_OUTPUT` and unescaped host vars in OpenSQL. The classic source under `../manual_install/` will not activate. The cloud variant uses **only released APIs** (XCO + gCTS REST + cloud HTTP framework) and passes the strict-allow-list syntax check.

For the full mapping of classic operations → cloud equivalents, see [`../abap_cloud/README_CLOUD.md`](../abap_cloud/README_CLOUD.md).

---

## Install order

| # | File | Object name | Type | Where to paste in ADT |
|---|---|---|---|---|
| 1 | [`01_ZGCTS_HIST.tabl.txt`](../manual_install/01_ZGCTS_HIST.tabl.txt) | `ZGCTS_HIST` | DDIC table | Same as classic — DB Table editor. Custom Z-tables ARE allowed in cloud. |
| 2 | [`02_ZCL_GCTS_TR_ANALYZER_CLOUD.clas.txt`](02_ZCL_GCTS_TR_ANALYZER_CLOUD.clas.txt) | `ZCL_GCTS_TR_ANALYZER_CLOUD` | Global class | Class editor → "Global Class" tab |
| 3 | [`03_ZCL_GCTS_HTTP_HANDLER_CLOUD.clas.txt`](03_ZCL_GCTS_HTTP_HANDLER_CLOUD.clas.txt) | `ZCL_GCTS_HTTP_HANDLER_CLOUD` | Global class | Class editor → "Global Class" tab |
| 4 | (no file — see below) | `ZGCTS_HTTP_SERVICE_CLOUD` | HTTP Service binding | **Create interactively in ADT** (see step 4 below) |
| 5 | [`04_ZCL_GCTS_DEP_ATC_CHK_CLOUD.clas.txt`](04_ZCL_GCTS_DEP_ATC_CHK_CLOUD.clas.txt) | `ZCL_GCTS_DEP_ATC_CHK_CLOUD` | Global class | Class editor → "Global Class" tab |

---

## Step-by-step

### 1. DDIC table `ZGCTS_HIST`

The cloud variant reuses the **same** custom table from the classic install. Open [`../manual_install/01_ZGCTS_HIST.tabl.txt`](../manual_install/01_ZGCTS_HIST.tabl.txt), copy field list, paste into ADT's "Database Table" editor, save, activate.

> Custom Z-tables (anything starting with `Z` or `Y` in your customer namespace) are always allowed in cloud. Only **SAP-internal** tables like `E070` are blocked.

### 2. Class `ZCL_GCTS_TR_ANALYZER_CLOUD`

1. ADT → File → New → ABAP Class → Name `ZCL_GCTS_TR_ANALYZER_CLOUD`, package `ZGCTS_CLOUD` (or your customer package).
2. **Important:** when creating, set **ABAP Language Version** = `ABAP for Cloud Development`. Otherwise XCO calls will be rejected later.
3. Paste content from [`02_ZCL_GCTS_TR_ANALYZER_CLOUD.clas.txt`](02_ZCL_GCTS_TR_ANALYZER_CLOUD.clas.txt) into the "Global Class" tab.
4. Activate (Ctrl+F3).

### 3. Class `ZCL_GCTS_HTTP_HANDLER_CLOUD`

Same pattern as step 2. Paste from [`03_ZCL_GCTS_HTTP_HANDLER_CLOUD.clas.txt`](03_ZCL_GCTS_HTTP_HANDLER_CLOUD.clas.txt). This class implements `IF_HTTP_SERVICE_EXTENSION`, the cloud-released HTTP entry point.

### 4. HTTP Service binding `ZGCTS_HTTP_SERVICE_CLOUD`

There is no copy-paste source for this — it's a metadata object created via the wizard:

1. ADT → File → New → Other ABAP Repository Object → "HTTP Service".
2. Name: `ZGCTS_HTTP_SERVICE_CLOUD`, description: `TR Analyser cloud HTTP entry`, package: `ZGCTS_CLOUD`.
3. **Handler class**: `ZCL_GCTS_HTTP_HANDLER_CLOUD` (the one you just created).
4. Save & activate.
5. The service will be reachable at:
   ```
   https://<your-tenant>.abap.<region>.hana.ondemand.com/sap/bc/http/sap/zgcts_http_service_cloud
   ```
   Or, if you map a friendly path in **Communication Scenario / Service Binding**, at the path you choose (e.g. `/sap/bc/zgcts/analyze`).

### 5. Class `ZCL_GCTS_DEP_ATC_CHK_CLOUD`

Same pattern as step 2. Paste from [`04_ZCL_GCTS_DEP_ATC_CHK_CLOUD.clas.txt`](04_ZCL_GCTS_DEP_ATC_CHK_CLOUD.clas.txt). After activation:

1. ADT → ATC → Configuration → Custom Checks → **Register check class** → enter `ZCL_GCTS_DEP_ATC_CHK_CLOUD`.
2. Add it to your check variant.
3. Run ATC on a transport — dependency findings now appear in the standard ATC result list.

---

## Set up the gCTS destination (one-time)

The analyzer reads transport contents through a customer-managed HTTP destination called `GCTS_LOCAL`. Create it once:

1. **Communication Arrangement** for outbound HTTP (or **BTP Destination Service** if you prefer).
2. Name: `GCTS_LOCAL`.
3. Target URL: your tenant's gCTS endpoint (typically the same tenant; use the local loopback URL).
4. Authentication: business user with role `SAP_BR_DEVELOPER` or equivalent gCTS-read permission.

The exact field list depends on your tenant version — see your platform docs under **gCTS — Outbound Communication**.

---

## Smoke test

```bash
curl -u <user>:<token> -X POST \
  -H 'Content-Type: application/json' \
  --data '{"input":[{"id":"<your-commit-or-TR-id>"}]}' \
  https://<your-tenant>.abap.<region>.hana.ondemand.com/sap/bc/http/sap/zgcts_http_service_cloud
```

Expected: HTTP 200 with JSON `{ "label":"...", "objectCount":N, "depCount":M, "deps":[...] }`.

| Status | Likely cause | Fix |
|---|---|---|
| 401 | Wrong user / token | Check Communication User in Communication Arrangement |
| 403 | Missing IAM scope | Add the catalog/service to the business role |
| 404 | Service binding not active | Check `ZGCTS_HTTP_SERVICE_CLOUD` is published |
| 500 + `gCTS REST call failed` | Destination `GCTS_LOCAL` missing or wrong | See "Set up the gCTS destination" above |

---

## Differences vs the classic README

| Topic | Classic README | This README |
|---|---|---|
| File `02_*.locals.txt` | Local types of analyser class | **Not needed** — the cloud analyser inlines its helpers |
| File `03_ZGCTS_ANALYZE_HANDLER` | `IF_HTTP_EXTENSION` + SICF node activation | Replaced by `ZCL_GCTS_HTTP_HANDLER_CLOUD` + HTTP Service binding |
| ICF node `/sap/bc/zgcts/analyze` | Activated via SICF transaction | Mapped via Communication Scenario; SICF doesn't exist in cloud |
| `CL_CI_TEST_OBJECT` ATC base | Allowed | Replaced by `CL_CI_TEST_ROOT` (cloud-released) |
| `cl_demo_output` debug path | Available | Removed; output via HTTP response only |