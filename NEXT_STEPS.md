# Next Steps — In Plain Language

Every step below is grounded in files that already exist in this project.
No invented APIs, no speculative tooling. If a step depends on something
outside the workstation (a real SAP system, a real Eclipse install), it is
called out as such.

---

## Step 1 — Get the tool onto a real SAP test system (sandbox)

**What:** Take what's in `TR dependency/abap/` and load it into a real SAP
sandbox / development client.

**How:**
1. Open ADT (ABAP Development Tools in Eclipse).
2. Connect to your sandbox SAP system.
3. If your sandbox has **abapGit** installed, use it to pull this folder.
   Instructions are already written in `TR dependency/abap/INSTALL_VIA_ABAPGIT.md`.
4. If abapGit is **not** available, manually create the four objects by
   copy-pasting each `.abap` file:
   - `ZCL_GCTS_TR_ANALYZER` (the main analyser class)
   - `ZGCTS_ANALYZE_HANDLER` (the HTTP handler)
   - `ZCL_GCTS_DEP_ATC_CHECK` (the ATC plug-in)
   - `ZGCTS_HIST` (the history table)

**You'll know it worked when:** all four objects activate without error.

**Why this is first:** until the ABAP side compiles on a real system, every
other step is theoretical. The Python simulator says the algorithm is right,
but only a real ABAP compiler can confirm the syntax actually works.

---

## Step 2 — Wire up the HTTP entry point (SICF node)

**What:** Tell SAP to expose the analyser over HTTP so the Eclipse plugin
can call it.

**How:** Follow the existing instructions in
`TR dependency/abap/docs/SICF_SETUP.md`. In short, in transaction `SICF`:
- Create a service node at path `/sap/bc/zgcts/analyze`
- Point its handler class to `ZGCTS_ANALYZE_HANDLER`
- Activate the node

**You'll know it worked when:** running this curl command from your laptop
returns a JSON response (even a "Missing parameter" error counts as
working — it means the service is alive):

```
curl -u <your-user>:<your-password> "https://<your-sap-host>/sap/bc/zgcts/analyze?tr="
```

---

## Step 3 — Build and install the Eclipse plugin

**What:** Compile the Java code and install it into your Eclipse / ADT.

**How:**
```
cd "TR dependency/eclipse"
mvn clean package -DskipTests
```
This produces a folder at
`com.gmw.gcts.analyzer.updatesite/target/repository/`. In Eclipse:
- *Help → Install New Software → Add → Archive*
- Pick that `repository/` folder
- Tick "TR Analyser for ADT" → Next → Next → Finish → restart Eclipse

**You'll know it worked when:** in Eclipse you see *Window → Preferences →
TR Analyser*. Configure the SAP URL, your username, password, click
"Test Connection". It should respond green.

---

## Step 4 — Test it on the SAFE example first (intra-TR conflict)

**What:** Use the **Scenario 1** mock data we've already validated in Python
to see if the real system behaves the same way.

**How:**
1. In your sandbox, create one TR with three tasks. (You can use SE09 / SE10
   for this.)
2. In task 1 add a class `ZCL_ORDER_API`.
3. In task 2 also add the same class `ZCL_ORDER_API` (this creates the
   conflict).
4. In task 3 add another class `ZCL_ORDER_REPORT` that inherits from
   `ZCL_ORDER_API`.
5. In Eclipse: right-click the parent TR → *TR Analyser…*

**You'll know it worked when:** the result view shows the same answer the
Python simulator does — one CRITICAL cluster with all three tasks, action
"COORDINATE first, then pull together". This is documented step-by-step in
`TR dependency/verification/MOCK_DATA_SIMULATION.md` (Scenario 1).

**If it does NOT match the Python answer:** that's the moment the two
ABAP P0 defects (the cluster-string substring match and the recursive
union-find) flagged in `ARCHITECT_REVIEW.md` would appear. Fix those *only
if* the test exposes them.

---

## Step 5 — Test the cross-TR scenario (multiple TRs)

**What:** Repeat Step 4 with **Scenario 3** from
`MOCK_DATA_SIMULATION.md` — five separate TRs in a dependency chain.

**Honest pre-requisite:** today the Eclipse dialog only accepts ONE TR id.
You will hit that limit. Two options:

- **Option A (1-day Java change):** Modify the four files described in
  `RELEASE_PLAYBOOK.md` Section 2 (the dialog regex, the HTTP client
  signature, the view title, the result handler) to accept a comma-separated
  list. Then retest.
- **Option B (no code change):** Use `curl` to hit the ICF endpoint
  directly. The ABAP backend already accepts comma-separated input:
  ```
  curl -u <user>:<pwd> "https://<host>/sap/bc/zgcts/analyze?tr=DEVK900200,DEVK900201,DEVK900202,DEVK900203,DEVK900204"
  ```
  Compare the JSON body to the JSON the Python simulator produces.

**You'll know it worked when:** the JSON shows the 5-step DEV→QA→PROD
sequence with each TR waiting on its predecessor.

---

## Step 6 — Decide what to fix based on what actually broke

**This is the honest path.** Do **not** pre-emptively change code that
might be right. Use Steps 4 and 5 to expose actual defects, then fix only
what is observed to be broken. Concretely:

| If you see… | Fix this |
|---|---|
| Wrong cluster membership when TR ids share a prefix | The ABAP `tasks` string + `CS` issue (P0.S1 in `ARCHITECT_REVIEW.md`) |
| Short-dump on a large TR | The recursive `uf_find` (P0.S2) |
| Garbled non-English text in the result view | The Java JSON parser (P0.J1) |
| Cannot enter multiple TRs in dialog | The 4 small Java edits (P0.J2) |
| HTTP 401 / 403 when you expect to be logged in | Your user lacks `S_TRANSPRT` (TTYPE=`CUST`, ACTVT=03 Display) |
| HTTP 404 | SICF node not active (re-do Step 2) |
| Eclipse "no result" with no error | The hand-rolled JSON parser dropped a field — Java P0.J1 again |

If everything works, you can skip the fixes entirely. The architect review
flagged risks, not certainties.

---

## Step 7 — Pilot with 5-10 colleagues

**What:** Hand the plugin to a small group on your team. Run for one or two
sprints.

**How:**
1. Zip the Eclipse `repository/` folder, share via Teams / Sharepoint /
   internal Nexus / GitHub Enterprise Release.
2. Give each colleague a one-page install guide (`README.md` already has
   most of it).
3. Create one tracking page where they paste:
   - The TR(s) they analysed
   - What the tool said
   - What actually happened in QA
4. Watch for false positives ("the tool warned but nothing broke") and
   false negatives ("the tool said safe but QA failed").

**You'll know it's ready for a wider audience when:** zero false negatives
in two sprints, and at least one developer has a story like *"this caught a
mistake before I released it"*.

---

## Step 8 — Make a 30-second screencast

**What:** A small video showing the cross-TR scenario.

**How:** macOS QuickTime or any screen recorder. Show:
1. Two TRs with a dependency conflict (open them in SE09).
2. Right-click → *TR Analyser…* in Eclipse.
3. The result view appearing with the recommended order.

This single artefact is worth more than any document for getting other
teams interested. Keep it under 30 seconds.

---

## Step 9 — Public release (optional)

**Only do this if Step 7 is green.** Two stages:

1. Push the build to **GitHub Releases** (not Pages — Pages is auth-walled
   inside SAP enterprise, Releases is not). Cut a `v1.0.0` tag.
2. If you want it on the Eclipse Marketplace, fill in
   `TR dependency/marketplace/marketplace.xml` with the screenshots and
   screencast from Step 8 and submit at
   `https://marketplace.eclipse.org`. Approval typically takes 1-3 business
   days.

If this tool is internal only, **stop at GitHub Release**. Marketplace adds
publisher support obligations you don't need.

---

## Step 10 — Then, and only then, look at Phase 3 onwards

`SOLUTION_ARCHITECTURE.md` describes 10 phases. Steps 1-9 above cover
"Phase 0 + Phase 1". The juicy stuff (auto-block release with
`CTS_REQUEST_CHECK` BAdI, GitHub PR check, source AST scan) is Phase 3
onwards. Each is independently shippable. Pick the next one that solves a
problem your pilot users actually hit. Don't build any of them in advance.

---

## Honest summary of what is verified vs. what is assumed

| Statement | Verified by | Status |
|---|---|---|
| The 4-stage algorithm produces correct cluster + pull-order on the 4 fixtures | `verification/simulate_pipeline.py` running on this workstation, all 4 PASS | ✅ Verified |
| The TR-level DEV→QA→PROD topo-sort is correct on the 3 SAP-shaped scenarios | `verification/mock_sap_data.py` running on this workstation, all 3 PASS | ✅ Verified |
| The Eclipse plugin compiles into an installable update site | `mvn clean package` recorded in `verification/VERIFICATION_REPORT.md` | ✅ Verified |
| The JSON wire round-trips correctly between ABAP-shape and Java-shape parsers | `verification/verify_json_contract.py` all 4 round-trips PASS | ✅ Verified (within the documented parser limits) |
| The ABAP source compiles on a real S/4HANA system | — | ❌ Not yet verified — needs Step 1 |
| The `AUTHORITY-CHECK` actually blocks unauthorised users | — | ❌ Not yet verified — needs Step 2 |
| The Eclipse plugin renders the JSON correctly inside ADT | — | ⚠ Plugin builds clean, but no live click-through done — needs Step 3 |
| The two ABAP P0 risks (cluster substring, recursion stack) actually fire | — | ❌ Risks identified by code reading, not yet observed — Steps 4 and 5 will expose them if real |
| The Java JSON parser limits identified are real bugs | `verify_json_contract.py::known_parser_limits` empirically confirmed unicode-escape failure | ✅ One bug confirmed; others identified by code reading not yet hit |

Use this table to set expectations with anyone you brief. Anything in the
"Not yet verified" column needs Steps 1-5 of this playbook before being
asserted.

---

## A simple "where am I" tracker

Print this and tick boxes as you go:

```
[  ] Step 1  Loaded ABAP into sandbox; classes activated
[  ] Step 2  SICF node /sap/bc/zgcts/analyze active; curl works
[  ] Step 3  Eclipse plugin installed; "Test Connection" green
[  ] Step 4  Scenario 1 (intra-TR conflict) tested in Eclipse;
             matches Python simulator
[  ] Step 5  Scenario 3 (5-TR chain) tested via curl or after
             4-line Java change; matches Python simulator
[  ] Step 6  Fixed only the defects that actually appeared in
             Steps 4 and 5
[  ] Step 7  Pilot with 5-10 colleagues for 1-2 sprints
[  ] Step 8  30-second screencast recorded
[  ] Step 9  GitHub Release cut (and optionally Marketplace
             listing submitted)
[  ] Step 10 Picked the next Phase from SOLUTION_ARCHITECTURE.md
             based on what pilot users actually need
```

If you can't tick the box on a step, do **not** start the next one. The
sequence is what makes the claims defensible.