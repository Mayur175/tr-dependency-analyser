# TR Analyser

Eclipse ADT plugin + ABAP backend that detects cross-task **and cross-TR**
object dependencies in SAP transport requests, and recommends a safe release /
pull order to prevent activation failures in QA.

Works on:

- S/4HANA Public Cloud + gCTS (task-based release)
- S/4HANA Private Cloud / on-prem (classic CTS, multi-TR coordination)

---

## Install — Eclipse plugin

Two methods. Pick one.

### Method A — Local ZIP (recommended)

1. Build the update site once (or download the published `dist/gcts-analyzer-updatesite-*.zip`):
   ```bash
   cd "TR dependency/eclipse"
   mvn clean package -DskipTests
   ```
   Output: `com.gmw.gcts.analyzer.updatesite/target/repository/`
2. In Eclipse: **Help → Install New Software → Add → Archive…**
3. Browse to the `repository/` folder (or the ZIP).
4. Tick **TR Analyser for ADT** → Next → Next → Finish → restart Eclipse.

### Method B — Dropins JAR

1. After `mvn package`, copy `com.gmw.gcts.analyzer/target/com.gmw.gcts.analyzer-*.jar`
   into your Eclipse `dropins/` folder.
2. Restart Eclipse with `eclipse -clean` (once).

> **Why no public update-site URL?**  The plan once advertised
> `https://mayur175.github.io/tr-analyser/updatesite`. SAP enterprise GitHub
> Pages requires authentication, which Eclipse's P2 client cannot follow, so
> that URL is **not** usable. Use Method A or B above.

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