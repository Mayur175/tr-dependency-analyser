# Install the TR Analyser backend via abapGit (one-click)

The repo is **already in abapGit-compatible layout**. Files are at:

```
TR dependency/abap/
├── .abapgit.xml                  ← repo descriptor (FOLDER_LOGIC=FULL)
└── src/
    ├── package.devc.xml          ← package description
    ├── zcl_gcts_tr_analyzer.clas.abap          ← analyser core
    ├── zcl_gcts_tr_analyzer.clas.locals_def.abap
    ├── zcl_gcts_tr_analyzer.clas.xml
    ├── zgcts_analyze_handler.clas.abap         ← ICF HTTP handler
    ├── zgcts_analyze_handler.clas.xml
    ├── zcl_gcts_dep_atc_check.clas.abap        ← ATC check
    ├── zcl_gcts_dep_atc_check.clas.locals_def.abap
    ├── zcl_gcts_dep_atc_check.clas.xml
    └── zgcts_dep_history.tabl.xml              ← persistence table (DD02V/DD03P/DD09L)
```

The `.abapgit.xml` declares:

```xml
<STARTING_FOLDER>/src/</STARTING_FOLDER>
<FOLDER_LOGIC>FULL</FOLDER_LOGIC>
<MASTER_LANGUAGE>E</MASTER_LANGUAGE>
```

abapGit clones the entire Git repo, then reads `STARTING_FOLDER` to know
which sub-tree contains the SAP objects. So you point abapGit at the GitHub
repo URL and tell it sub-folder `/TR dependency/abap/`.

---

## Prerequisites

1. **abapGit installed on the SAP system.** One-time setup:
   <https://docs.abapgit.org/guide-install.html>. Either:
   - **Standalone** (`ZABAPGIT_STANDALONE`) — single ABAP report copied once
     into `SE38`, or
   - **Developer version** (`ZABAPGIT`) — multi-program version pulled from
     <https://github.com/abapGit/abapGit>.
2. **Authorisations on the SAP user**:
   - `S_DEVELOP` for class / table creation,
   - `S_TRANSPRT` for the workbench transport abapGit creates.
3. **Network egress** from the SAP system to the Git server hosting this
   repo. If your repo is on `github.tools.sap` the SAP system needs to reach
   that host on port 443 (typically over an internal proxy).
4. **Target package** — create a transportable customer package, e.g.
   `ZGCTS`, in `SE80` before pulling.

---

## Step 1 — Clone on the SAP system

In your SAP system:

1. `SE38` → run `ZABAPGIT` (or `ZABAPGIT_STANDALONE`).
2. Click **+ Online**.
3. **Git URL**: this repo's HTTPS URL.
   If your repo path needs a sub-folder, abapGit will infer it from the
   `.abapgit.xml`'s `STARTING_FOLDER`; otherwise you can set the sub-folder
   in the dialog (`/TR dependency/abap/`).
4. **Package**: `ZGCTS` (or your chosen package).
5. **Branch**: `main` (or whichever branch you publish).
6. **Pull** — abapGit imports the four classes + the table into the chosen
   package and opens an activation TR.
7. Mass-activate (`Ctrl+F3` from `SE80` or Project Explorer).
8. Verify in `SE80`: package `ZGCTS` should now contain
   - `ZCL_GCTS_TR_ANALYZER`,
   - `ZGCTS_ANALYZE_HANDLER`,
   - `ZCL_GCTS_DEP_ATC_CHECK`,
   - `ZGCTS_DEP_HISTORY`.

---

## Step 2 — Configure SICF (one-time, manual)

abapGit installs ABAP repository objects. **SICF service nodes are not ABAP
repository objects** — they live in customising — so they are out of abapGit's
scope. Configure once per system:

1. Transaction `SICF`.
2. Navigate `default_host → sap → bc`.
3. Right-click `bc` → **New Sub-Element** → name: `zgcts`.
4. Inside `zgcts` → **New Sub-Element** → name: `analyze`.
5. On the `analyze` node:
   - **Handler List**: class `ZGCTS_ANALYZE_HANDLER`.
   - **Logon Data**: usually "Standard" (uses caller's SSO / Basic auth).
6. Activate the `analyze` node (right-click → **Activate Service**).
7. Smoke test from your laptop:
   ```bash
   curl -u "USER:PASSWORD" \
     "https://<your-system>/sap/bc/zgcts/analyze?tr=GMWK900691"
   ```
   Expect HTTP 200 with a JSON body, or HTTP 403 / 401 if your user lacks
   `S_TRANSPRT` (TTYPE=`CUST`, ACTVT=`03`).

See `docs/SICF_SETUP.md` for the same steps with screenshots.

---

## Step 3 — Updates over time

When the repo changes (new release, bug fix):

```bash
# on your laptop
git pull
```

In the SAP system, go to abapGit → the existing repository → **Pull** again.
abapGit shows the diff before applying. Activate the changed objects.

---

## What this delivers

| Before abapGit packaging | After (today) |
|---|---|
| Open each `.clas.abap` file, paste into ADT, activate, repeat 4× | One **abapGit Online → Pull** |
| Table created manually in SE11 | Generated from `zgcts_dep_history.tabl.xml` |
| Updates require pasting again | `git pull` + abapGit **Pull** |
| SICF node still manual | SICF node still manual (one-time, Step 2) |

This is the install-experience parity goal flagged as **Gap C** in
`eclipse/MISSING_FOR_ABAP_CLEANER_PARITY.md`. **Gap closed.**

---

## Caveats and known limits

- **First-time install on a brand-new SAP system**: abapGit's table importer
  must be able to create `ZGCTS_DEP_HISTORY` from `zgcts_dep_history.tabl.xml`.
  If the import fails (older abapGit versions or unusual technical settings),
  fall back to creating the table manually in `SE11` from the field list in
  the XML, then pull again — the existing table will be reconciled, not
  overwritten.
- **`MANDT` field role**: included in the key for transparent table
  consistency. If your customer convention disallows `MANDT` on Z-tables,
  adjust the `.tabl.xml` and the `INSERT zgcts_dep_history` calls in
  `zcl_gcts_tr_analyzer.clas.abap` (`persist_result`).
- **abapGit version**: any release from 2023 onward handles the layout used
  here. Older versions (< 1.121) may flag the `<EXCLASS>1</EXCLASS>` element
  as unknown — upgrade abapGit, or manually remove that line.
- **Private repos**: abapGit prompts for a personal access token (GitHub) or
  username + password (most other Git servers).