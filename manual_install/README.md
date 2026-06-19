# Manual install — copy & paste each file into ADT

This folder contains everything you need to install the TR Analyser ABAP backend
on your SAP system **without abapGit**. There is one file per ABAP object,
ready to copy and paste directly into the corresponding ADT editor.

---

## Object list

You will create exactly **3 mandatory + 1 optional** objects:

| # | Object | Type | Mandatory? | Source file |
|---|--------|------|------------|-------------|
| 1 | `ZGCTS_DEP_HISTORY` | Database table | **Yes** — analyzer writes to it | `01_ZGCTS_DEP_HISTORY.tabl.txt` |
| 2 | `ZCL_GCTS_TR_ANALYZER` | Class — analysis engine | **Yes** — main logic | `02_ZCL_GCTS_TR_ANALYZER.clas.txt` + `02_ZCL_GCTS_TR_ANALYZER.locals.txt` |
| 3 | `ZGCTS_ANALYZE_HANDLER` | Class — HTTP handler | **Yes** — Eclipse plugin calls it | `03_ZGCTS_ANALYZE_HANDLER.clas.txt` |
| 4 | `ZCL_GCTS_DEP_ATC_CHECK` | Class — ATC plug-in | Optional | `04_ZCL_GCTS_DEP_ATC_CHECK.clas.txt` + `04_ZCL_GCTS_DEP_ATC_CHECK.locals.txt` |

Create them **in this order** because each step depends on the previous one.

---

## How to install each object — the pattern

For **each** object, the steps are always:

1. In Eclipse / ADT: right-click your package → **New** → pick the right object type.
2. Give it the exact name from the table above.
3. **Save** the empty stub.
4. Open the file from this folder (e.g. `02_ZCL_GCTS_TR_ANALYZER.clas.txt`) → select all → copy.
5. In ADT, click into the matching tab (Global Class / Local Types / etc.) → select all (Ctrl+A or Cmd+A) → paste.
6. **Ctrl+S** (save) → **Ctrl+F3** (activate).

Detailed step-by-step per object below.

---

## Step 1 — Create `ZGCTS_DEP_HISTORY` (database table)

1. Right-click your package → **New** → **Other ABAP Repository Object** → search **Database Table** → **Next**.
2. Name: `ZGCTS_DEP_HISTORY`.
   Description: `TR Analyser - Dependency Analysis History`.
   Pick your TR. **Finish**.
3. The empty table opens in the DDL editor.
4. Open `01_ZGCTS_DEP_HISTORY.tabl.txt` from this folder, **select all → copy**.
5. In the ADT editor, **select all → paste** (replaces the entire content).
6. **Ctrl+S** → **Ctrl+F3** (activate). Icon turns green.

---

## Step 2 — Create class `ZCL_GCTS_TR_ANALYZER`

1. Right-click your package → **New** → **ABAP Class**.
2. Name: `ZCL_GCTS_TR_ANALYZER`.
   Description: `TR Analyser - analysis engine`.
   Finish.
3. The class opens with five tabs at the bottom:
   - **Global Class**
   - **Class-relevant Local Types**
   - **Local Types**
   - **Test Classes**
   - **Macros**
4. **In the Global Class tab:**
   - Open `02_ZCL_GCTS_TR_ANALYZER.clas.txt` → copy all → paste over everything in the tab.
5. **In the Local Types tab:**
   - Open `02_ZCL_GCTS_TR_ANALYZER.locals.txt` → copy all → paste over everything in the tab.
6. **Ctrl+S** → **Ctrl+F3**. Should turn green.

---

## Step 3 — Create class `ZGCTS_ANALYZE_HANDLER` (the HTTP handler)

1. Right-click your package → **New** → **ABAP Class**.
2. Name: `ZGCTS_ANALYZE_HANDLER`.
   Description: `TR Analyser - HTTP handler`.
   Finish.
3. Open `03_ZGCTS_ANALYZE_HANDLER.clas.txt` → copy all → paste into the **Global Class** tab.
4. **Ctrl+S** → **Ctrl+F3**.

This class has no Local Types tab content (it's all in the Global Class).

> ⚠ **Cloud-edition note:** if activation fails with *"`IF_HTTP_EXTENSION` is not released for cloud development"*, your edition is BTP ABAP Environment / Steampunk. Tell me the exact error and I'll provide a `IF_HTTP_SERVICE_EXTENSION` port.

---

## Step 4 — (Optional) Create class `ZCL_GCTS_DEP_ATC_CHECK` (ATC plug-in)

Skip this if you don't use ATC. Otherwise:

1. New → ABAP Class → name `ZCL_GCTS_DEP_ATC_CHECK`.
2. **Global Class tab:** paste from `04_ZCL_GCTS_DEP_ATC_CHECK.clas.txt`.
3. **Local Types tab:** paste from `04_ZCL_GCTS_DEP_ATC_CHECK.locals.txt`.
4. **Ctrl+S** → **Ctrl+F3**.

---

## Step 5 — Wire up the HTTP entry point

The class is installed but not yet exposed on a URL. Configure either SICF (on-prem / private edition) or HTTP Service (BTP ABAP Environment) per `abap/docs/SICF_SETUP.md`. Short version:

### On-prem / Private Edition (SICF)
1. Run transaction `SICF`.
2. Tree: `default_host` → `sap` → `bc`.
3. New Sub-Element → Independent Service → `zgcts`.
4. Inside `zgcts`: New Sub-Element → Service Element → `analyze`.
5. On `analyze`: Logon Data = Standard. Handler List → add `ZGCTS_ANALYZE_HANDLER`.
6. Right-click `analyze` → Activate Service.

### BTP ABAP Environment (no SICF)
1. **File → New → Other → ABAP → HTTP Service**.
2. Name `ZGCTS_ANALYZE`. Handler class `ZGCTS_ANALYZE_HANDLER`.
3. Save.

---

## Step 6 — Test

1. From Eclipse: right-click any TR → **TR Analyser…**
2. You should see clusters and a pull order.

You can also quickly smoke-test from your terminal:

```bash
curl -i -u "<sap-user>:<password>" \
  "https://<your-sap-host>/sap/bc/zgcts/analyze?tr=<some-tr-id>"
```

A response of `HTTP 200` with JSON = success. `HTTP 403` = the `c_enforce_auth` flag is still `abap_true` somewhere; double-check Step 3 picked up the `abap_false` version.

---

## Quick reference — security note

The class `ZGCTS_ANALYZE_HANDLER` ships with `c_enforce_auth = abap_false` so any authenticated SAP user can read TR contents (sandbox/pilot use). Every response carries header `X-Auth-Bypass: yes` to make this visible to monitoring.

**Before promoting to QA / PROD**: open `ZGCTS_ANALYZE_HANDLER`, find the `c_enforce_auth` constant near the top, change `abap_false` back to `abap_true`, activate. Then ask Basis to grant `S_TRANSPRT (TTYPE=CUST, ACTVT=03)` to any user who needs to run TR Analyser.

---

## If anything fails

Send me the **exact error text** from the Problems view in Eclipse. The error tells me whether your edition needs a port (and to which API) — I'll do the rewrite and push to GitHub.