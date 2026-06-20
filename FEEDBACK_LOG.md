# FEEDBACK_LOG — Corrections and Lessons

> Every correction, push-back, or "no that's wrong" the user has stated.
> Read at session start; treat **Active rules** as if appended to CLAUDE.md.
> See [CLAUDE.md §8.2](CLAUDE.md) for the rules of this file.

---

## Active rules

These corrections must be applied on every relevant turn. New entries land
here; once a rule is codified into `CLAUDE.md` proper, it moves to
*Resolved / superseded* with a back-reference.

### [2026-06-20] — When an SAP artefact cannot be located, ASK
- **What I did wrong / what was unclear:** the original `CLAUDE.md` told me
  to "verify SAP APIs from authoritative sources" but did not pin down
  the *behaviour* when verification failed. The implicit fallback would
  have been to write plausible-looking code with a `" TODO verify"` comment
  — exactly the failure mode SAP code cannot tolerate.
- **What the user actually wanted:** if I cannot find the source of a
  class, table, BAdI, function module, ICF node, authorisation object, or
  XCO factory method, I must **stop and ask** — do not improvise.
- **Rule going forward:** *Stop emitting code, name the artefact precisely,
  offer the user-side verification path (SE11 / SE24 / SE18 / SE19 / SE80
  / SE93 / SICF / SU21 or the SAP Help URL), and wait. Only resume after
  the user supplies the artefact or authorises a documented graceful
  degradation path.*
- **Generalisation:** A plausible-looking SAP API call that does not exist
  on the target system is worse than a refusal — it fails at customer
  activation time, not at our development time. Refusal is cheap; wrong
  code is expensive. Same principle applies to any environment-specific
  surface (release-state contracts, SP-level method drift, BAdI filter
  characteristics).

### [2026-06-20] — OOP + SOLID is mandatory, not a preference
- **What I did wrong / what was unclear:** an earlier draft of CLAUDE.md
  §4.1 said *"no speculative abstractions"*, which the user read as
  permitting "just write a procedural method that works". That is not what
  was intended — SOLID is required *when adding the second concrete
  implementation* (Cloud vs Classic, XCO vs table-SELECT), and the
  backwards-compatibility checklist must be applied before merge.
- **What the user actually wanted:** every design must use OOP with SOLID
  so that future extensions do not break existing code.
- **Rule going forward:** *Apply the SOLID-in-this-repo's-domain table
  (CLAUDE.md §4.0) and the four-question backwards-compatibility checklist
  before any merge. Strategy / Factory / Template Method / Adapter are the
  approved patterns; god classes, singletons, and inheritance for code
  reuse are not.*
- **Generalisation:** "no speculative abstractions" and "design for
  extension" are not in conflict — abstractions are introduced *when a
  second concrete need is being added*, not before. The two rules form a
  pair: don't speculate, but when the second case lands, do it properly
  with an interface and dependency inversion.

### [2026-06-20] — Confirm hard-to-reverse outward-facing actions every time
- **What I did wrong / what was unclear:** when the user asked to "create a
  new repo", I needed to clarify (a) name, (b) visibility, (c) history
  policy, (d) remote alias before doing it. I did clarify — this entry
  exists to lock that behaviour in for next time.
- **What the user actually wanted:** for any action that changes
  remote-state on an external service (creating repos, enabling Pages,
  archiving, opening PRs, sending webhooks), pause and ask for the small
  set of decisions that are genuinely the user's, before acting.
- **Rule going forward:** *Use AskUserQuestion for the 2–4 decisions that
  matter; default to the safer choice on each. Never enable Pages, archive,
  delete, or push to a non-default remote without explicit go-ahead.*
- **Generalisation:** Standing authorization (CLAUDE.md §3) covers
  routine, reversible work on the user's repo. It does not extend to
  outward-facing publishing, irreversible destruction, or anything that
  affects users beyond the maintainer.

### [2026-06-20] — Playwright MCP cannot use the user's Edge session
- **What I did wrong / what was unclear:** when the user pointed at "I'm
  logged in to GitHub in Edge", the implicit assumption was that
  Playwright MCP would inherit that session. It does not — it launches its
  own isolated Chromium profile.
- **What the user actually wanted:** to know this constraint up front, and
  for me to offer a working path (gh CLI, manual login inside Playwright,
  or "you do it manually, I verify").
- **Rule going forward:** *Never claim Playwright MCP shares browser
  sessions with the user's local browser. When a task needs an authenticated
  GitHub action, prefer the `gh` CLI (already authenticated) over driving a
  browser. Reserve Playwright for read-only inspection or for flows where
  the user has explicitly accepted typing credentials into the
  MCP-controlled browser.*
- **Generalisation:** MCP servers run in isolated environments by design.
  Cookies, keychain entries, browser profiles, ssh agents, and
  environment-variable secrets from the user's shell are NOT visible to
  them unless explicitly forwarded. Always pick the tool whose
  authentication context already matches the task.

### [2026-06-20] — Maintain SESSION_SUMMARY.md and FEEDBACK_LOG.md every meaningful turn
- **What I did wrong / what was unclear:** without a continuity file, every
  new session starts from scratch and the user has to re-explain context.
  Every correction risks being repeated.
- **What the user actually wanted:** two files at the repo root —
  SESSION_SUMMARY.md (cumulative state) and FEEDBACK_LOG.md (corrections
  log) — updated automatically.
- **Rule going forward:** *At session start, read both files. After each
  meaningful turn (state actually changed), update SESSION_SUMMARY.md per
  CLAUDE.md §8.1 and append any new correction to FEEDBACK_LOG.md per §8.2.
  Do not skip the update for "small" changes — small changes accumulate
  into context loss across sessions.*
- **Generalisation:** Persistent project memory beats in-context memory for
  multi-session work. The cost of two file edits per turn is far lower
  than the cost of re-deriving the project state next session.

---

## Resolved / superseded

*(empty — no rules have stabilised into pure-CLAUDE.md scope yet. When one
does, move it here with a back-reference such as
`now codified in CLAUDE.md §X.Y`.)*

---

*This file is appended to whenever the user issues a correction. See
[CLAUDE.md §8.2](CLAUDE.md). Repeating a mistake already logged here is
the worst-case failure — flag it explicitly to the user if it happens.*
