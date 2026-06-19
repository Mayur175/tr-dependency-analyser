# Session handover — 2026-06-19 → 2026-06-20

This file is the running notes from today's session. Read it top-to-bottom
tomorrow before touching anything; it tells you exactly where you stopped
and what the next click is.

---

## Where we are right now (the truth)

### What works end-to-end

| Layer                          | Status |
|--------------------------------|--------|
| GitHub repo `tr-dependency-analyser`           | clean, `main` and `gh-pages` both up to date |
| Eclipse plugin build (Maven Tycho)             | green; latest build qualifier `1.0.0.202606191949` |
| GitHub Pages update site `https://mayur175.github.io/tr-dependency-analyser/` | live, serves the latest build |
| GitHub Release `v1.0.0`                        | published with the update-site ZIP attached |
| Eclipse plugin install                         | installed locally; "TR Analyser → Check for Updates" now self-heals broken sites and points at GitHub Pages |
| Cloud-mode preferences                         | implemented (System URL, Username/Password optional, Cloud mode checkbox, Service path) |
| Cloud HTTP handler class                       | source ready in `abap_cloud/src/zcl_gcts_http_handler_cloud.clas.abap` and mirrored in `manual_install_cloud/03_ZCL_GCTS_HTTP_HANDLER_CLOUD.clas.txt` |
| Cloud analyser class                           | source ready in `abap_cloud/src/zcl_gcts_tr_analyzer_cloud.clas.abap` and mirrored in `manual_install_cloud/02_ZCL_GCTS_TR_ANALYZER_CLOUD.clas.txt` |
| `ZGCTS_HIST` table                             | DDL exists in repo, deployed in customer system |

### What is in a half-broken state on the BTP tenant

We were configuring a fresh BTP Public Cloud tenant
`my4101910.lab.s4hana.cloud.sap` for user `CB9980000038`. This is where we
stopped:

1. The customer originally created the cloud HTTP handler class with the
   accidental name `ZCL_CL_GCTS_HTTP_HANDLER_CLOUD` (extra `_CL_` infix).
2. The HTTP Service binding `ZCL_GCTS_HTTP_HANDLER_CLOUD` was created
   pointing at that class name. So far so good.
3. The customer then **deleted the class** `ZCL_CL_GCTS_HTTP_HANDLER_CLOUD`
   without first detaching it from the binding.
4. As a consequence:
   - The HTTP Service binding now has a dangling handler-class pointer.
   - Trying to delete the binding fails with
     `Error while reading the object R3TR from the database`.
   - The binding's "Configure authorization default values" dialog opens
     but the **Retrieve** button is greyed out, because the system cannot
     read the (missing) class to suggest auth objects.
   - The customer business user `CB9980000038` cannot call the binding
     because nothing is published; Eclipse currently returns
     `HTTP 401 Unauthorized`.

So three things need fixing on the BTP side, in order. They are documented
below. **Nothing in the GitHub repo or Eclipse plugin needs to change to
unblock this.**

---

## Tomorrow's first action: heal the orphan binding

### Step 1 — recreate `ZCL_CL_GCTS_HTTP_HANDLER_CLOUD` with the same name

Yes, the same name including `_CL_`. We are not renaming anything; we are
giving the dangling pointer a real target so the binding becomes editable
again.

1. ADT → Project Explorer → right-click the package that holds the other
   `ZCL_GCTS_*` classes → **New → ABAP Class**.
2. **Name**: `ZCL_CL_GCTS_HTTP_HANDLER_CLOUD` (exact, with `_CL_`).
3. **Description**: `TR Analyser - cloud HTTP handler`.
4. **Next → Finish**.

ADT opens a stub class.

### Step 2 — paste the source

The source below has the class name `zcl_cl_gcts_http_handler_cloud` in
both the DEFINITION and IMPLEMENTATION lines so it matches the file name
SAP just registered. Do **not** edit the class name in the source.

```abap
"! <p class="shorttext synchronized">TR Analyser - Public Cloud HTTP handler</p>
"!
"! Cloud-released HTTP service extension. Wired to the HTTP Service
"! binding ZCL_GCTS_HTTP_HANDLER_CLOUD on the BTP / S/4HANA Public
"! Cloud tenant. The class name is ZCL_CL_GCTS_HTTP_HANDLER_CLOUD
"! because that is what the existing binding already references; the
"! "_CL_" infix is purely historical and has no functional meaning.
CLASS zcl_cl_gcts_http_handler_cloud DEFINITION
  PUBLIC FINAL
  CREATE PUBLIC.

  PUBLIC SECTION.

    INTERFACES if_http_service_extension.

  PRIVATE SECTION.

    METHODS handle_post
      IMPORTING io_request  TYPE REF TO if_web_http_request
                io_response TYPE REF TO if_web_http_response.

    METHODS write_error
      IMPORTING io_response TYPE REF TO if_web_http_response
                iv_status   TYPE i
                iv_text     TYPE string.

    METHODS extract_input_ids
      IMPORTING iv_body         TYPE string
      RETURNING VALUE(rt_input) TYPE zcl_gcts_tr_analyzer_cloud=>tt_input.

    METHODS json_escape
      IMPORTING iv_value          TYPE string
      RETURNING VALUE(rv_escaped) TYPE string.

ENDCLASS.


CLASS zcl_cl_gcts_http_handler_cloud IMPLEMENTATION.

  METHOD if_http_service_extension~handle_request.

    DATA(lo_request)  = request.
    DATA(lo_response) = response.

    TRY.
        DATA(lv_method) = lo_request->get_method( ).

        IF lv_method = 'POST'.
          handle_post( io_request  = lo_request
                       io_response = lo_response ).
        ELSE.
          write_error( io_response = lo_response
                       iv_status   = 405
                       iv_text     = |Method { lv_method } not allowed; use POST| ).
        ENDIF.

      CATCH cx_root INTO DATA(lo_ex).
        write_error( io_response = lo_response
                     iv_status   = 500
                     iv_text     = lo_ex->get_text( ) ).
    ENDTRY.

  ENDMETHOD.


  METHOD handle_post.

    DATA(lv_body)  = io_request->get_text( ).
    DATA(lt_input) = extract_input_ids( lv_body ).

    IF lt_input IS INITIAL.
      write_error( io_response = io_response
                   iv_status   = 400
                   iv_text     = `Body must contain non-empty "input" array of {"id":"..."} entries` ).
      RETURN.
    ENDIF.

    DATA(lo_analyzer) = NEW zcl_gcts_tr_analyzer_cloud(
      it_input            = lt_input
      iv_include_external = abap_false ).

    lo_analyzer->run( ).
    lo_analyzer->persist_result( ).

    DATA(lv_json) = lo_analyzer->to_json( ).

    io_response->set_status( i_code   = 200
                             i_reason = 'OK' ).
    io_response->set_header_field( i_name  = 'Content-Type'
                                   i_value = 'application/json; charset=utf-8' ).
    io_response->set_text( lv_json ).

  ENDMETHOD.


  METHOD extract_input_ids.

    " Hand-rolled extractor. Looks for every "id":"VALUE" occurrence
    " inside the body and treats each as one input row.
    DATA(lv_rest) = iv_body.

    DO.
      FIND FIRST OCCURRENCE OF REGEX
        `"id"\s*:\s*"([^"]*)"`
        IN lv_rest
        SUBMATCHES DATA(lv_id).

      IF sy-subrc <> 0.
        EXIT.
      ENDIF.

      IF lv_id IS NOT INITIAL.
        APPEND VALUE #( id = lv_id ) TO rt_input.
      ENDIF.

      DATA(lv_idx) = find( val = lv_rest sub = lv_id ).
      IF lv_idx < 0.
        EXIT.
      ENDIF.
      lv_rest = lv_rest+lv_idx.
      lv_idx  = find( val = lv_rest sub = `"` ).
      IF lv_idx < 0.
        EXIT.
      ENDIF.
      lv_rest = lv_rest+lv_idx.
      lv_rest = lv_rest+1.
    ENDDO.

  ENDMETHOD.


  METHOD write_error.

    DATA(lv_json) = `{"error":"` && json_escape( iv_text ) && `"}`.

    io_response->set_status( i_code   = iv_status
                             i_reason = 'Error' ).
    io_response->set_header_field( i_name  = 'Content-Type'
                                   i_value = 'application/json; charset=utf-8' ).
    io_response->set_text( lv_json ).

  ENDMETHOD.


  METHOD json_escape.

    rv_escaped = iv_value.
    REPLACE ALL OCCURRENCES OF `\` IN rv_escaped WITH `\\`.
    REPLACE ALL OCCURRENCES OF `"` IN rv_escaped WITH `\"`.
    REPLACE ALL OCCURRENCES OF cl_abap_char_utilities=>newline
            IN rv_escaped WITH `\n`.
    REPLACE ALL OCCURRENCES OF cl_abap_char_utilities=>cr_lf
            IN rv_escaped WITH `\n`.

  ENDMETHOD.

ENDCLASS.
```

### Step 3 — save and activate

- **Cmd+S** → no save error. The class name in source matches the file.
- **Cmd+F3** → activate. Both definition and implementation should turn
  green.

### Step 4 — re-open the binding `ZCL_GCTS_HTTP_HANDLER_CLOUD`

Close the binding tab if it is open. Double-click the binding in Project
Explorer to re-open it. The handler-class link should now be live and
clicking it should jump into the class you just created.

### Step 5 — switch the binding into Edit mode

The "Retrieve" button in the auth-defaults dialog was greyed because the
binding was effectively in display mode. In the binding editor:

- Look at the top-right corner.
- Click the pencil / unlock / "Edit" button to acquire a write lock.
- The buttons in the dialog go solid; the title bar shows a `*` for
  unsaved changes.

### Step 6 — Configure authorization default values → Retrieve → Save

1. Click the link **Configure authorization default values** in the
   binding's general info area.
2. In the new dialog, top-right, click **Retrieve**. It should now be
   active (no longer greyed).
3. The grid populates. The `S_START` row's status changes from
   "No Default" to "Default Set".
4. **Cmd+S**. Close the dialog.

### Step 7 — Publish the binding

Back on the binding tab, top-right, click **Publish Locally**. Wait for
the spinner to finish.

This is the moment the binding becomes callable from outside ADT.

---

## Then verify with curl from the terminal

```bash
curl -i -u "CB9980000038:<password>" \
     -H "Content-Type: application/json" \
     -X POST \
     -d '{"input":[{"id":"GMWK900691"}]}' \
     "https://my4101910.lab.s4hana.cloud.sap:443/sap/bc/http/sap/ZCL_GCTS_HTTP_HANDLER_CLOUD?sap-client=080"
```

Expected outcomes and the action for each:

| First HTTP line | Action |
|---|---|
| `HTTP/2 200` plus a JSON body | Done with backend. Move to the Eclipse step below. |
| `HTTP/2 401`                  | Wrong password; retype. If still 401, the user is not assigned the catalog the binding generated → see "IAM scope" below. |
| `HTTP/2 403`                  | User authenticated but no scope on the binding → "IAM scope" below. |
| `HTTP/2 404`                  | URL mismatch — copy the binding URL exactly. |
| `HTTP/2 405`                  | Wrong method (binding wants POST). Should not happen if you used the curl above verbatim. |

### IAM scope (only if 401 or 403 from curl)

When you published the binding in step 7, BTP auto-generated a Business
Catalog for it. The calling user `CB9980000038` needs that catalog in one
of its Business Roles.

1. Open the SAP Fiori launchpad of the tenant
   (`https://my4101910.lab.s4hana.cloud.sap/ui` or whatever entry the
   tenant uses).
2. Open the app **Maintain Business Roles**.
3. Find the role `CB9980000038` already has (often `SAP_BR_DEVELOPER` or
   `SAP_BR_ADMINISTRATOR` on lab tenants). If the role is SAP-standard
   and not editable, **Copy** it to `ZBR_DEVELOPER_TR` and edit the copy.
4. In **Assigned Business Catalogs**, click **Add**. Search for
   `ZGCTS` — the catalog auto-generated for the binding will appear (its
   name is something like `ZGCTS_…` or whatever package prefix you used).
5. Tick it. Save the role.
6. If you copied the role, also open **Maintain Business Users**, find
   `CB9980000038`, replace the old role with `ZBR_DEVELOPER_TR`, save.

Wait ~30 seconds, re-run the curl. Should return 200.

---

## Then configure the Eclipse plugin

Only after curl returns 200. **Window → Preferences → TR Analyser**:

| Field | Value |
|---|---|
| **SAP System URL**            | `https://my4101910.lab.s4hana.cloud.sap:443` |
| **Username (optional)**       | `CB9980000038` |
| **Password (optional)**       | (your password — retype, don't paste) |
| **Timeout (seconds)**         | `30` |
| **Cloud mode (BTP)**          | ✅ |
| **Service path (Cloud mode)** | `/sap/bc/http/sap/ZCL_GCTS_HTTP_HANDLER_CLOUD?sap-client=080` |

Critical traps to avoid:

- **Drop the `-api`** from the host. The tenant's `*-api.…` host is the
  Communication-API gateway and only accepts Communication Users.
  Business user `CB9980000038` → 401 there.
- **Cloud mode must be ticked**. Without it the plugin falls back to the
  on-prem path `/sap/bc/zgcts/analyze` which does not exist on BTP and
  returns 403 from the dispatcher.
- **The `?sap-client=080` is part of the Service path**. The plugin
  appends the Service path verbatim to the System URL.

Click **Apply and Close**.

---

## Then run TR Analyser in Eclipse

1. In Project Explorer click on your ABAP project node so it is the
   active selection.
2. **TR Analyser → TR Analyser…** (or **Cmd+Alt+G**).
3. Enter `GMWK900691` (or any TR you have on the tenant). OK.
4. The TR Analyser view should populate. To prove the handler ran,
   open `ZGCTS_HIST` → Display Data Preview → fresh row with today's
   `RUN_TS`.

If still red:

| Eclipse error message                              | Likely cause                                  | Fix |
|---|---|---|
| `HTTP 401 Unauthorized`                            | Bad password OR wrong host (`-api`)           | Retype password; drop `-api` |
| `HTTP 403 Forbidden`                               | Catalog not in user's role                    | IAM scope step above |
| `HTTP 404 - endpoint not found`                    | Service path wrong or binding not published   | Recopy URL from binding; click Publish Locally |
| `HTTP 405 Method Not Allowed`                      | Cloud mode is not ticked                      | Tick Cloud mode |
| `Cannot reach …`                                   | Network / VPN / proxy                         | Same curl from terminal will fail too |

---

## Repository state at end of session

| Branch       | HEAD commit | Purpose |
|--------------|-------------|---------|
| `main`       | `a2ebab8`   | Source. Latest change: self-healing Check for Updates handler. |
| `gh-pages`   | `a62de9b`   | Live p2 update site at `https://mayur175.github.io/tr-dependency-analyser/`. |
| `tr-dep/main` and `tr-dep/gh-pages` are both up to date with the local commits. |
| Latest plugin build qualifier: `1.0.0.202606191949` |
| GitHub Release `v1.0.0` carries `com.gmw.gcts.analyzer.updatesite-1.0.0-SNAPSHOT.zip` (58 KB) for offline install. |

### How to rebuild and republish (script for tomorrow)

If you ever modify the plugin source on `main` and want it on the
update site:

```bash
# 1. Build
cd "TR dependency/eclipse"
mvn clean package -DskipTests

# 2. Refresh gh-pages
cd "TR dependency"
git fetch tr-dep gh-pages
git worktree add /tmp/gh-pages-wt tr-dep/gh-pages
cd /tmp/gh-pages-wt
git checkout -B gh-pages

SRC="<absolute path>/TR dependency/eclipse/com.gmw.gcts.analyzer.updatesite/target/repository"
rm -rf artifacts.jar artifacts.xml.xz content.jar content.xml.xz p2.index features plugins
cp -R "$SRC"/{artifacts.jar,artifacts.xml.xz,content.jar,content.xml.xz,p2.index,features,plugins} .
cp "<absolute path>/TR dependency/eclipse/com.gmw.gcts.analyzer.updatesite/target/com.gmw.gcts.analyzer.updatesite-1.0.0-SNAPSHOT.zip" \
   dist/com.gmw.gcts.analyzer.updatesite-1.0.0.zip

git add -A
git commit -m "Publish update site - <change summary>"
git push tr-dep gh-pages

cd - && git worktree remove /tmp/gh-pages-wt
```

About 60 seconds later, GitHub Pages serves the new files. In Eclipse,
**Help → Check for Updates** picks them up automatically because the
self-healing handler we shipped today registered the URL.

---

## Useful URLs

- Repo:           https://github.com/Mayur175/tr-dependency-analyser
- Update site:    https://mayur175.github.io/tr-dependency-analyser/
- Release v1.0.0: https://github.com/Mayur175/tr-dependency-analyser/releases/tag/v1.0.0
- BTP tenant:     https://my4101910.lab.s4hana.cloud.sap
- Bound URL:      https://my4101910.lab.s4hana.cloud.sap:443/sap/bc/http/sap/ZCL_GCTS_HTTP_HANDLER_CLOUD?sap-client=080

---

## TL;DR for tomorrow

1. Recreate `ZCL_CL_GCTS_HTTP_HANDLER_CLOUD` with the source above.
2. Save & activate.
3. Re-open binding, switch to Edit mode (pencil top-right).
4. Click **Configure authorization default values** → **Retrieve** → Save.
5. **Publish Locally**.
6. Curl from terminal — should return `HTTP/2 200`. If not, IAM scope.
7. In Eclipse Preferences: drop `-api`, tick Cloud mode, set Service path.
8. Click TR Analyser → enter TR id → see results, see rows in `ZGCTS_HIST`.