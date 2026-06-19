# How to import the latest changes into GitHub and Eclipse

There are **two separate things** to keep in sync:

| Layer | Tool | What "update" means |
|---|---|---|
| **A. GitHub** | `git` (command line) | Push your code changes from this folder to the GitHub repo |
| **B. Eclipse plugin** | Eclipse "Check for Updates" | Pull the newest plugin version onto your IDE |

They're independent. You can do A without B, B without A, or both. Each section below is a complete recipe — no jumping around.

---

## A. Push code changes to GitHub

Use this any time you have edited a file inside the `TR dependency/` folder
(ABAP source, Java source, docs, anything) and you want it on GitHub.

### Quick check first — is anything to push?

```bash
cd "TR dependency"
git status
```

You'll see one of three things:

| What you see | What it means | What to do |
|---|---|---|
| `nothing to commit, working tree clean` | No local edits yet | Nothing to push. Start over once you've actually edited something. |
| Lines under `Changes not staged for commit:` | You've edited files but haven't told git about them yet | Continue with Step 1 below. |
| Lines under `Changes to be committed:` | Already staged, just needs a commit | Skip to Step 2. |

### Step 1 — Stage your changes

```bash
git add -A          # stage every change
# or, if you want only one file:
# git add abap/src/zgcts_analyze_handler.clas.abap
```

### Step 2 — Commit with a clear message

```bash
git commit -m "Short description of WHAT changed and WHY"
```

Good messages:
- ✅ `"Disable AUTHORITY-CHECK on sandbox to unblock pilot users"`
- ✅ `"Fix typo in README install section"`

Bad messages (please avoid — your future self will hate you):
- ❌ `"update"`
- ❌ `"fix"`
- ❌ `"changes"`

### Step 3 — Push to GitHub

```bash
git push tr-dep main
```

That's it. Refresh `https://github.com/Mayur175/tr-dependency-analyser` in
the browser and your change is there.

> **First time on a new machine?** Git will prompt for username +
> Personal Access Token (not your GitHub password). Generate one at
> <https://github.com/settings/tokens> with `repo` scope. After that,
> macOS Keychain remembers it forever — no more prompts.

### Bonus — also push to the SAP-internal mirror (`origin`)

```bash
git push origin main
```

(Optional. Only do this if you also want the change on
`github.tools.sap`. The two remotes are independent.)

---

## A.bis — Did the change touch the Eclipse plugin code?

If you edited anything under `TR dependency/eclipse/` (Java source, plugin
manifest, etc.), users won't see the change until you **republish the
update site**. The Eclipse "Check for Updates" only finds new versions on
the URL we publish at `https://mayur175.github.io/tr-dependency-analyser/`.

Steps:

```bash
# 1. Build the new plugin
cd "TR dependency/eclipse"
mvn -B -DskipTests clean package

# 2. Refresh the gh-pages branch
cd "/Users/I763161/Documents/Vibe Coding/MCP Servers/TR_Tool/TR dependency"
git worktree add /tmp/tr-dep-ghpages2 gh-pages
cd /tmp/tr-dep-ghpages2

# 3. Replace the unpacked update-site files
rm -rf artifacts.* content.* features plugins p2.index
cp -R "/Users/I763161/Documents/Vibe Coding/MCP Servers/TR_Tool/TR dependency/eclipse/com.gmw.gcts.analyzer.updatesite/target/repository/." .

# 4. Replace the dist/ downloads
mkdir -p dist
cp "/Users/I763161/Documents/Vibe Coding/MCP Servers/TR_Tool/TR dependency/eclipse/com.gmw.gcts.analyzer.updatesite/target/com.gmw.gcts.analyzer.updatesite-"*.zip dist/com.gmw.gcts.analyzer.updatesite-1.0.0.zip
cp "/Users/I763161/Documents/Vibe Coding/MCP Servers/TR_Tool/TR dependency/eclipse/com.gmw.gcts.analyzer/target/com.gmw.gcts.analyzer-"*.jar dist/com.gmw.gcts.analyzer-1.0.0.jar

# 5. Commit and push
git add -A
git -c user.name="Mayur175" -c user.email="mayur175@users.noreply.github.com" \
    commit -m "Publish update site (new plugin version)"
git push tr-dep gh-pages

# 6. Clean up
cd "/Users/I763161/Documents/Vibe Coding/MCP Servers/TR_Tool/TR dependency"
git worktree remove /tmp/tr-dep-ghpages2
git branch -D gh-pages
```

GitHub Pages will pick up the new `gh-pages` content automatically within
30-60 seconds. You can confirm with:

```bash
curl -sI "https://mayur175.github.io/tr-dependency-analyser/" | head -3
```

Look for `HTTP/2 200`.

> **If you only edited ABAP code (under `abap/src/`) or docs**, skip A.bis
> entirely. The Eclipse plugin doesn't change for those — only the ABAP
> backend on your SAP system needs to be re-imported (see Section C).

---

## B. Pull the latest plugin into Eclipse

Use this when someone has republished the update site (i.e., A.bis above
ran on someone's machine and a new version is now on github.io).

### Step 1 — Tell Eclipse to look for updates

In Eclipse: **Help → Check for Updates**

Eclipse pings every update site you have configured (including
`https://mayur175.github.io/tr-dependency-analyser/`).

### Step 2 — Confirm the install

A dialog appears showing what changed. Click **Next**, accept the
licence, click **Finish**, restart Eclipse when prompted.

### Step 3 — Verify

After restart:

- Right-click any TR in the Transport Organizer → **TR Analyser…**
- The version is visible in **Help → About Eclipse → Installation Details
  → Installed Software** (look for `TR Analyser for ADT`).

That's it.

> **If "Check for Updates" says nothing changed** but you know the URL
> has new bytes:
> 1. Quit Eclipse.
> 2. Restart with `eclipse -clean` once (forces a P2 cache flush).
> 3. Try again.

### If you used Method 2 (local ZIP) or Method 3 (dropins JAR)

Those installs do **not** auto-update. You have to:

1. Download the new ZIP / JAR from
   `https://mayur175.github.io/tr-dependency-analyser/dist/`
2. Repeat the same install steps (Help → Install New Software → Add →
   Archive, OR drop into `dropins/` + `eclipse -clean`).

This is exactly why Method 1 (URL) is recommended — it's the only one
with auto-update.

---

## C. (Bonus) Pull the latest ABAP backend onto your SAP system

If a change went into `TR dependency/abap/src/*.abap` (e.g., the
`c_enforce_auth` flag we added to `ZGCTS_ANALYZE_HANDLER`):

1. In your SAP system, run `ZABAPGIT` (transaction `SE38`).
2. Open the existing repo entry for this project.
3. Click **Pull** (it shows a delta of files that changed).
4. Activate (Ctrl+F3) the changed objects.
5. The `/sap/bc/zgcts/analyze` ICF endpoint immediately serves the new
   logic. No restart needed.

If your SAP system has no abapGit, you'll need to manually copy-paste each
changed `.abap` file into ADT and activate. abapGit is much faster — see
`abap/INSTALL_VIA_ABAPGIT.md`.

---

## A complete "I just changed something on my laptop, what do I do?" tree

```
You edited a file. Where was it?

├── docs only (README.md, *.md, etc.)
│   → A. Push code changes to GitHub
│   (DONE)
│
├── ABAP source (abap/src/*.abap)
│   → A. Push code changes to GitHub
│   → C. Pull on the SAP system via abapGit, activate
│
├── Java / Eclipse plugin source (eclipse/**)
│   → A. Push code changes to GitHub
│   → A.bis. Rebuild + republish the update site
│   → ask users to do B. Help → Check for Updates
│
└── Python verification scripts (verification/*.py)
    → A. Push code changes to GitHub
    → re-run the simulators locally to make sure they still pass
```

---

## What I just verified for you (right now)

```
Local branch:  main           (clean, no uncommitted edits)
Remote main:   tr-dep/main    a7e9920 - everything is in sync
Remote pages:  tr-dep/gh-pages cbc07bc - Eclipse update site is current
```

So **right now there is nothing to push** — your laptop and GitHub are
already aligned. The procedures above are for the next time you make a
change.

---

## One-line cheat sheet

| Task | Command |
|---|---|
| See what's changed locally | `git status` |
| Stage every change | `git add -A` |
| Commit | `git commit -m "describe what changed"` |
| Push to GitHub | `git push tr-dep main` |
| Republish Eclipse plugin (after Java change) | rebuild + push to `gh-pages` |
| Refresh Eclipse to get newest plugin | Help → Check for Updates |
| Refresh ABAP backend after `.clas.abap` change | abapGit Pull + activate |