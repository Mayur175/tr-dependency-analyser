# TR Analyser

Eclipse ADT plugin + ABAP backend that detects cross-task **and cross-TR**
object dependencies in SAP transport requests, and recommends a safe release /
pull order to prevent activation failures in QA.

Works on:

- S/4HANA Public Cloud + gCTS (task-based release)
- S/4HANA Private Cloud / on-prem (classic CTS, multi-TR coordination)

---

## Install — Eclipse plugin

Three install methods. Method 1 is the easiest; pick another if your
environment cannot reach the public internet.

| # | Method | Best for | Requires |
|---|--------|----------|----------|
| 1 | **Update-site URL** (recommended) | Most users | Open internet access from Eclipse |
| 2 | **Local ZIP / archive** | Air-gapped or locked-down environments | A built ZIP or `repository/` folder |
| 3 | **Dropins JAR** | Quick smoke test on one machine | Single plugin JAR |

### Method 1 — Update-site URL (recommended, abap-cleaner-style)

In Eclipse / ADT:

1. **Help → Install New Software…**
2. Click **Add…**
3. **Name:** `TR Dependency Analyser`
   **Location:** `https://mayur175.github.io/tr-dependency-analyser/`
4. Tick **TR Analyser for ADT** in the list → **Next** → **Next** →
   accept licence → **Finish** → restart Eclipse.

That's it. No manual download, no ZIP juggling, exactly the same flow as
the SAP `abap-cleaner` plugin.

> The URL is a **public-GitHub-Pages** site (`github.io`), not the
> SAP-internal one. Anyone on the open internet can install from it;
> Eclipse's P2 client follows the redirect cleanly with no authentication
> required. Future versions appear automatically when you do
> *Help → Check for Updates*.

### Method 2 — Local ZIP / archive (offline)

Use this when your Eclipse cannot reach `github.io` once installed
(corporate proxy on the developer's IDE machine, etc.). The download
itself comes from the same GitHub Pages site.

1. Download the update-site ZIP:
   <https://mayur175.github.io/tr-dependency-analyser/dist/com.gmw.gcts.analyzer.updatesite-1.0.0.zip>
   (49 KB)

   Or build it yourself from source:
   ```bash
   cd "TR dependency/eclipse"
   mvn clean package -DskipTests
   ```
   Built ZIP: `com.gmw.gcts.analyzer.updatesite/target/com.gmw.gcts.analyzer.updatesite-*.zip`.
2. In Eclipse: **Help → Install New Software → Add → Archive…**
3. Browse to the ZIP **or** the unpacked `repository/` folder.
4. Tick **TR Analyser for ADT** → **Next** → **Next** → **Finish** →
   restart Eclipse.

### Method 3 — Dropins JAR (quickest, no UI dialogs)

Use this when you just want to drop one file into Eclipse and restart.

1. Download the plugin JAR:
   <https://mayur175.github.io/tr-dependency-analyser/dist/com.gmw.gcts.analyzer-1.0.0.jar>
   (42 KB)

   Or build it yourself: `mvn package` (as in Method 2), then locate
   `eclipse/com.gmw.gcts.analyzer/target/com.gmw.gcts.analyzer-*.jar`.
2. Copy that single JAR into your Eclipse installation's `dropins/`
   folder. Locations:
   - **macOS:** `/Applications/Eclipse.app/Contents/Eclipse/dropins/`
   - **Windows:** `C:\eclipse\dropins\` (wherever you installed Eclipse)
   - **Linux:** `~/eclipse/dropins/` (or wherever the install lives)
3. Restart Eclipse **once** with the `-clean` flag so it re-scans dropins:
   ```bash
   eclipse -clean
   ```
   After that one restart, normal Eclipse launches will pick the plugin up.

> Methods 2 and 3 do **not** auto-update. To upgrade, repeat the steps
> with the new ZIP / JAR. Use Method 1 if you want automatic updates.

---

## Install — ABAP backend

The Eclipse plugin calls an ICF endpoint on your SAP system. **No copy-paste
required** — the backend is packaged as an [abapGit](https://docs.abapgit.org)
repository.

### One-click install via abapGit (recommended)

Prerequisite: abapGit installed on your SAP system, see
<https://docs.abapgit.org/guide-install.html>.

1. In your SAP system, run report `ZABAPGIT` (transaction SE38) and choose
   **+ Online**.
2. Git URL: this repository's HTTPS URL. Sub-folder: `/TR dependency/abap/`
   (the `.abapgit.xml` lives here).
3. Pick a target customer package (e.g. `ZGCTS`) and a transport.
4. **Pull** → all four classes + the `ZGCTS_DEP_HISTORY` table import in one
   step. Activate (Ctrl+F3).
5. Configure the ICF service node `/sap/bc/zgcts/analyze` once — see
   `TR dependency/abap/docs/SICF_SETUP.md` (this part is not in abapGit's
   scope, SICF nodes live outside the workbench).

Full step-by-step (with screenshots / curl tests / hand-authored XML
templates) is in `TR dependency/abap/INSTALL_VIA_ABAPGIT.md`.

### Manual fallback

If your SAP system has no abapGit, paste each `.abap` file from
`TR dependency/abap/src/` into ADT (`SE80` / `New ABAP Class`), create the
table from the field list in `zgcts_dep_history.tabl.xml`, then configure
SICF as described in `docs/SICF_SETUP.md`.

---

## Configure

In Eclipse: **Window → Preferences → TR Analyser**

| Field | Value |
|-------|-------|
| SAP System URL | `https://your-system.example.com:44300` |
| Username       | I-number / SAP user |
| Password       | stored in Eclipse Secure Storage |
| Timeout (s)    | 30 |

Click **Test Connection** to verify.

The user must have authorisation object `S_TRANSPRT` (TTYPE=`CUST`,
ACTVT=`03 Display`) — the ICF handler enforces this.

---

## Use

### One TR

- Right-click a TR in the Transport Organizer → **TR Analyser…**
- Or **Ctrl+Alt+G** (macOS: **Cmd+Alt+G**) anywhere in Eclipse.
- Or top menu **TR Analyser → TR Analyser…**

The dialog accepts a single id (e.g. `GMWK900691`).

### Multiple TRs / tasks (cross-TR analysis)

In the same dialog, enter a comma-separated list:

```
DEVK900042,DEVK900043
GMWK900691,GMWK900692,DEVK900050
```

The backend resolves each id:
- If it is a TR (`E070-STRKORR` matches existing rows) → all child tasks are scanned.
- If it is a bare task → just that task.

The result view shows:
- `[CRITICAL]` clusters (same object owned by 2+ tasks),
- `[HIGH]` clusters (activation dependencies — `IMPLEMENTS`, `INHERITS`, `CALLS`),
- `[MEDIUM]` clusters (type references),
- `[OK]` independent tasks,
- A numbered **Recommended Pull / Release Order**.

---

## What's analysed

| Object type | Source | Risk produced |
|-------------|--------|---------------|
| CLAS — class | `SEOMETAREL` (RELTYPE EX/EI) | HIGH (INHERITS, IMPLEMENTS) |
| INTF — interface | `SEOMETAREL` (RELTYPE EI) | HIGH (IMPLEMENTS) |
| TABL — table | `DD03L` rollname | MEDIUM (TYPE_REF) |
| DTEL — data element | `DD04L` domname | MEDIUM (TYPE_REF) |
| FUGR — function group | `TFDIR` pname | MEDIUM (CALLS) |
| Same object in two tasks | `E071` cross-task scan | **CRITICAL** (CONFLICT) |
| DDLS / DDLX / BDEF | not yet implemented | — |

Implementation uses **classic CTS / DDIC tables** (`E070`, `E071`, `SEOMETAREL`,
`DD03L`, `DD04L`, `TFDIR`) — present on **every** SAP NetWeaver / S/4HANA
release, both on-prem and Public Cloud. Where XCO is available it can be added
later as a preferred path; the current implementation does not depend on XCO.

---

## Build

```bash
cd "TR dependency/eclipse"
mvn clean package -DskipTests
```

Output: `com.gmw.gcts.analyzer.updatesite/target/repository/`

---

## Project documents

- `SOLUTION_ARCHITECTURE.md` — strategic plan vs. the user's two daily problems
- `GAPS_IN_CURRENT_DESIGN.md` — code-level defect list (73 gaps, 10 prioritised)
- `eclipse/MISSING_FOR_ABAP_CLEANER_PARITY.md` — install-experience parity gaps
- `TR_Dependency_Analyzer_Plan.md` — original phased plan (some content superseded)
- `abap/docs/SICF_SETUP.md` — SICF activation + curl test guide