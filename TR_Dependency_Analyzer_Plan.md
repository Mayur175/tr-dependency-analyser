# TR Task Dependency Analyzer — Architecture & Implementation Plan

---

## 1. Problem Statement

In SAP Public Cloud (S/4HANA Cloud / BTP ABAP Environment) using **gCTS with task-based commits**, each developer's changes are isolated in a **Task** within a Transport Request (TR). Multiple tasks in the same TR can contain the **same object** or **objects that depend on each other** (e.g., a class in Task A implements an interface in Task B).

When a developer pulls only their own task, it may fail to activate because a dependent object is locked in a different task that hasn't been pulled yet.

**SAP provides no native tool to detect this.** SE03 only checks conflicts one TR at a time, with no dependency graph or safe pull-order recommendation.

---

## 2. Architectural Decision

### The Core Constraint That Drives Everything

This tool is fundamentally different from tools like **ABAP Cleaner**. ABAP Cleaner works purely on the open editor document with no backend calls. This analyzer **must** query the SAP system — XCO APIs (`xco_cp_cts`, `xco_cp_oo`, `xco_cp_abap_dictionary`) are cloud-only ABAP APIs with no Eclipse/Java equivalent. The SAP backend must do the analysis.

This single constraint shapes every distribution and integration decision.

### Three Options Evaluated

| | Option 1 | Option 2 ✅ | Option 3 |
|---|---|---|---|
| **Description** | Keep clipboard+F9, upgrade distribution to P2 | Replace clipboard+F9 with ICF REST call, P2 distribution | Full Marketplace: Option 2 + Zest graph view + Maven Tycho CI |
| **User friction** | 3 manual steps each run | **1 right-click → results appear** | 1 right-click → results appear |
| **Robustness** | Fragile (relies on clipboard state, F9 command ID) | **Robust (HTTP call, direct response)** | Robust |
| **Output** | ADT Console (text only) | Dedicated Eclipse View (structured) | Zest visual graph view |
| **Distribution** | P2 update site | P2 update site | Eclipse Marketplace |
| **ABAP deployment** | ABAP class only | ABAP class + ICF HTTP handler | ABAP class + ICF HTTP handler |
| **Effort** | 2–3 days | **1–2 weeks** | 3–4 weeks |

### Decision: **Option 2 as implementation target, Option 3 as north star**

**Why Option 1 was rejected:** Upgrading distribution while keeping the clipboard+F9 bridge solves the installation problem but leaves the core fragility. Every daily user on a team will hit the clipboard state timing issue or F9 command ID mismatch. The mechanism is informal and does not scale.

**Why Option 2 is the right step:** Replacing clipboard+F9 with a direct HTTP call to a custom ICF service eliminates all trigger fragility. The plugin calls the SAP system, receives structured JSON, renders it in a dedicated view. This is identical in quality to how ADT itself works internally.

**Why Option 3 is the north star:** Adding a Maven Tycho build + Zest graph view + Eclipse Marketplace listing takes the tool from "team utility" to "openly distributable". This is the ABAP Cleaner distribution model applied to a dependency analysis tool.

---

## 3. Target Architecture

### Current State (Option 1 baseline — clipboard+F9)

```
┌────────────────────────────────────┐
│         Eclipse IDE (ADT)          │
│                                    │
│  Right-click TR                    │
│       └─► AnalyzeTRHandler.java    │
│             ├─ opens ABAP class    │
│             ├─ copies snippet to   │
│             │  clipboard           │  ← FRAGILE: depends on timing,
│             └─ triggers F9         │    F9 command ID, editor focus
└────────────────────────────────────┘
                    │ ADT F9 run
                    ▼
┌────────────────────────────────────┐
│      SAP System (ABAP Backend)     │
│   ZCL_GCTS_TR_ANALYZER             │
│   (4-stage XCO pipeline)           │
│   Output → ADT Console (text)      │  ← No structure, lost on next run
└────────────────────────────────────┘
```

### Target Architecture (Option 2 — ICF REST)

```
┌──────────────────────────────────────────────────────────┐
│                    Eclipse IDE (ADT)                     │
│                                                          │
│  Transport Organizer View                                │
│       └─ [Right-click TR] ──► "Analyse Dependencies…"   │
│                                    │                     │
│                    AnalyzeTRHandler.java                 │
│                    (detects TR via IAdaptable or regex)  │
│                                    │                     │
│                    HTTP GET /sap/bc/zgcts/analyze?tr=…   │
│                    (authenticated via ADT session)        │
│                                    │                     │
│                    ◄── JSON response ──────────────      │
│                                    │                     │
│                    DependencyResultView.java             │
│                    (Eclipse View — structured table      │
│                     with cluster groups + pull order)    │
└──────────────────────────────────────────────────────────┘
                            │
                            │ HTTP over existing ADT connection
                            ▼
┌──────────────────────────────────────────────────────────┐
│              SAP System (ABAP Backend)                   │
│                                                          │
│   ICF Handler: ZGCTS_ANALYZE_HANDLER                    │
│   Registered at: /sap/bc/zgcts/analyze                  │
│   ┌────────────────────────────────────────────────┐    │
│   │  ZCL_GCTS_TR_ANALYZER (4-stage XCO pipeline)  │    │
│   │                                                │    │
│   │  Stage 1: Task Inventory (xco_cp_cts)         │    │
│   │  Stage 2: Dependency Extraction (XCO per type)│    │
│   │  Stage 3: Cluster Detection (Union-Find)      │    │
│   │  Stage 4: Pull-Order (Topological sort)       │    │
│   └────────────────────────────────────────────────┘    │
│                    │                                     │
│                    └─► JSON response                     │
│    { "tr": "GMWK900691", "clusters": [...],             │
│      "pullOrder": [...], "edges": [...] }               │
└──────────────────────────────────────────────────────────┘
```

### Option 3 North Star — Full Marketplace Distribution

```
com.gmw.gcts.analyzer/            ← Core plugin (HTTP client + View)
com.gmw.gcts.analyzer.feature/    ← Feature.xml groups all plugins
com.gmw.gcts.analyzer.updatesite/ ← P2 repo: content.xml + artifacts.xml
pom.xml                            ← Maven Tycho 5.x root build

GitHub Actions:  tag → mvn package → publish P2 to GitHub Pages
Install URL:     Help → Install New Software → https://gmw.github.io/gcts-analyzer/updatesite
```

---

## 4. ABAP Backend

### 4.1 — XCO Analysis Pipeline (ZCL_GCTS_TR_ANALYZER)

The four-stage pipeline is the analytical core. It remains unchanged regardless of which trigger mechanism is used (F9 or ICF). The only change in Option 2 is that results are serialised to JSON instead of written to `cl_demo_output`.

#### Stage 1 — Task Inventory

**Goal:** For a given TR number, collect all tasks and their objects.

**API:** `xco_cp_cts` — Released Cloud API, safe for S/4HANA Cloud and BTP ABAP.

```abap
DATA(lo_tr)    = xco_cp_cts=>transports->for_transport_request( iv_tr ).
DATA(lt_tasks) = lo_tr->tasks->all( ).

LOOP AT lt_tasks INTO DATA(lo_task).
  DATA(lt_objects) = lo_task->objects->all( ).
  LOOP AT lt_objects INTO DATA(lo_obj).
    " lo_obj->object_key yields: pgmid, type (CLAS/INTF/TABL...), name
  ENDLOOP.
ENDLOOP.
```

**Output table (mt_objects):**

| Field    | Type   | Example    |
|----------|--------|------------|
| task_id  | CHAR20 | GMWK900692 |
| obj_type | CHAR4  | CLAS       |
| obj_name | CHAR40 | ZCL_FOO    |

---

#### Stage 2 — Dependency Extraction

For each object in `mt_objects`, the appropriate extractor is called based on `obj_type`:

##### CLAS (ABAP Class)
```abap
DATA(lo_class) = xco_cp_oo=>class( obj_name ).
lo_class->content( )->get_super_class( )->name          " INHERITS
lo_class->content( )->get_implemented_interfaces( )     " IMPLEMENTS
```

| Dependency Kind | Source | Target    | Risk |
|-----------------|--------|-----------|------|
| IMPLEMENTS      | Class  | Interface | HIGH |
| INHERITS        | Class  | Class     | HIGH |

##### INTF (ABAP Interface)
```abap
DATA(lo_intf) = xco_cp_oo=>interface( obj_name ).
lo_intf->content( )->get_implemented_interfaces( )      " parent interfaces
```

| Dependency Kind | Source    | Target    | Risk |
|-----------------|-----------|-----------|------|
| IMPLEMENTS      | Interface | Interface | HIGH |

##### TABL (Database Table)
```abap
DATA(lo_table) = xco_cp_abap_dictionary=>database_table( obj_name ).
lo_table->fields->all( )->content( )->get_data_element( )->name  " column types
```

| Dependency Kind | Source | Target       | Risk   |
|-----------------|--------|--------------|--------|
| TYPE_REF        | Table  | Data Element | MEDIUM |

##### DTEL (Data Element)
```abap
DATA(lo_dtel) = xco_cp_abap_dictionary=>data_element( obj_name ).
lo_dtel->content( )->get_domain( )->name                " domain reference
```

| Dependency Kind | Source       | Target | Risk   |
|-----------------|--------------|--------|--------|
| TYPE_REF        | Data Element | Domain | MEDIUM |

##### DDLS (CDS View Entity)
```abap
DATA(lo_cds) = xco_cp_cds=>view_entity( obj_name ).
lo_cds->content( )->get_data_sources( )                 " tables and other CDS views
```

| Dependency Kind | Source   | Target      | Risk   |
|-----------------|----------|-------------|--------|
| USES            | CDS View | Table / CDS | MEDIUM |

##### Filtering Rule — Cross-Task Only
```abap
" Only record an edge if the target object lives in a DIFFERENT task
IF lv_target_task IS NOT INITIAL AND lv_target_task <> lv_source_task.
  " record edge
ENDIF.
```

- Same-task dependencies → ignored (no pull risk)
- Objects outside the TR entirely → ignored (external dependencies)

**Output table (mt_deps):**

| Field         | Example      |
|---------------|--------------|
| source_task   | GMWK900692   |
| source_object | CLAS/ZCL_FOO |
| target_task   | GMWK900693   |
| target_object | INTF/ZIF_FOO |
| kind          | IMPLEMENTS   |
| detail        | free-text    |

---

#### Stage 3 — Cluster Detection (Union-Find)

**Goal:** Group tasks that MUST be pulled together into atomic clusters.

**Algorithm:**
1. Initialise: each task is its own root (`parent[task] = task`)
2. For each dependency edge `(source_task → target_task)`: union the two roots
3. Group all tasks by their root → each group is a **cluster**

**Risk Classification per Cluster:**
- `HIGH` — contains at least one IMPLEMENTS or INHERITS edge
- `MEDIUM` — contains only TYPE_REF or USES edges
- `NONE` — independent task, no cross-task dependencies

**Example:**
```
Input tasks:   GMWK900692, GMWK900693, GMWK900694, GMWK900695
Edges:         692 → 693 (IMPLEMENTS)
               693 → 694 (TYPE_REF)

Clusters:
  Cluster 1 [HIGH]:    692, 693, 694
  Independent [NONE]:  695
```

---

#### Stage 4 — Pull-Order Recommendation (Topological Sort)

**Goal:** Tell the developer exactly which tasks to pull, in which order.

**Algorithm:**
1. Sort clusters: HIGH first, then MEDIUM, then NONE
2. Within a cluster: topological order (the depended-on task comes first)
3. Output numbered steps

**Console / View Output:**
```
=================================================================
  gCTS Task Dependency Analyzer — TR GMWK900691
  Tasks: 4   Objects: 12   Cross-task edges: 3
=================================================================

[HIGH RISK]  Cluster — must pull together
  Tasks:  GMWK900692, GMWK900693, GMWK900694
  Reason: ZCL_FOO (692) implements ZIF_FOO (693)  [IMPLEMENTS]
          ZTBL_BAR (693) uses ZDE_FOO (694)        [TYPE_REF]

[NONE]  Independent
  Task:   GMWK900695
  (no cross-task dependencies — safe to pull alone)

Recommended Pull Order:
  Step 1: Pull TOGETHER  →  GMWK900692 + GMWK900693 + GMWK900694
  Step 2: Pull alone     →  GMWK900695
=================================================================
```

---

### 4.2 — ICF HTTP Handler (ZGCTS_ANALYZE_HANDLER) — Option 2

The ICF handler wraps the XCO pipeline and returns structured JSON, eliminating the need for clipboard+F9. The Eclipse plugin calls this service over the authenticated ADT HTTP session.

**ICF Registration:**
- Service path: `/sap/bc/zgcts/analyze`
- Handler class: `ZGCTS_ANALYZE_HANDLER`
- Method: GET, parameter `tr` = TR number

```abap
CLASS zgcts_analyze_handler DEFINITION PUBLIC FINAL CREATE PUBLIC.
  PUBLIC SECTION.
    INTERFACES if_http_extension.
ENDCLASS.

CLASS zgcts_analyze_handler IMPLEMENTATION.
  METHOD if_http_extension~handle_request.
    DATA(lv_tr) = server->request->get_form_field( 'tr' ).

    " Validate TR format
    IF lv_tr IS INITIAL OR NOT matches( val = lv_tr
                                        regex = '[A-Z0-9]{3,4}K[0-9]{6}' ).
      server->response->set_status( code = 400 reason = 'Bad Request' ).
      server->response->set_cdata( '{"error":"invalid TR format"}' ).
      RETURN.
    ENDIF.

    " Run the 4-stage pipeline
    ZCL_GCTS_TR_ANALYZER=>GV_TR_ID = lv_tr.
    DATA(lo_analyzer) = NEW zcl_gcts_tr_analyzer( ).

    " Serialize results to JSON
    DATA(lv_json) = lo_analyzer->to_json( ).

    server->response->set_status( code = 200 reason = 'OK' ).
    server->response->set_header_field( name = 'Content-Type'
                                        value = 'application/json' ).
    server->response->set_cdata( lv_json ).
  ENDMETHOD.
ENDCLASS.
```

**ICF Activation steps** (transaction SICF):
1. Go to SICF → navigate to `/sap/bc/` → create new node `zgcts`
2. Under `zgcts`, create node `analyze`
3. Set handler class: `ZGCTS_ANALYZE_HANDLER`
4. Activate the service node
5. Assign to a role/user that ADT developers can authenticate with

---

### 4.3 — JSON Response Format

```json
{
  "tr": "GMWK900691",
  "summary": {
    "taskCount": 4,
    "objectCount": 12,
    "edgeCount": 3
  },
  "clusters": [
    {
      "risk": "HIGH",
      "tasks": ["GMWK900692", "GMWK900693", "GMWK900694"],
      "edges": [
        { "from": "CLAS/ZCL_FOO", "fromTask": "GMWK900692",
          "to": "INTF/ZIF_FOO",  "toTask":   "GMWK900693",
          "kind": "IMPLEMENTS",  "detail": "ZCL_FOO implements ZIF_FOO" },
        { "from": "TABL/ZTBL_BAR", "fromTask": "GMWK900693",
          "to": "DTEL/ZDE_FOO",   "toTask":   "GMWK900694",
          "kind": "TYPE_REF",     "detail": "ZTBL_BAR column uses ZDE_FOO" }
      ]
    },
    {
      "risk": "NONE",
      "tasks": ["GMWK900695"],
      "edges": []
    }
  ],
  "pullOrder": [
    { "step": 1, "action": "TOGETHER", "tasks": ["GMWK900692","GMWK900693","GMWK900694"] },
    { "step": 2, "action": "ALONE",    "tasks": ["GMWK900695"] }
  ]
}
```

---

## 5. Eclipse Plugin (com.gmw.gcts.analyzer)

### 5.1 — Project Structure

#### Current (Phase 1 — dropins)
```
com.gmw.gcts.analyzer/
├── .project                      ← PDE + Java natures
├── .classpath                    ← JRE 17 + PDE container
├── META-INF/MANIFEST.MF          ← OSGi bundle descriptor
├── plugin.xml                    ← 5 extension points
├── build.properties
├── icons/dependency.png
└── src/com/gmw/gcts/analyzer/
    ├── Activator.java
    └── handlers/
        └── AnalyzeTRHandler.java ← clipboard+F9 (Phase 1)
```

#### Target (Phase 2+3 — P2 update site, ABAP Cleaner model)
```
com.gmw.gcts.analyzer/                    ← Core plugin (HTTP client + View)
├── META-INF/MANIFEST.MF
├── plugin.xml                             ← adds View extension point
├── src/com/gmw/gcts/analyzer/
│   ├── Activator.java
│   ├── handlers/
│   │   └── AnalyzeTRHandler.java          ← replaced: HTTP call instead of clipboard
│   ├── client/
│   │   └── AnalyzerHttpClient.java        ← NEW: HTTP GET → JSON parse
│   ├── model/
│   │   └── AnalysisResult.java            ← NEW: JSON model (clusters, edges, pullOrder)
│   └── views/
│       └── DependencyResultView.java      ← NEW: Eclipse View rendering results

com.gmw.gcts.analyzer.feature/            ← NEW: Feature project
└── feature.xml                            ← groups the plugin for P2

com.gmw.gcts.analyzer.updatesite/         ← NEW: P2 repository
└── category.xml                           ← categories for Install New Software dialog

pom.xml (root)                             ← NEW: Maven Tycho 5.x multi-module build
```

---

### 5.2 — Eclipse Extension Points (plugin.xml)

#### Command + Handler + Menus + Keyboard (unchanged from Phase 1)

```xml
<!-- Command -->
<extension point="org.eclipse.ui.commands">
  <command id="com.gmw.gcts.analyzer.commands.analyzeTR"
           name="Analyse gCTS Dependencies…"/>
</extension>

<!-- Handler -->
<extension point="org.eclipse.ui.handlers">
  <handler commandId="com.gmw.gcts.analyzer.commands.analyzeTR"
           class="com.gmw.gcts.analyzer.handlers.AnalyzeTRHandler"/>
</extension>

<!-- Right-click context menu -->
<extension point="org.eclipse.ui.menus">
  <menuContribution allPopups="true"
    locationURI="popup:org.eclipse.ui.popup.any?after=additions">
    <command commandId="com.gmw.gcts.analyzer.commands.analyzeTR"
             label="Analyse Dependencies…" icon="icons/dependency.png">
      <visibleWhen checkEnabled="false">
        <with variable="selection"><count value="+"/></with>
      </visibleWhen>
    </command>
  </menuContribution>

  <!-- Top-level menu bar -->
  <menuContribution locationURI="menu:org.eclipse.ui.main.menu?after=additions">
    <menu id="com.gmw.gcts.analyzer.menu" label="gCTS Tools">
      <command commandId="com.gmw.gcts.analyzer.commands.analyzeTR"
               label="Analyse TR Dependencies…"/>
    </menu>
  </menuContribution>
</extension>

<!-- Keyboard shortcut: Ctrl+Alt+G / Cmd+Alt+G -->
<extension point="org.eclipse.ui.bindings">
  <key commandId="com.gmw.gcts.analyzer.commands.analyzeTR"
       sequence="M1+M3+G"
       schemeId="org.eclipse.ui.defaultAcceleratorConfiguration"/>
</extension>
```

#### NEW — Dependency Result View (Phase 2)

```xml
<extension point="org.eclipse.ui.views">
  <view id="com.gmw.gcts.analyzer.views.dependencyResult"
        name="gCTS Dependency Analysis"
        class="com.gmw.gcts.analyzer.views.DependencyResultView"
        category="com.sap.adt"
        icon="icons/dependency.png"
        restorable="true"/>
</extension>
```

---

### 5.3 — Handler Flow Comparison

#### Phase 1 (current — clipboard+F9)
```
Right-click TR → AnalyzeTRHandler
  ├─ detect TR via regex on selection.toString()
  ├─ show InputDialog (pre-filled)
  ├─ open ZCL_GCTS_TR_ANALYZER in ADT editor
  ├─ copy snippet to clipboard          ← fragile: timing-dependent
  └─ trigger F9 via IHandlerService     ← fragile: 3 command IDs to try
```

#### Phase 2 (target — ICF REST)
```
Right-click TR → AnalyzeTRHandler
  ├─ detect TR via IAdaptable (or regex fallback)
  ├─ show InputDialog (pre-filled)
  └─ AnalyzerHttpClient.analyze(tr)
       │  HTTP GET /sap/bc/zgcts/analyze?tr=GMWK900691
       │  using existing ADT authenticated session
       ▼
       JSON response → AnalysisResult model
       DependencyResultView.show(result)   ← Eclipse View, no manual steps
```

---

### 5.4 — HTTP Client (AnalyzerHttpClient.java) — Phase 2

```java
public class AnalyzerHttpClient {

    private final String systemBaseUrl;  // e.g. https://my-system.sap.com
    private final String authHeader;     // Basic or Bearer from ADT session

    public AnalysisResult analyze(String tr) throws IOException, InterruptedException {
        HttpClient client = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .build();

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(systemBaseUrl + "/sap/bc/zgcts/analyze?tr=" + tr))
                .header("Authorization", authHeader)
                .header("Accept", "application/json")
                .GET()
                .build();

        HttpResponse<String> response = client.send(request,
                HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() != 200) {
            throw new IOException("SAP returned HTTP " + response.statusCode()
                    + ": " + response.body());
        }

        return AnalysisResult.fromJson(response.body());
    }
}
```

> **ADT Session Reuse:** In Phase 3, replace raw `authHeader` with
> `IAdtRestResourceFactory` from `com.sap.adt.tools.core` — this reuses the
> existing authenticated ADT project connection automatically, with no
> credential management needed.

---

### 5.5 — Distribution: P2 Update Site (ABAP Cleaner Model)

This is how ABAP Cleaner (`sap.github.io/abap-cleaner/updatesite`) distributes itself. We adopt the same pattern.

#### feature.xml
```xml
<feature id="com.gmw.gcts.analyzer.feature"
         version="1.0.0.qualifier"
         label="gCTS Task Dependency Analyzer"
         vendor="GMW"
         license-feature="org.eclipse.license">
  <description>
    Detects cross-task object dependencies in gCTS Transport Requests.
    Recommends safe pull order to prevent activation failures.
  </description>
  <plugin id="com.gmw.gcts.analyzer"
          version="1.0.0.qualifier"
          unpack="false"/>
</feature>
```

#### category.xml (updatesite)
```xml
<site>
  <feature url="features/com.gmw.gcts.analyzer.feature_1.0.0.jar"
           id="com.gmw.gcts.analyzer.feature"
           version="1.0.0.qualifier">
    <category name="gmw.gcts"/>
  </feature>
  <category-def name="gmw.gcts" label="gCTS Tools for ADT"/>
</site>
```

#### Maven Tycho root pom.xml (skeleton)
```xml
<project>
  <groupId>com.gmw.gcts</groupId>
  <artifactId>com.gmw.gcts.analyzer.parent</artifactId>
  <version>1.0.0-SNAPSHOT</version>
  <packaging>pom</packaging>

  <modules>
    <module>com.gmw.gcts.analyzer</module>
    <module>com.gmw.gcts.analyzer.feature</module>
    <module>com.gmw.gcts.analyzer.updatesite</module>
  </modules>

  <build>
    <plugins>
      <plugin>
        <groupId>org.eclipse.tycho</groupId>
        <artifactId>tycho-maven-plugin</artifactId>
        <version>5.0.0</version>
        <extensions>true</extensions>
      </plugin>
    </plugins>
  </build>
</project>
```

**Build + publish:**
```bash
mvn clean package
# Output: com.gmw.gcts.analyzer.updatesite/target/repository/
# Host on GitHub Pages → https://gmw.github.io/gcts-analyzer/updatesite
```

**Team installs with:**
```
Help → Install New Software → Add → https://gmw.github.io/gcts-analyzer/updatesite
```

---

## 6. Supported Object Types & Dependency Kinds

| Object Type  | ABAP Code | Dependencies Extracted     | Risk   | Status    |
|--------------|-----------|----------------------------|--------|-----------|
| Class        | CLAS      | Superclass, Interfaces     | HIGH   | Supported |
| Interface    | INTF      | Parent Interfaces          | HIGH   | Supported |
| Table        | TABL      | Data Elements (columns)    | MEDIUM | Supported |
| Data Element | DTEL      | Domain                     | MEDIUM | Supported |
| CDS View     | DDLS      | Tables, other CDS views    | MEDIUM | Supported |
| Metadata Ext | DDLX      | Base CDS view              | MEDIUM | Phase 2   |
| RAP Behavior | BDEF      | CDS View, other behaviors  | HIGH   | Phase 2   |
| Function Grp | FUGR      | Other function groups      | MEDIUM | Phase 2   |

### Not Yet Planned
| Object Type      | ABAP Code | Reason Not Yet Supported              |
|------------------|-----------|---------------------------------------|
| Include Program  | PROG/REPS | Requires parsing include hierarchy    |
| Type Pool        | TYPE      | Limited XCO API coverage              |
| Message Class    | MSAG      | No cross-task dependency risk         |
| Enhancement Impl | ENHO      | Enhancement framework complexity      |
| BAdI Definition  | ENHS      | Enhancement framework complexity      |

---

## 7. Known Gaps & Remediation Plan

### Gap 1 — Function Module Dependencies Not Extracted
- **Problem:** FUGR objects not yet supported
- **Impact:** A class calling a function module in another task is not flagged
- **Fix:** Add `deps_for_fugr` using `xco_cp_abap=>function_module( )->content( )`
- **Phase:** 2

### Gap 2 — Same-Object Conflict Not Detected
- **Problem:** If Task A and Task B both contain the same object (`ZCL_FOO`), no edge is recorded — but this is a **lock conflict**: one developer's changes overwrite the other's
- **Impact:** Silent data loss on import
- **Fix:** Add Stage 2b — scan `mt_objects` for duplicate `obj_name` across tasks, report as `CONFLICT` edges (severity: CRITICAL, above HIGH)
- **Phase:** 2

### Gap 3 — Clipboard+F9 Trigger Mechanism is Fragile *(being replaced)*
- **Problem:** `detectTrFromSelection()` scans `toString()` of the selection; F9 trigger tries 3 command IDs by trial and error
- **Impact:** TR detection fails silently; F9 may not fire in all Eclipse/ADT versions
- **Fix (Option 2):** Replace entirely with `AnalyzerHttpClient` calling ICF service; use `IAdaptable.getAdapter(ICtsTransportRequest.class)` for TR detection
- **Phase:** 2 (core fix), Phase 3 (IAdaptable refinement)

### Gap 4 — Text-Only Output
- **Problem:** Results are written to ADT Console as plain text
- **Impact:** Hard to navigate complex dependency chains across 10+ tasks
- **Fix:** `DependencyResultView` — a dedicated Eclipse View with collapsible cluster groups; Phase 3 adds a Zest graph renderer
- **Phase:** 2 (table view), 3 (Zest graph)

### Gap 5 — No Pre-Pull Hook
- **Problem:** Developer must manually trigger analysis; nothing prevents pulling without checking
- **Impact:** Silent failures still possible if developer forgets
- **Fix:** Register as a gCTS pre-pull event listener (if SAP exposes the extension point) or add an ATC check
- **Phase:** 4

### Gap 6 — No DDLX / RAP Behavior Support
- **Problem:** Metadata extensions (DDLX) and RAP behavior definitions (BDEF) not handled
- **Impact:** RAP-based apps with split CDS/behavior across tasks have undetected dependencies
- **Fix:** Add `deps_for_ddlx` and `deps_for_bdef` using XCO CDS APIs
- **Phase:** 2

### Gap 7 — External Dependencies Silently Ignored
- **Problem:** If an object depends on something outside the TR entirely, it is not reported
- **Impact:** Upgrade/compatibility checks miss these
- **Fix:** Optionally report external dependencies as `INFO` severity
- **Phase:** 4

### Gap 8 — No Persistence / History
- **Problem:** Analysis results are lost when the console clears or Eclipse restarts
- **Impact:** No audit trail before a pull
- **Fix:** Persist results to a custom ABAP database table; export as CSV/JSON from the Eclipse View
- **Phase:** 4

---

## 8. Implementation Roadmap

### Phase 1 — Foundation ✅ Complete
- [x] ABAP class `ZCL_GCTS_TR_ANALYZER` with 4-stage XCO pipeline
- [x] Eclipse plugin scaffold: `.project`, `.classpath`, `MANIFEST.MF`, `plugin.xml`, `build.properties`
- [x] `Activator.java`, `AnalyzeTRHandler.java` (clipboard+F9 approach)
- [x] Supported object types: CLAS, INTF, TABL, DTEL, DDLS
- [x] All files created under `gcts_task_dependency_analyzer/`

### Phase 2 — ICF REST + Structured Output ✅ Complete
Eliminated all clipboard+F9 fragility. Direct HTTP call, dedicated Eclipse View.

- [x] **ABAP:** `to_json()` method on `ZCL_GCTS_TR_ANALYZER` — full JSON serialisation
- [x] **ABAP:** `ZGCTS_ANALYZE_HANDLER` ICF handler at `/sap/bc/zgcts/analyze`
- [x] **ABAP:** `SICF_SETUP.md` — step-by-step SICF activation + curl test guide
- [x] **Java:** `AnalyzerHttpClient.java` — HTTP GET, Eclipse Secure Storage for password
- [x] **Java:** `AnalysisResult.java` — JSON model (Cluster, Edge, PullStep), hand-rolled parser
- [x] **Java:** `DependencyResultView.java` — Eclipse TreeViewer: clusters, edges, pull order
- [x] **Java:** `AnalyzerPreferencePage.java` — URL/user/password prefs, Test Connection button
- [x] **Java:** `AnalyzeTRHandler.java` — clipboard+F9 removed, opens View, background HTTP call
- [x] **Java:** `Activator.java` — seeds preference defaults
- [x] **Gap 2:** Stage 2b — same-object CONFLICT detection (CRITICAL severity)
- [x] **Gap 6:** `deps_for_ddlx`, `deps_for_bdef`, `deps_for_fugr` extractors added

### Phase 3 — P2 Distribution + Graph View ✅ Complete
ABAP Cleaner-grade distribution. Zest visual graph view.

- [x] `com.gmw.gcts.analyzer.feature/feature.xml` — OSGi feature grouping plugin
- [x] `com.gmw.gcts.analyzer.updatesite/category.xml` — P2 repository categories
- [x] Root `pom.xml` + 3 module `pom.xml` files — Maven Tycho 5.x multi-module build
- [x] `.github/workflows/release.yml` — tag → `mvn package` → P2 → GitHub Pages → GitHub Release
- [x] **Gap 3:** `TrDetector.java` — IAdaptable strategy with regex toString fallback
- [x] **Gap 1:** `deps_for_fugr` — function group dependency extraction (done in Phase 2)
- [x] **Gap 4 (advanced):** `DependencyGraphView.java` — Zest graph: task nodes coloured by risk, directed edges, tree layout, toolbar (fit / toggle direction)
- [x] `MANIFEST.MF` updated — added `org.eclipse.zest.core`, `zest.layouts`, `draw2d`
- [x] `plugin.xml` updated — registered `DependencyGraphView` as second Eclipse View
- [x] `AnalyzeTRHandler.java` updated — opens both views; graph view degrades gracefully if Zest not installed

> **Note on VS Code diagnostics:** The IDE may show "cannot resolve org.eclipse.*" errors.
> This is a known VS Code limitation with PDE classpath containers. All imports resolve
> correctly inside Eclipse IDE once the Target Platform is set (Section B of setup guide).

### Phase 4 — Enterprise Features ✅ Complete
- [x] **Gap 5:** `ZCL_GCTS_DEP_ATC_CHECK` — ATC check class raises findings (Priority 1/2/3) for CRITICAL/HIGH/MEDIUM dependencies; registers via SE92/ATC framework; uses `lcl_atc_json_reader` local class to parse pipeline JSON
- [x] **Gap 8 ABAP:** `ZGCTS_DEP_HISTORY` database table — stores one row per dependency edge per run; `persist_result()` method writes to it; `to_csv()` method for flat file export
- [x] **Gap 8 Java:** `ExportCsvAction.java` — toolbar button in `DependencyResultView`; opens Save dialog; writes RFC 4180 CSV; enabled only after a successful result
- [x] **Gap 7:** `gv_include_external = abap_true` flag on analyzer; `add_external_dep()` records INFO-level (`EXT_*` kind prefix) edges; ICF handler exposes `?external=true` parameter
- [x] **ICF handler updated:** `?format=csv`, `?persist=true`, `?external=true` query params
- [x] Eclipse Marketplace — `marketplace/marketplace.xml` + `MARKETPLACE_SUBMISSION.md` step-by-step guide

---

## 9. File Inventory

### Phase 1 scaffold (legacy — `gcts_task_dependency_analyzer/`)
Original clipboard+F9 prototype. Retained as reference; superseded by Phase 2+ files below.
```
gcts_task_dependency_analyzer/
├── .project / .classpath / build.properties / plugin.xml / META-INF/MANIFEST.MF
├── src/com/gmw/gcts/analyzer/
│   ├── Activator.java
│   └── handlers/AnalyzeTRHandler.java        ← clipboard+F9 (superseded)
└── gmw_abap/
    ├── zcl_gcts_tr_analyzer.clas.abap        ← initial pipeline (superseded)
    └── zcl_gcts_tr_analyzer.clas.locals_imp.abap
```

### Full implementation (`TR dependency/`) — all phases complete

```
TR dependency/
├── TR_Dependency_Analyzer_Plan.md
│
├── .github/workflows/
│   └── release.yml                                    ← tag → mvn → P2 → GitHub Pages + Release
│
├── abap/
│   ├── zcl_gcts_tr_analyzer/
│   │   ├── zcl_gcts_tr_analyzer.clas.abap            ← 4-stage pipeline + to_json, to_csv,
│   │   │                                                 persist_result, external deps, conflicts
│   │   └── zcl_gcts_tr_analyzer.clas.locals_def.abap ← lcl_string_util (Local Types tab in ADT)
│   │
│   ├── zgcts_analyze_handler/
│   │   └── zgcts_analyze_handler.clas.abap            ← ICF handler: ?format, ?persist, ?external
│   │
│   ├── zgcts_dep_history/
│   │   └── zgcts_dep_history.tabl.ddls                ← DB table DDL for analysis history
│   │
│   ├── zcl_gcts_dep_atc_check/
│   │   ├── zcl_gcts_dep_atc_check.clas.abap          ← ATC check: raises P1/P2/P3 findings
│   │   └── zcl_gcts_dep_atc_check.clas.locals_def.abap ← lcl_atc_json_reader (Local Types tab)
│   │
│   └── docs/
│       └── SICF_SETUP.md                              ← SICF activation + curl test + auth guide
│
├── eclipse/
│   ├── pom.xml                                        ← Maven Tycho 5.x root (3 modules)
│   │
│   ├── com.gmw.gcts.analyzer/
│   │   ├── .project / .classpath / build.properties
│   │   ├── META-INF/MANIFEST.MF                       ← Requires: zest.core, zest.layouts, draw2d
│   │   ├── plugin.xml                                 ← command, handler, 3 menus, 2 views, prefs
│   │   ├── pom.xml
│   │   └── src/com/gmw/gcts/analyzer/
│   │       ├── Activator.java
│   │       ├── actions/
│   │       │   └── ExportCsvAction.java               ← toolbar CSV export (RFC 4180)
│   │       ├── client/
│   │       │   └── AnalyzerHttpClient.java            ← HTTP GET, Eclipse Secure Storage
│   │       ├── handlers/
│   │       │   ├── AnalyzeTRHandler.java              ← opens both views, background thread
│   │       │   └── TrDetector.java                    ← IAdaptable + regex fallback
│   │       ├── model/
│   │       │   └── AnalysisResult.java                ← JSON model, hand-rolled parser
│   │       ├── preferences/
│   │       │   ├── AnalyzerPreferencePage.java        ← URL/user/password + Test Connection
│   │       │   └── PreferenceConstants.java
│   │       └── views/
│   │           ├── DependencyResultView.java          ← TreeViewer + CSV export toolbar button
│   │           └── DependencyGraphView.java           ← Zest graph, risk colours, fit/toggle
│   │
│   ├── com.gmw.gcts.analyzer.feature/
│   │   ├── feature.xml
│   │   └── pom.xml
│   │
│   └── com.gmw.gcts.analyzer.updatesite/
│       ├── category.xml
│       └── pom.xml
│
└── marketplace/
    ├── marketplace.xml                                ← Eclipse Marketplace listing descriptor
    └── MARKETPLACE_SUBMISSION.md                     ← step-by-step submission guide
```

**Build:** `cd eclipse && mvn clean package`
**Install URL:** `https://<org>.github.io/<repo>/updatesite`
**Marketplace:** `Help → Eclipse Marketplace` → search `gCTS`
    │
    └── com.gmw.gcts.analyzer.updatesite/
        ├── category.xml                       ← P2 categories: "gCTS Tools for ADT"
        └── pom.xml
```

**Build:** `cd eclipse && mvn clean package`
**Output:** `com.gmw.gcts.analyzer.updatesite/target/repository/`
**Install:** `Help → Install New Software → Add → https://<org>.github.io/<repo>/updatesite`



---

## 10. Eclipse Plugin Setup Guide

### Prerequisites

| Requirement | Details |
|-------------|---------|
| **Eclipse IDE** | **Eclipse IDE for RCP and RAP Developers** — includes PDE. Standard "Eclipse for Java" does not have plugin wizard support. |
| **SAP ADT** | Installed in Eclipse via `https://tools.hana.ondemand.com/latest`. The same Eclipse you connect to your SAP system with. |
| **Java JDK** | JDK 17 or later. Verify: `java -version` in terminal. |
| **SAP System** | BTP ABAP Environment or S/4HANA Cloud. XCO APIs are cloud-only — not available in ECC. |

---

### Section A — Import Plugin Project

1. **File → Import → General → Existing Projects into Workspace → Next**
2. Browse to `TR_Tool/gcts_task_dependency_analyzer/`
3. Tick `com.gmw.gcts.analyzer` → leave **Copy projects** unchecked → **Finish**
4. Verify the project icon shows a **plug symbol** in Project Explorer

If the plug symbol is missing:
- Right-click project → **Configure → Convert to Plug-in Project**
- If this option is absent, install PDE: **Help → Install New Software** → `https://download.eclipse.org/releases/latest` → **General Purpose Tools → Eclipse Plug-in Development Environment**

---

### Section B — Configure Target Platform (SAP ADT Bundles)

The plugin references `com.sap.adt.*` bundles. Eclipse must know where to find them.

1. **Help → About Eclipse IDE → Installation Details** — confirm `com.sap.adt` entries exist
2. If missing, install ADT: **Help → Install New Software → Add** → `https://tools.hana.ondemand.com/latest` → tick **ABAP Development Tools**
3. **Window → Preferences → Plug-in Development → Target Platform** → set **Running Platform** as active → **Apply and Close**

> Without step 3, the Java compiler reports unresolved imports even though ADT is installed.

---

### Section C — Add the Plugin Icon

1. Source a 16×16 PNG (any dependency/graph icon)
2. Place at: `gcts_task_dependency_analyzer/icons/dependency.png`
3. Right-click project → **Refresh (F5)**

---

### Section D — Build and Verify

1. **Project → Clean → Clean selected project**
2. **Window → Show View → Problems** — expected: zero errors

| Error | Fix |
|-------|-----|
| `HandlerUtil cannot be resolved` | Target Platform not set — redo Section B step 3 |
| `org.eclipse.ui.ide cannot be resolved` | Add `org.eclipse.ui.ide` to `Require-Bundle` in MANIFEST.MF |
| Plugin execution not covered | PDE project — not Maven, ignore this warning |

---

### Section E — Test in Development Mode (Fastest, No JAR Needed)

1. Right-click `plugin.xml` → **Run As → Eclipse Application**
2. A second Eclipse window opens with the plugin active
3. In that window: open ADT connection → Transport Organizer view
4. Right-click any TR → **"Analyse Dependencies…"** should appear
5. Test: **gCTS Tools** top menu → **Ctrl+Alt+G** keyboard shortcut

---

### Section F — Install Permanently

#### Option 1 — Dropins (Phase 1, quick)
1. Right-click `plugin.xml` → **Export → Plug-in Development → Deployable plug-ins and fragments**
2. Set destination directory → Finish → get `com.gmw.gcts.analyzer_1.0.0.<ts>.jar`
3. Copy JAR to Eclipse `dropins/` folder:
   - macOS: `/Applications/Eclipse.app/Contents/Eclipse/dropins/`
   - Windows: `C:\eclipse\dropins\`
4. Restart Eclipse: `eclipse -clean`

#### Option 2 — P2 Update Site (Phase 3, recommended for teams)
After the Maven Tycho build is set up:
```bash
mvn clean package
# generates: com.gmw.gcts.analyzer.updatesite/target/repository/
```
Host the `repository/` folder → team installs with:
```
Help → Install New Software → Add → https://your-host/gcts-analyzer/updatesite
```
This is the same mechanism used by ABAP Cleaner (`sap.github.io/abap-cleaner/updatesite`). Supports versioning, auto-updates, and clean uninstallation.

---

### Section G — ABAP Backend Setup

#### Step G1 — Create the ABAP Class
1. In ADT, connect to your BTP ABAP / S/4HANA Cloud system
2. Right-click your package → **New → ABAP Class**
3. Name: `ZCL_GCTS_TR_ANALYZER`, Description: `gCTS Task Dependency Analyzer`
4. Click **Finish** → paste content from `gmw_abap/zcl_gcts_tr_analyzer.clas.abap`
5. Activate: **Ctrl+F3**

#### Step G2 — Test the ABAP Class Standalone
1. In the class body, add a test call:
   ```abap
   ZCL_GCTS_TR_ANALYZER=>GV_TR_ID = 'GMWK900691'.
   NEW ZCL_GCTS_TR_ANALYZER( ).
   ```
2. **F9** → check ADT Console view for cluster output

#### Step G3 — Create ICF Handler (Phase 2)
1. Create class `ZGCTS_ANALYZE_HANDLER` implementing `IF_HTTP_EXTENSION`
2. In SICF (transaction): navigate to `/sap/bc/` → create node `zgcts` → child node `analyze`
3. Handler class: `ZGCTS_ANALYZE_HANDLER` → activate
4. Test via browser: `GET https://<system>/sap/bc/zgcts/analyze?tr=GMWK900691`

---

### Section H — End-to-End Usage Flow (Phase 2 target)

```
Developer: right-clicks TR in Transport Organizer
                          │
                          ▼
         AnalyzeTRHandler detects TR number
         InputDialog opens pre-filled → user clicks OK
                          │
                          ▼
         HTTP GET /sap/bc/zgcts/analyze?tr=GMWK900691
         (uses existing ADT session — no extra login)
                          │
                          ▼
         ABAP: ZCL_GCTS_TR_ANALYZER runs 4-stage XCO pipeline
         Returns JSON: clusters + edges + pullOrder
                          │
                          ▼
         DependencyResultView opens automatically in Eclipse
         Shows: cluster table with risk colour, pull order steps
                          │
                          ▼
         Developer follows Step 1, Step 2 → pull without errors
```

---

### Section I — Troubleshooting

| Symptom | Cause | Fix |
|---------|-------|-----|
| "Analyse Dependencies…" missing from context menu | Plugin not loaded | Check dropins; restart `eclipse -clean`; verify in About → Installation Details |
| TR number not auto-detected | Selection node text format differs | Type TR manually in InputDialog |
| F9 not triggered (Phase 1) | ADT command ID differs in Eclipse version | Press F9 manually; the snippet is already on clipboard |
| HTTP 403 from ICF service (Phase 2) | ICF service not activated or user lacks authorisation | Activate in SICF; check user's ICF authorisation objects |
| HTTP 400 from ICF service | TR number in wrong format | Verify TR matches `[A-Z0-9]{3,4}K[0-9]{6}` |
| Empty output / no tasks found | TR has no tasks or TR number incorrect | Verify TR in SE09; check TR is in the correct system |
| `XCO_CP_CTS` not available | On-premise ECC system — XCO is cloud-only | Tool only works on S/4HANA Cloud / BTP ABAP Environment |
| Compile errors in Java | Target Platform not configured | Redo Section B step 3 — set Running Platform as active |
| No update site in Install New Software | P2 not yet built (Phase 1) | Use dropins method (Section F Option 1) until Phase 3 |

---

## 11. Quick Reference

### TR Number Format

SAP TR numbers follow the pattern: `[SYSTEM_ID][K][6-digit-sequence]`

| System | TR Example  |
|--------|-------------|
| GMW    | GMWK900691  |
| DEV    | DEVK900042  |

Regex: `[A-Z0-9]{3,4}K[0-9]{6}`

### Risk Levels

| Level    | Trigger                                | Required Action                    |
|----------|----------------------------------------|------------------------------------|
| CRITICAL | Same object in two different tasks     | Coordinate with other developer before pulling |
| HIGH     | IMPLEMENTS or INHERITS across tasks    | Must pull the entire cluster together |
| MEDIUM   | TYPE_REF or USES across tasks          | Strongly recommended to pull together |
| NONE     | No cross-task dependency               | Safe to pull independently         |

### Key Keyboard Shortcuts

| Shortcut | Action |
|----------|--------|
| `Ctrl+Alt+G` (Win/Linux) | Trigger Analyse Dependencies from anywhere |
| `Cmd+Alt+G` (macOS) | Trigger Analyse Dependencies from anywhere |
| `Ctrl+F3` | Activate ABAP object in ADT |
| `F9` | Run as ABAP Application (Phase 1 manual trigger) |

---

## 12. Release Guide

### Repository & Infrastructure

| Resource | URL / Path |
|----------|-----------|
| Source code (main branch) | `https://github.com/Mayur175/tr-analyser` |
| Plugin JAR (direct download) | `dist/com.gmw.gcts.analyzer_1.0.0.jar` in repo |
| P2 ZIP (offline install) | `dist/gcts-analyzer-updatesite-1.0.0.zip` in repo |
| gh-pages branch (P2 files) | `https://github.com/Mayur175/tr-analyser/tree/gh-pages` |

> **Important — Pages Authentication Constraint:**
> SAP enterprise GitHub (`github.tools.sap`) requires authentication for all
> Pages URLs — even for public repositories. Eclipse's P2 client cannot
> authenticate through the browser redirect, so
> `https://pages.github.tools.sap/.../updatesite` returns a login redirect
> instead of P2 metadata, causing the error:
>
> `org.eclipse.equinox.p2.core.ProvisionException: Unable to read repository`
>
> **Do NOT use the Pages URL as the P2 update site URL in Eclipse.**
> Use the offline install methods described below instead.

> **Note:** GitHub Actions is also disabled by SAP enterprise administrators on
> `github.tools.sap`. Releases are built locally and pushed manually.
> The `release.yml` workflow is retained for future use if Actions is enabled.

---

### Prerequisites (one-time setup, already done)

| Tool | Version | How to verify |
|------|---------|---------------|
| Java (SapMachine) | 26.0.1 | `java -version` |
| Maven | 3.9.16 | `/opt/homebrew/bin/mvn -version` |
| Maven toolchain (JDK 17) | 17.0.18 | `cat ~/.m2/toolchains.xml` |
| Git | any | `git --version` |

**Toolchain file** (`~/.m2/toolchains.xml`) must point to the JDK 17 installation:
```xml
<toolchains>
  <toolchain>
    <type>jdk</type>
    <provides>
      <id>JavaSE-17</id>
      <version>17</version>
    </provides>
    <configuration>
      <jdkHome>/opt/homebrew/Cellar/openjdk@17/17.0.18/libexec/openjdk.jdk/Contents/Home</jdkHome>
    </configuration>
  </toolchain>
</toolchains>
```

---

### Step-by-Step: How to Release a New Version

#### Step 1 — Make your changes

Edit source files under `TR dependency/` as normal.
Increment the version in these three places if it is a version bump:

| File | Field to update |
|------|----------------|
| `eclipse/com.gmw.gcts.analyzer/META-INF/MANIFEST.MF` | `Bundle-Version:` e.g. `1.1.0.qualifier` |
| `eclipse/com.gmw.gcts.analyzer.feature/feature.xml` | `version=` attribute on `<feature>` |
| `eclipse/pom.xml` | `<version>` in parent + all 3 child `pom.xml` files |

#### Step 2 — Build the P2 update site locally

```bash
cd "/Users/I763161/Documents/Vibe Coding/MCP Servers/TR_Tool/TR dependency/eclipse"

JAVA_HOME="/Library/Java/JavaVirtualMachines/sapmachine-jdk-26.0.1.jdk/Contents/Home" \
  /opt/homebrew/bin/mvn clean package -DskipTests --batch-mode

# Expected output:
# [INFO] BUILD SUCCESS
# Output JAR:  com.gmw.gcts.analyzer/target/com.gmw.gcts.analyzer-<version>.jar
# Output site: com.gmw.gcts.analyzer.updatesite/target/repository/
```

#### Step 3 — Push source changes to main

```bash
cd "/Users/I763161/Documents/Vibe Coding/MCP Servers/TR_Tool/TR dependency"

git add .
git commit -m "Release vX.Y.Z — <short description of changes>"
git push origin main
```

#### Step 4 — Publish the new P2 update site to gh-pages

```bash
# Create a fresh staging directory
rm -rf /tmp/gcts-ghpages && mkdir -p /tmp/gcts-ghpages/updatesite

# Copy P2 repository output
cp -r "/Users/I763161/Documents/Vibe Coding/MCP Servers/TR_Tool/TR dependency/eclipse/com.gmw.gcts.analyzer.updatesite/target/repository/." \
      /tmp/gcts-ghpages/updatesite/

# Copy the landing page (edit version number in index.html if desired)
cp "/Users/I763161/Documents/Vibe Coding/MCP Servers/TR_Tool/TR dependency/eclipse/com.gmw.gcts.analyzer.updatesite/target/repository/../../../.." 2>/dev/null || true

# Re-create index.html with updated version
cat > /tmp/gcts-ghpages/index.html <<'EOF'
<!DOCTYPE html>
<html>
<head>
  <meta charset="UTF-8">
  <title>gCTS Task Dependency Analyzer</title>
  <style>body{font-family:sans-serif;max-width:700px;margin:40px auto;line-height:1.6}</style>
</head>
<body>
  <h1>gCTS Task Dependency Analyzer</h1>
  <p>Eclipse ADT plugin for detecting cross-task object dependencies in SAP gCTS Transport Requests.</p>
  <h2>Install in Eclipse</h2>
  <ol>
    <li>Help → Install New Software → Add</li>
    <li>Name: <code>gCTS Analyzer</code></li>
    <li>URL: <code>https://mayur175.github.io/tr-analyser/updatesite</code></li>
    <li>Select <em>gCTS Tools for ADT</em> → Install → Restart</li>
  </ol>
  <p><a href="https://github.com/Mayur175/tr-analyser">Source code and documentation →</a></p>
</body>
</html>
EOF

# Push to gh-pages (force-overwrite — gh-pages is always a snapshot of latest)
cd /tmp/gcts-ghpages
git init
git config user.email "I763161@sap.com"
git config user.name "I763161"
git remote add origin https://github.com/Mayur175/tr-analyser.git
git checkout --orphan gh-pages
git add .
git commit -m "Release vX.Y.Z — publish P2 update site"
git push -f origin gh-pages
```

#### Step 5 — Verify the update site ZIP is intact

```bash
unzip -l "/Users/I763161/Documents/Vibe Coding/MCP Servers/TR_Tool/TR dependency/dist/gcts-analyzer-updatesite-1.0.0.zip" | grep -E "content|artifact|p2"
# Should show: content.jar, artifacts.jar, p2.index
```

#### Step 6 — Copy updated files to dist/ and commit

```bash
cd "/Users/I763161/Documents/Vibe Coding/MCP Servers/TR_Tool/TR dependency"

# Copy fresh build outputs to dist/
cp eclipse/com.gmw.gcts.analyzer.updatesite/target/com.gmw.gcts.analyzer.updatesite-*.zip \
   dist/gcts-analyzer-updatesite-X.Y.Z.zip

cp eclipse/com.gmw.gcts.analyzer/target/com.gmw.gcts.analyzer-*.jar \
   dist/com.gmw.gcts.analyzer_X.Y.Z.jar

git add dist/
git commit -m "Release vX.Y.Z — updated dist artifacts"
git push origin main
```

#### Step 7 — Tag the release in git

```bash
git tag vX.Y.Z
git push origin vX.Y.Z
```

---

### What Existing Users Need to Do

> **Why the P2 URL doesn't work:**
> SAP enterprise GitHub Pages requires authentication for every request.
> Eclipse's P2 client cannot authenticate, so it gets a login redirect instead
> of repository metadata. Use the offline methods below instead.

---

**Method 1 — Dropins JAR (Simplest — Recommended)**

Install the plugin in 3 steps with no URL needed:

1. Download `dist/com.gmw.gcts.analyzer_1.0.0.jar` from the repo
2. Copy it to your Eclipse `dropins` folder:

| OS | Dropins path |
|----|-------------|
| macOS | `/Applications/Eclipse.app/Contents/Eclipse/dropins/` |
| Windows | `C:\eclipse\dropins\` |
| Linux | `~/eclipse/dropins/` |

3. Restart Eclipse with the `-clean` flag:

```bash
# macOS:
/Applications/Eclipse.app/Contents/MacOS/eclipse -clean

# Windows:
eclipse.exe -clean
```

Verify: **Help → About Eclipse IDE → Installation Details → Plug-ins** — search for `com.gmw.gcts.analyzer`.

---

**Method 2 — Install from Local ZIP (Full P2 with version tracking)**

1. Download `dist/gcts-analyzer-updatesite-1.0.0.zip` from the repo
2. In Eclipse: **Help → Install New Software → Add**
3. Click **Archive…** (not Local, not URL)
4. Browse to the downloaded ZIP file → **Add**
5. Tick **gCTS Tools for ADT** → **Next → Next → Finish**
6. Restart Eclipse

Advantage over dropins: Eclipse tracks the installation and can manage upgrades via **Help → Check for Updates** when a new ZIP is installed.

---

**Method 3 — Team Share via Internal Network Folder (P2 URL that works)**

If your team has access to an internal web server or shared network drive that is accessible without authentication:

1. Unzip `gcts-analyzer-updatesite-1.0.0.zip` to that server/share
2. The install URL becomes `http://internal-server/gcts-analyzer/updatesite/`
   or `file:///\\share\gcts-analyzer\updatesite\` (Windows UNC)
3. This URL works in Eclipse because it requires no authentication

---

**Upgrading to a new release:**

- **Dropins:** Delete the old JAR from `dropins/`, copy the new JAR, restart with `-clean`
- **ZIP install:** Help → Install New Software → Add → Archive → new ZIP → update

**Installing offline (no network access to Pages):**
Download `dist/gcts-analyzer-updatesite-1.0.0.zip` from the repo and use **Method 2** above.

---

### Troubleshooting the Build & Install

| Problem | Cause | Fix |
|---------|-------|-----|
| `Unable to read repository at pages.github.tools.sap/...` | SAP enterprise Pages requires authentication — Eclipse P2 client cannot authenticate | Use **Method 1 (dropins JAR)** or **Method 2 (local ZIP)** instead of the Pages URL |
| `TypeNotPresentException: P2ArtifactRepositoryLayout` | Running `mvn` with JDK 17 but Tycho 5 needs JDK 21+ | Set `JAVA_HOME` to SapMachine 26 as shown in Step 2 |
| `useJDK = BREE configured, but no toolchain found` | `~/.m2/toolchains.xml` missing or wrong path | Recreate the file with the correct JDK 17 path (see Prerequisites) |
| `Preview of features supported only at latest source level` | `--enable-preview` flag in `pom.xml` conflicts with BREE | Do not re-add `--enable-preview` to compiler args — already removed |
| `graph.clear() undefined` | Zest API — `clear()` does not exist on `Graph` | Already fixed — nodes disposed individually |
| `Pattern matching in switch requires Java 21` | `case Type var ->` form not valid in Java 17 | Already fixed — use `instanceof` chain instead |
| Plugin not visible after dropins install | `-clean` flag not used on restart | Restart Eclipse with `eclipse -clean` (once only) |
| `BUILD FAILURE` — network error resolving Eclipse P2 | Firewall / proxy blocking `download.eclipse.org` | Run on corporate network, or set Maven proxy in `~/.m2/settings.xml` |

---

### ABAP Backend — Deploy Changes

When ABAP source files change, re-deploy them manually in ADT:

| Object | File | Action |
|--------|------|--------|
| `ZCL_GCTS_TR_ANALYZER` | `abap/zcl_gcts_tr_analyzer/zcl_gcts_tr_analyzer.clas.abap` | Paste into ADT → Activate (Ctrl+F3) |
| Local Types | `abap/zcl_gcts_tr_analyzer/zcl_gcts_tr_analyzer.clas.locals_def.abap` | Paste into **Local Types** tab of the class |
| `ZGCTS_ANALYZE_HANDLER` | `abap/zgcts_analyze_handler/zgcts_analyze_handler.clas.abap` | Paste into ADT → Activate |
| `ZCL_GCTS_DEP_ATC_CHECK` | `abap/zcl_gcts_dep_atc_check/zcl_gcts_dep_atc_check.clas.abap` | Paste into ADT → Activate |
| `ZGCTS_DEP_HISTORY` | `abap/zgcts_dep_history/zgcts_dep_history.tabl.ddls` | Create table in ADT → Activate |

After changing the ICF handler, test the endpoint:
```bash
curl -u "user:password" \
  "https://<your-sap-host>/sap/bc/zgcts/analyze?tr=GMWK900691"
```
