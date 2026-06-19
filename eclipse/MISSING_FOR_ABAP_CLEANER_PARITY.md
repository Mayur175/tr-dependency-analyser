# What Is Missing for `abap-cleaner`-Style "Import & Use" Experience

This document is a Senior SAP Technical Architect review of the current Eclipse
plugin against the user goal:

> "Similar to abap-cleaner, I want my TR analyse tool to import in the system
>  and analyse the TR/task dependency in SAP system."

It lists **only verifiable, evidence-backed gaps** — no speculation about
internal SAP APIs.

---

## 1. Architectural Constraint — Non-Negotiable

`abap-cleaner` works **purely on the open editor document** (text-only). It
does NOT call the SAP system. The TR Dependency Analyzer **must** call the SAP
system, because the analytical primitives it needs (`xco_cp_cts`, `xco_cp_oo`,
`xco_cp_abap_dictionary`) exist only in ABAP, not in Java.

Therefore the **install-and-use** experience can be `abap-cleaner`-like, but
the runtime architecture cannot. Two halves must be installed:

1. The Eclipse plugin (this folder).
2. The ABAP backend (`TR dependency/abap/`) — class + ICF handler + table.

This split is **explicitly documented** in `TR_Dependency_Analyzer_Plan.md`
section 2 and is correct.

---

## 2. Status of the Eclipse Plugin

| Item | Status | Notes |
|------|--------|-------|
| Bundle manifest, plugin.xml, build.properties | ✅ Done | Cleaned up in last review |
| Command + handler + menus + key binding | ✅ Done | Right-click, top menu, toolbar, Ctrl+Alt+G |
| HTTP client + JSON parser + result model | ✅ Done | Pure Java, no external libs |
| `DependencyResultView` (TreeViewer) | ✅ Done | Header, cluster tree, pull order, CSV export |
| `AnalyzerPreferencePage` (URL/user/pwd/timeout) | ✅ Done | Uses Eclipse Secure Storage for password |
| TR detection from selection | ✅ Done | `IAdaptable` reflective probe + regex fallback |
| Eclipse Job for background HTTP call | ✅ Done | Integrates with the Progress view |
| Tycho build (parent + plugin + feature + updatesite) | ✅ Done | `mvn clean package` produces a P2 site |
| `.project` / `.classpath` with PDE natures | ✅ Done | Verified in last review |

---

## 3. Verified Gaps Toward `abap-cleaner`-Style UX

### Gap A — No automatic ADT destination/session reuse

**Current behaviour**: the user enters URL, username, password in
`Window → Preferences → gCTS Tools` before first use.

**`abap-cleaner`-style behaviour**: the plugin would automatically use the
**already-authenticated ADT project** the user is connected to. The user opens
ADT, selects an ABAP project, right-clicks a TR, and the plugin uses that
project's session — no separate login.

**Evidence**: `abap-cleaner` source
(<https://github.com/SAP/abap-cleaner/tree/main/com.sap.adt.abapcleaner.eclipse>)
shows it is a **pure offline plugin** — it never hits the SAP system. So we
cannot literally copy its mechanism. But we can copy the *experience* by
re-using the ADT REST framework that ADT itself uses internally.

**What an honest implementation looks like**:

| Approach | Verified? | Risk |
|----------|-----------|------|
| **(a)** Use `com.sap.adt.tools.core.project.IAbapProject` + `IAdtRestResourceFactory` | Plugin classes exist in every ADT install, but **SAP does not formally publish them as a stable third-party API**. They are marked `@noreference` in some MANIFEST entries. | Code may break across ADT versions. |
| **(b)** Use the Eclipse `org.eclipse.core.net` proxy + the user-entered URL/password (current approach) | Fully public APIs (`java.net.http.HttpClient`, `org.eclipse.equinox.security`) | None |
| **(c)** Reflection probe for `IAbapProject`, fall back to (b) | Same uncertainty as (a), but isolated behind try/catch | Acceptable |

**Recommendation**: keep (b) as the **default** (it works on every Eclipse +
ADT install today, no internal SAP dependency). Optionally add (c) later
behind a feature flag, but only after empirically verifying the ADT classes on
the target ADT version. **Do not invent method signatures.**

---

### Gap B — No ABAP project picker

**Current behaviour**: when the developer has 2+ ABAP projects open, the
plugin has no way to know which one's URL to use. (It uses the single URL
configured in preferences.)

**Fix**: when preferences are empty, open a Selection Dialog populated from
ADT's project list using `org.eclipse.core.resources.IWorkspaceRoot.getProjects()`
filtered by ABAP project nature
(`com.sap.adt.tools.abapsource.abapNature` — verified via ADT plugin.xml in
public ADT distribution).

**Status**: not implemented. Workaround: user enters URL in preferences.

---

### Gap C — ABAP backend is not packaged as an abapGit repository

**Current state**: The `abap/` folder contains raw `.clas.abap` source files.
Installing the backend on the SAP system requires the developer to manually
paste each file into ADT, create the SICF node, etc. (see `SICF_SETUP.md`).

**`abap-cleaner`-equivalent** (for the backend half): make the `abap/` folder
a valid **abapGit** repository so the developer can clone it onto the SAP
system in one step:

```
abap/
  .abapgit.xml                          ← repo descriptor (folder logic, encoding)
  src/
    $tmp.devc.xml                        ← package definition
    zcl_gcts_tr_analyzer.clas.abap
    zcl_gcts_tr_analyzer.clas.xml        ← object metadata (description, ABAP class header)
    zcl_gcts_tr_analyzer.clas.locals_def.abap
    zgcts_analyze_handler.clas.abap
    zgcts_analyze_handler.clas.xml
    zgcts_hist.tabl.xml           ← table object metadata (DD02V/DD09L)
    ...
```

**Verified format**: <https://docs.abapgit.org/ref-format.html>

**Status**: not implemented. Right now the developer must:
1. Open each `.abap` file
2. Manually create the class in ADT
3. Paste the source
4. Repeat for every artefact
5. Manually configure SICF

After abapGit packaging, the developer instead runs in ADT:
**File → New → ABAP Repository → Clone … → URL of this repo** → done.

---

### Gap D — Update site needs a versioned `index.html` landing page

**Current state**: the user installs by adding the P2 update-site URL.
`abap-cleaner` additionally hosts a friendly landing page at
<https://sap.github.io/abap-cleaner/updatesite/> with install instructions.

**Status**: an `index.html` is mentioned in the release guide
(`TR_Dependency_Analyzer_Plan.md` section 12 step 4) but is generated only at
release time. There is no static `index.html` checked into the repository.

This is **cosmetic** — the plugin installs fine without it.

---

### Gap E — No bundled icon

`plugin.xml` references `icons/dependency.png` but the file is absent (only
the `README.md` placeholder is there).

**Impact**: Eclipse renders a default missing-icon glyph. Plugin still works.

**Fix**: drop a 16×16 PNG at `icons/dependency.png`. License must be
compatible (CC0 / Apache / EPL).

---

## 4. What the User Actually Needs Next

In order of impact:

1. **Package the ABAP backend as an abapGit repo** (Gap C). Single highest-
   impact change for "import in the system" — turns 5 manual paste steps into
   one clone.
2. **Verify the ABAP source files** (`abap/zcl_gcts_tr_analyzer/*.abap` etc.)
   compile cleanly on the target SAP system. They were authored from the
   plan, not test-driven on a real BTP/S4 system. Specific risks per the
   plan's own ABAP conventions section: domain-typed `WHERE` clauses, inline
   `INTO @DATA(...)` only valid for `SELECT SINGLE`, fallbacks for missing
   `DDLDEPENDENCY` / `DDLSOURCE` tables on older releases.
3. **Provide the icon** (Gap E).
4. **(Optional)** ADT project picker (Gap B) — quality-of-life only when the
   user has multiple projects open.
5. **(Optional)** ADT session reuse via reflection (Gap A option (c)) — only
   after verification on a real ADT installation; do **not** add code that
   imports unverified `com.sap.adt.tools.core.*` classes.

---

## 5. What Was *Not* Changed in This Round, and Why

The user's task was to "**correct the code in `TR dependency/eclipse/`**".
Specifically:

- ABAP files in `TR dependency/abap/` were **not modified**. They are out of
  scope and contain content that should only be changed against a live ABAP
  system (XCO API surface differs between cloud release dates).
- Marketplace / GitHub-Pages release pipeline (`marketplace/`, `dist/`) was
  **not modified**. The previous review left them functional.
- No undocumented SAP-internal Java classes were added. Per the user's
  instruction *"without valid proof do not use any method or attribute"*, the
  ADT session-reuse path was deliberately left for a follow-up that can be
  validated on a live ADT install.

---

## 6. How to Install Today (after this review)

### Eclipse plugin

```
1. cd "TR dependency/eclipse"
2. JAVA_HOME=<JDK 21+> mvn clean package -DskipTests
3. Output: com.gmw.gcts.analyzer.updatesite/target/repository/
4. In Eclipse:  Help → Install New Software → Add → Archive → that folder
5. Restart Eclipse
6. Window → Preferences → gCTS Tools → enter SAP URL, user, password → Test Connection
7. In Transport Organizer: right-click TR → "Analyse gCTS Dependencies…"
```

### ABAP backend

```
1. ADT → connect to the target SAP system
2. New → ABAP Class → ZCL_GCTS_TR_ANALYZER → paste contents of the matching .abap file → activate
3. Repeat for ZGCTS_ANALYZE_HANDLER, ZCL_GCTS_DEP_ATC_CHECK, table ZGCTS_HIST
4. SICF: create node /sap/bc/zgcts/analyze, handler ZGCTS_ANALYZE_HANDLER, activate
5. Verify with curl  (see TR dependency/abap/docs/SICF_SETUP.md)
```

This is the honest, working install path. Steps 2–4 of the ABAP side are the
ones that abapGit packaging (Gap C) would collapse into a single clone.