# Security Policy

## Reporting a vulnerability

If you discover a vulnerability in TR Dependency Analyser, **do not file a
public GitHub issue**. Instead, please report it privately:

- Open a [GitHub Security Advisory](https://github.com/Mayur175/tr-dependency-analyser/security/advisories/new)
  on the repository (preferred — gives a controlled disclosure timeline).
- Or contact the maintainer through GitHub: [@Mayur175](https://github.com/Mayur175).

We will acknowledge receipt within **5 business days** and aim to provide a
remediation plan within **30 days** for confirmed issues. We will credit
reporters in the changelog unless they ask to remain anonymous.

---

## Supported versions

| Version | Supported |
|---|---|
| 1.0.x | ✅ Active |
| < 1.0  | ❌ Unsupported |

---

## Known security posture (read before deployment)

The following are **deliberate, documented design choices** in v1.0 — not
vulnerabilities. They are listed here so operators understand the
defaults before deploying.

### 1. ICF handler `AUTHORITY-CHECK` defaults to FALSE

The ICF handler [`zgcts_analyze_handler`](abap/src/zgcts_analyze_handler.clas.abap)
ships with `AUTHORITY-CHECK` **disabled by default** so pilot users on
personal / dev tenants can install via abapGit and use the tool without
waiting for Basis to grant `S_TRANSPRT` (TTYPE=`CUST`, ACTVT=`03`).

**While disabled:**
- Every authenticated SAP user with HTTP access to the ICF node can read
  **any** TR's contents via this endpoint.
- The handler emits an `X-Auth-Bypass: yes` HTTP response header on every
  request so operations / monitoring can detect the open posture.

**Operators MUST:**
- Flip the constant `c_enforce_auth` (or equivalent control flag in the
  source) to `abap_true` before deploying to anything other than a
  personal sandbox.
- After flipping, callers without `S_TRANSPRT` (TTYPE=`CUST`,
  ACTVT=`03 Display`) receive HTTP 403.

The exact procedure is documented in the source comment of the handler
class.

### 2. Cloud variant intentionally has no outbound HTTP

The Cloud handler [`zcl_gcts_http_handler_cloud`](abap_cloud/src/zcl_gcts_http_handler_cloud.clas.abap)
intentionally does **not** issue outbound HTTP. The factory parameter
names of `cl_http_destination_provider` vary by SP level; the v1
implementation is purely inbound to keep the cloud variant stable across
releases. This is a stability choice, not a security issue, but it does
mean Phase 4 (GitHub PR Status Check) is implemented out-of-band.

### 3. The Eclipse plugin stores credentials in Eclipse Secure Storage

The plugin's preference page stores SAP credentials in
[Eclipse Secure Storage](https://help.eclipse.org/latest/index.jsp?topic=/org.eclipse.platform.doc.user/reference/ref-securestorage.htm)
(JFace / `ISecurePreferences` API). This is the standard Eclipse mechanism
and uses platform-native key storage (macOS Keychain, Windows DPAPI,
Linux libsecret) where available.

### 4. Persisted analysis history (`ZGCTS_HIST`)

When `persist=true` is passed to the ICF endpoint, results are written to
the customer-namespace table `ZGCTS_HIST`. Anyone with `S_TABU_DIS` for
table `ZGCTS_HIST` can read this history. **It contains TR / task IDs and
object names** — no secrets, but potentially sensitive to release
planning. Restrict table access on production tenants.

---

## Secure-coding standards for contributors

- **No secrets in commits.** Tokens, passwords, private keys, customer
  TR contents, customer object names — never. The pre-commit hygiene
  checklist in [CLAUDE.md §3.4](CLAUDE.md) applies.
- **No new ICF handlers without `AUTHORITY-CHECK`.** Any new endpoint
  must check an appropriate authorisation object (`S_TRANSPRT`,
  `S_DEVELOP`, `S_RFC`, etc.) before reading repository data.
- **No `SELECT *` on customer data.** Project only the columns you need;
  reduces data exposure if logs or dumps capture the row.
- **No dynamic SQL with unsanitised input.** Use `@`-escaped host vars
  (already standard in this repo).
- **No swallowed exceptions.** Every `CATCH` either re-throws, logs, or
  has a comment explaining why the exception is harmless.
- **No reflective access to undocumented SAP APIs without a fallback.**
  ADT private APIs may be used only behind a `try/catch` that keeps the
  plugin functional when the API drifts.

---

## Reporting issues with the public install URL

The plugin's update site is hosted on GitHub Pages
(`https://mayur175.github.io/tr-dependency-analyser/`). If you observe
the site serving unexpected content, suspect a supply-chain compromise,
or notice a divergence between the published JAR and the source in this
repository, please report it via the channels at the top of this file
**before** installing the affected version.
