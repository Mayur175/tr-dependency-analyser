# Release Playbook — Practical paths to "Marketplace-ready", "Cross-TR-ready", "Tested"

The architect review (`ARCHITECT_REVIEW.md`) flagged four items that block a
public release. The user has confirmed that the **ABAP P0s** (cluster
substring match + recursive `uf_find`) can be tested directly in Eclipse
once the tool is imported on a real system — that is the right approach for
those defects.

This playbook covers the **other three blockers** and recommends the best
available approach for each, sequenced so you can ship in stages without
waiting for everything at once.

| # | Blocker | Best available approach | Time | Risk |
|---|---|---|---|---|
| 1 | Public Eclipse Marketplace listing | Stage gates: internal → unlisted → listed | 2 weeks | Low |
| 2 | Marketing claim of "cross-TR support" | Plumb the wire end-to-end + run the 5-TR scenario from `mock_sap_data.py` against the real system | 1 day | Medium |
| 3 | Marketing claim of "tested" | Three-tier evidence pyramid: unit + contract + integration | 3-4 days | Low |

Total elapsed: about 2 weeks. None of these blockers requires waiting for
SAP to release new APIs or for an external team to deliver anything.

---

## 1. Public Eclipse Marketplace listing

### Why it's blocked today

- `marketplace/` folder is a placeholder with submission instructions, not a
  submitted listing.
- The plugin SymbolicName is still `com.gmw.gcts.analyzer` while the
  user-facing label is "TR Analyser" — Marketplace reviewers will ask why.
- Hosting story is unclear: README points at a GitHub Pages URL the project
  itself documents as non-working.
- No screencast / before-after demo — Marketplace listings without a 30-sec
  demo video get filtered out by users in the first 5 seconds.

### Best available approach — three stages, escalating commitment

#### Stage A — Internal pilot (first 1-2 weeks after the P0 fixes)

This is the lowest-risk way to prove the tool out before any public listing.

1. **Build the update site once** with `mvn clean package` and host the
   resulting `repository/` folder on whichever artifact store your team
   already uses:
   - SAP-internal **Nexus** or **Artifactory** if the team has one.
   - **Sharepoint / Teams file area** as a ZIP (yes, this works — Eclipse
     accepts a local archive via *Help → Install New Software → Add →
     Archive*).
   - **Internal Git repo's "Releases" page** if the team is on GitHub
     Enterprise — releases pages don't require Pages auth and produce a
     stable download URL.
2. Document the install path in `README.md` with a screenshot.
3. Recruit **5-10 developers from your own team** as pilot users. Run for
   2 sprints. Collect feedback in a single GitHub issue.
4. Track these metrics:
   - Number of analyses run (telemetry from `ZGCTS_DEP_HISTORY`)
   - % of pilot users who run it more than once (ad-hoc → habit)
   - Number of dependency-related QA failures **before** vs **during** the pilot
5. Exit criteria for Stage A:
   - 0 P0 / P1 issues open
   - At least one team member has used it on a real cross-TR conflict and
     credited it in a release note

#### Stage B — Unlisted public release (week 3-4)

Once Stage A is green, you can publish the artefacts publicly **without a
Marketplace listing**. This is a real common pattern and it's how
`abap-cleaner` itself shipped originally.

1. Push the update site to a **public GitHub release**. Releases (unlike
   GitHub Pages) do not require authentication and Eclipse's P2 client
   handles them fine via the Archive install path.
2. Add a short `INSTALL.md` with two paths:
   - **Path A** (1 click): "Drop this JAR into Eclipse `dropins/`"
   - **Path B** (recommended): "Help → Install New Software → Add → Archive"
3. Cut a v1.0.0 tag. Use Tycho's `tycho-versions-plugin` to set the
   qualifier to `1.0.0.YYYYMMDDHHMM-<commit-sha>` so each release is
   reproducible.
4. Announce on whichever internal channels are appropriate (Teams,
   blog, internal community of practice). Keep the announcement short and
   link to a 30-second screencast (see Stage C item 3).
5. Exit criteria for Stage B:
   - 5+ external (different team) installations confirmed
   - 1 issue raised by a non-author and closed

#### Stage C — Eclipse Marketplace submission (week 5-6)

Marketplace gating is mostly cosmetic — once Stages A and B are clean,
Stage C is paperwork. The Marketplace reviewer checklist
(<https://marketplace.eclipse.org/marketplace-publishing-faq>):

| Item | Action |
|---|---|
| Working update-site URL | The same GitHub Releases URL from Stage B |
| Screenshots (3 required) | (1) Right-click on TR → Analyse menu, (2) Result view with a HIGH cluster, (3) Preferences page |
| 30-second screencast | Record with QuickTime / OBS; show: paste 2 TR ids → click Analyse → see the cross-TR sequence. Upload to YouTube unlisted, link in the description. |
| `feature.xml` license | EPL 2.0 (already used by Eclipse itself, frictionless) |
| Compatible-with declarations | Eclipse 2024-09 onwards (matches your Tycho `target` setting) |
| Plugin description | Re-use `README.md` first 3 paragraphs verbatim, with one diagram |
| Categories | "Code Management" + "Source Code Analyzer" |

Submit `marketplace/marketplace.xml` (already in repo) via
<https://marketplace.eclipse.org/user/login?destination=node/add/marketplace-listing>.
Approval is typically 1-3 business days.

### Open questions for the project owner

- Is this an SAP-internal tool or genuinely public? If internal, **stop at
  Stage B** — Marketplace adds GDPR-style support obligations (you become
  a "publisher" who must respond to issues). Internal-only saves that.
- Who is the **named maintainer** when an issue is filed? Marketplace
  requires a real human, not a generic alias.
- License: confirm EPL 2.0 is acceptable to your legal team (it's
  permissive but viral on derivative works of the plugin itself).

---

## 2. Marketing claim of "cross-TR support"

### Why it's blocked today

- The ABAP backend already accepts `?tr=A,B,C` and parses it.
- The Java `AnalyzerHttpClient.analyze()` takes one `String`.
- The dialog regex `TR_PATTERN` matches one id.
- The result view header shows one TR.

So the wire is ready, the algorithm is ready, but the **user-facing path is
single-TR only**. We cannot honestly claim cross-TR support.

### Best available approach — close the four gaps in one PR

This is the single most valuable change in the entire project (project's
own plan calls it "MVP item 2"). Estimated effort: **1 day**.

#### Step 1 — `AnalyzerHttpClient.analyze(List<String>)`

```java
public AnalysisResult analyze(List<String> trIds) {
    if (trIds == null || trIds.isEmpty()) {
        return AnalysisResult.error("No TR ids supplied.");
    }
    String csv = trIds.stream()
                      .map(String::trim)
                      .filter(s -> !s.isEmpty())
                      .collect(Collectors.joining(","));
    String encoded = URLEncoder.encode(csv, StandardCharsets.UTF_8);
    URI uri = new URI(systemUrl + ICF_PATH + "?tr=" + encoded);
    // ... rest unchanged
}

// Keep single-string overload for callers that still pass one id
public AnalysisResult analyze(String tr) {
    return analyze(List.of(tr));
}
```

#### Step 2 — `TrDetector.TR_LIST_PATTERN`

```java
private static final String TR_ID = "[A-Z0-9]{3,4}K[0-9]{6}";
public static final Pattern TR_LIST_PATTERN = Pattern.compile(
    "^\\s*" + TR_ID + "(\\s*,\\s*" + TR_ID + ")*\\s*$");
```

#### Step 3 — `AnalyzeTRHandler.promptForTr`

Change the input dialog to accept a comma-separated list. Show an example
in the prompt: `"GMWK900691, DEVK900042"`.

#### Step 4 — `DependencyResultView.setPartName`

Show the input set in the view title so users opening two analyses
back-to-back can tell them apart:

```java
String title = trIds.size() == 1
    ? "TR Analyser - " + trIds.get(0)
    : "TR Analyser - " + trIds.size() + " TRs";
setPartName(title);
```

### Best available proof that it works

Run the **Scenario 3 fixture from `mock_sap_data.py`** against the live
system. The expected output is:

```
Step 1: G1 -> DEVK900200  [RELEASE_ALONE]
Step 2: G2 -> DEVK900201  [RELEASE_ALONE]  (waits on: G1)
Step 3: G3 -> DEVK900202  [RELEASE_ALONE]  (waits on: G2)
Step 4: G4 -> DEVK900203  [RELEASE_ALONE]
Step 5: G5 -> DEVK900204  [RELEASE_ALONE]  (waits on: G4)
```

To make this real:

1. Create the 5 mock objects (`ZDOM_ARTID`, `ZDE_ARTID`, `ZTBL_ARTICLE`,
   `ZIF_ARTICLE`, `ZCL_ARTICLE_API`) in a sandbox client. ~10 minutes.
2. Put each one in its own TR via SE03. ~5 minutes.
3. Open Eclipse plugin → enter all 5 TRs → run.
4. Compare the result view to the Python output. They should match
   byte-for-byte.

If they match, you have **end-to-end empirical proof** that:
- The wire format works
- The ABAP backend handles a multi-TR input
- The TR-level topo-sort produces the right order
- The Eclipse plugin renders it correctly

This is a 1-day live-system smoke test that converts the claim from
"theoretical" to "demonstrated".

### Honest claim language after the fix

Before:
> ❌ "TR Analyser supports cross-TR dependency analysis"

After:
> ✅ "TR Analyser detects activation and type dependencies across up to N
> TRs in a single analysis (verified on N=5 in mixed CDS/DDIC
> dependency chains). Output includes a Basis-ready DEV → QA → PROD
> release sequence."

The N=5 is what you have proven. State the limit honestly. As production
TRs of higher cardinality are tested, raise N.

---

## 3. Marketing claim of "tested"

### Why it's blocked today

The verification report is unusually honest about this:
- Python algorithm tests: ✅ verified
- JSON contract round-trip: ✅ verified
- Tycho build: ✅ green
- ABAP runtime SQL execution: ❌ never executed
- ICF authority check actually denying access: ❌ never executed
- Eclipse plugin clicked through in ADT: ❌ never executed
- abapGit pull: ❌ never executed

Nothing has been observed running on a real SAP system. "Tested" with that
foundation is overstating it.

### Best available approach — Three-tier evidence pyramid

The industry standard for "we have tested this" is three layers, each with
its own evidence and each suitable for a different audience.

```
                    ┌─────────────────────────────┐
                    │   Tier 3: Integration       │  Tier 3 is what
                    │   (live SAP + live Eclipse)  │  you currently
                    └──────────────┬──────────────┘  cannot claim.
                                   │
                  ┌────────────────┴───────────────┐
                  │   Tier 2: Contract             │  Tier 2 is partly
                  │   (JSON schema validation,     │  done — extend it.
                  │    cross-language round-trip)  │
                  └────────────────┬───────────────┘
                                   │
              ┌────────────────────┴───────────────────┐
              │   Tier 1: Unit                         │  Tier 1 is the
              │   (Python sims, ABAP Unit, JUnit)      │  starting point.
              └────────────────────────────────────────┘
```

#### Tier 1 — Unit tests (Python ✅, ABAP ❌, Java ❌)

| Layer | Today | Action | Effort |
|---|---|---|---|
| Python | 4 fixtures pass + 3 mock-data scenarios | Wrap in `pytest`, generate coverage report | 0.5 day |
| ABAP   | Nothing | Add `cl_abap_unit_assert` tests for `uf_find`, `stage2b_conflicts`, `to_json`. Each method has an obvious input/output contract. | 1 day |
| Java   | Nothing | Add JUnit 5 tests for `AnalysisResult.fromJson` (especially after the `org.json` swap), plus a `MockHttpServer`-backed test for `AnalyzerHttpClient`. | 1 day |

After this round you can credibly say:
> ✅ "150+ unit tests, X% coverage, run on every commit via CI"

#### Tier 2 — Contract tests (start small, grow naturally)

The JSON wire format is the contract between the ABAP producer and the
Java consumer. Lock it down once and never break it silently.

1. **Publish a JSON Schema** under
   `verification/tr-analyser-schema-v1.json` (Draft-2020-12). It already
   exists implicitly — formalise it.
2. Add a Python test that loads every fixture's `to_json()` output and
   validates against the schema with `jsonschema`.
3. Add a Java test that takes the same fixture (a checked-in
   `.json` file) and parses it with the production parser. Assert
   round-trip equality.
4. Pin the schema by version in the JSON itself (you already do —
   `"version":"1.1"`). When you bump the version, run a migration test
   against the previous fixture file.

After this round you can credibly say:
> ✅ "JSON wire contract is locked by schema and verified across both
> producer and consumer in CI."

#### Tier 3 — Integration tests (the live-system smoke test)

This is the one that converts "tested" from "asserted in code" to
"demonstrated end-to-end". Three milestones, each named explicitly so
nobody pretends an earlier milestone was a later one.

| Milestone | What is proven | What is not yet proven |
|---|---|---|
| **M-Sandbox** | abapGit pulls onto a sandbox tenant; classes compile; SICF node responds; Eclipse plugin can authenticate | Nothing about real TRs |
| **M-Hello-World** | Run analyser on the 5-TR fixture from `mock_sap_data.py` (manually created in sandbox) | Nothing about scale or edge cases |
| **M-Real-TR** | Run analyser on a real cross-TR conflict that the team has already encountered. Output matches the analyst's hand-investigation. | Performance under thousands of objects |

Each milestone produces a screenshot or a log artefact that goes into
`verification/integration-evidence/M-*/`. The `VERIFICATION_REPORT.md`
table grows from "Unknown — requires live SAP system" rows to actual
"✅ Verified on tenant SBX, run-id 20260620-001" entries.

After all three milestones:
> ✅ "Integration-tested on a sandbox tenant against a known real-world
> cross-TR conflict. Result matched the analyst's hand investigation."

### Honest claim ladder

| Stage | Honest claim |
|---|---|
| Today | "Algorithm correctness verified by Python simulation. Java plugin compiles and packages. SAP backend not yet executed on a live system." |
| After Tier 1 | "Algorithm correctness verified by 4 + 3 + N test fixtures. JSON contract verified by round-trip. SAP backend not yet executed on a live system." |
| After Tier 2 | "Algorithm and wire contract are locked by schema and tested in CI on every commit. SAP backend not yet executed on a live system." |
| After Tier 3 M-Sandbox | "Verified on sandbox tenant: abapGit pull, ICF round-trip, basic auth gate." |
| After Tier 3 M-Hello-World | "End-to-end verified on the 5-TR mock fixture against a live tenant." |
| After Tier 3 M-Real-TR | "Verified against a real production-shaped cross-TR conflict." |
| After 6 months of pilot | "In production at <team>, has caught X dependency conflicts, prevented Y QA failures." |

Pick the language that matches the evidence. Never skip a rung.

---

## 4. Sequenced rollout plan

```
Week 0 ────────── Fix ABAP P0s (cluster string + recursive uf_find)
                  Test in Eclipse (user has confirmed they can do this)
Week 1 ────────── Fix Java P0s (org.json parser + List<String> API)
                  Plumb cross-TR end-to-end (4 small Java edits)
Week 2 ────────── Tier 1 unit tests (Python pytest, ABAP Unit, JUnit)
                  M-Sandbox + M-Hello-World integration milestones
Week 3 ────────── Tier 2 schema + cross-language contract test in CI
                  M-Real-TR integration milestone
Week 4 ────────── Stage A internal pilot (5-10 developers)
                  Update marketing language to "Verified on N=5, piloted
                  with team T"
Week 5-6 ──────── Stage B unlisted public release (GitHub Releases)
                  Stage C Marketplace submission paperwork
Week 7+ ───────── Marketplace listing live, follow up on feedback,
                  start Phase 3 (CTS_REQUEST_CHECK BAdI) from
                  SOLUTION_ARCHITECTURE.md
```

The critical path is Week 0-2. Weeks 3 onward can be reordered or
parallelised based on team availability.

---

## 5. What this playbook deliberately does NOT recommend

- **Do not skip Stage A internal pilot.** Going straight to the
  Marketplace without dogfooding produces 1-star reviews for the kind of
  bugs only real usage finds.
- **Do not claim "cross-TR" before the 4-line Java fix and the
  M-Hello-World run.** It is dishonest and the first user who tries it
  will write a public issue.
- **Do not claim "tested" until at least Tier 1 + M-Sandbox is done.**
  Marketing claims that don't match evidence are the fastest way to lose
  trust.
- **Do not chase Phase 3-10 features (BAdI gate, GitHub PR check, AST
  scan) before the MVP is in production.** They are described in
  `SOLUTION_ARCHITECTURE.md` for when the foundation is solid; pursuing
  them in parallel splits engineering effort and slows MVP shipping.

---

## 6. Quick reference — what to say externally at each stage

| Stage | One-line claim |
|---|---|
| Today | "Internal R&D project, algorithm verified by simulation, awaiting live-system rollout." |
| After Week 2 | "End-to-end verified on a 5-TR sandbox fixture, internal pilot starting." |
| After Week 4 | "Used by team T for 2 weeks, caught X conflicts, no false positives reported." |
| After Week 6 | "Available on Eclipse Marketplace; install via Help → Marketplace → Search 'TR Analyser'." |
| After Week 12 | "Used by 3+ teams across the organisation. Drop-in install via abapGit + Eclipse Marketplace." |

Every claim must be defensible against a one-question challenge:
*"How do you know?"* If the answer is "we ran it and saved the log here",
the claim is honest. If the answer is "we believe so because the algorithm
is correct in Python", the claim is overstated.