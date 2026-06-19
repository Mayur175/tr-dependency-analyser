# How to push future changes to GitHub

This project is **already** a Git repository. Three remotes are configured:

| Name | URL | Purpose |
|---|---|---|
| `origin` | `https://github.tools.sap/I763161/gcts-analyzer.git` | SAP-internal GitHub (work-in-progress) |
| `github` | `https://github.com/Mayur175/tr-analyser.git` | Older public repo (legacy) |
| `tr-dep` | `https://github.com/Mayur175/tr-dependency-analyser.git` | **Current public repo** — this is where the cleaned-up code lives |

The branch is `main`. Use `tr-dep` for new public pushes; `github` is kept
for historical reference only.

So you don't need to set anything up from scratch. You just need to know
the daily workflow.

---

## The 4-step routine for every change

After you edit any file, do these four commands from inside the
`TR dependency` folder:

```bash
cd "TR dependency"

# 1. See what changed
git status

# 2. Stage the files you want to commit
git add <file1> <file2> ...
# or stage everything that changed:
# git add -A

# 3. Commit with a clear message
git commit -m "Short description of what changed"

# 4. Push to the remote(s) you want
git push origin main           # SAP-internal
git push tr-dep main           # public  (current)
# git push github main         # legacy public repo - usually skip
```

That's it. You can push to one remote, the other, or both — they're
independent.

---

## Right now you have un-committed changes

`git status` shows there are already modified and new files **staged but
not yet committed**. Before you do anything else:

```bash
cd "TR dependency"

# Look at what's about to be committed
git status
git diff --cached            # exact lines staged

# When you're happy, commit
git commit -m "Add architect review, mock SAP simulation, release playbook"

# Push to both remotes
git push origin main
git push tr-dep main
```

If you're unsure what's staged, run `git diff --cached` first — it shows
the exact lines about to leave your laptop.

---

## A safer "feature branch" workflow (recommended once you're not alone)

Pushing straight to `main` is fine for one-person work. As soon as a
second person joins, switch to feature branches so changes can be reviewed.

```bash
# Start from a clean main
git checkout main
git pull origin main

# Create a branch for your change
git checkout -b feature/auth-bypass-flag

# ... edit files ...

git add -A
git commit -m "Make AUTHORITY-CHECK optional via class constant"

# Push the branch
git push origin feature/auth-bypass-flag

# Open a Pull Request on GitHub:
#   - SAP-internal: https://github.tools.sap/I763161/gcts-analyzer
#   - Public:        https://github.com/Mayur175/tr-analyser
# Merge it via the web UI after review.
```

---

## Common situations and the exact commands

### A. "I changed one ABAP file, want it on GitHub"

```bash
cd "TR dependency"
git add abap/src/zgcts_analyze_handler.clas.abap
git commit -m "Make AUTHORITY-CHECK optional via class constant"
git push origin main
git push tr-dep main
```

### B. "I made many changes across ABAP, Java, and docs"

```bash
cd "TR dependency"
git status                   # review the list first
git add -A                   # stage every change
git commit -m "Cross-TR support: Java client + ABAP handler + tests"
git push origin main
git push tr-dep main
```

### C. "I want to undo my last commit (not yet pushed)"

```bash
git reset --soft HEAD~1      # keeps the file edits, undoes the commit
# fix the files, then re-commit
```

### D. "Someone else pushed; I need to bring their changes in"

```bash
git pull origin main
# resolve any merge conflicts the editor flags, then:
git push origin main
```

### E. "I want to push to the public GitHub but NOT the SAP-internal one"

```bash
git push tr-dep main         # only the public remote
```

### F. "I want to see the history of a file"

```bash
git log -- abap/src/zgcts_analyze_handler.clas.abap
```

---

## Authentication — first time on a new laptop

Both remotes use HTTPS. The first `git push` will prompt for credentials:

| Remote | What you enter |
|---|---|
| `origin` (`github.tools.sap`) | Your SAP I-number (e.g. `I763161`) and a **Personal Access Token** generated at <https://github.tools.sap/settings/tokens>. NOT your network password. |
| `tr-dep` (`github.com`) | Your public GitHub username and a **Personal Access Token** generated at <https://github.com/settings/tokens>. |

To avoid typing the token every time, install the **Git Credential
Manager** (already on macOS by default if you installed Git via `brew` or
the Xcode command-line tools). It stores the token in macOS Keychain and
silently injects it on every push.

Quick test that auth works:

```bash
git ls-remote origin
git ls-remote tr-dep
```

Both should print a list of branches without prompting (after the first
successful push).

---

## What to check before EVERY push

Five questions, twenty seconds each:

1. **Does it build?**
   ```bash
   cd eclipse && mvn clean package -DskipTests
   ```
   Fail = don't push.

2. **Do the simulator tests still pass?**
   ```bash
   python3 verification/simulate_pipeline.py
   python3 verification/mock_sap_data.py
   ```
   Both must exit 0.

3. **Did I leave any debug code, hardcoded passwords, or sandbox flags?**
   Specifically check:
   ```bash
   grep -n "c_enforce_auth.*abap_false" abap/src/zgcts_analyze_handler.clas.abap
   ```
   This MUST return nothing if you're pushing to a branch that goes to QA.

4. **Is my commit message useful 6 months from now?**
   - ❌ `"fix"` `"update"` `"changes"`
   - ✅ `"Make AUTHORITY-CHECK optional via class constant; add X-Auth-Bypass header"`

5. **Am I pushing to the right remote?**
   - SAP-internal work → `origin` only
   - Public release → `github` only or both
   - When in doubt → `origin` first, then later promote to public via a clean PR.

---

## ⚠️ Multi-line commit messages — avoid the `dquote>` trap

If your commit message has more than one paragraph, or contains any of:

- parentheses around numbers, e.g. `lv_risk(10)`, `pull_action(30)`
- ampersands, e.g. `ANALYZER & DEP_ATC_CHECK`
- backticks, single quotes inside double quotes
- exclamation marks (zsh treats `!` as history expansion even inside `"`)
- a `$(...)`, `` ` ` ``, or `${...}` fragment

…then **DO NOT use `git commit -m "..."` directly on the command line**.
zsh will silently break out of the double-quote, leave you stuck at a
`dquote>` continuation prompt, and the commit will hang forever waiting
for a closing quote that never comes. The terminal log looks like this:

```text
cmdand dquote>   - constructor: drop the (001) text-symbol reference...
cmdand dquote>   - run: replace deprecated static GV_TR_ID...
cmdand dquote> ZCL_GCTS_TR_ANALYZER (persist_result):
```

That `dquote>` is zsh asking for the rest of the string. The command
never runs, the push never happens, and any tool driving the terminal
(Cline, an IDE task runner, CI) just sits in "Pending" forever.

### The safe pattern — file-based commit message

```bash
cd "TR dependency"

# 1. Write the message to a temp file (any editor or heredoc works)
cat > .git-commit-msg.tmp <<'EOF'
Code review fixes for ZCL_GCTS_TR_ANALYZER & ZCL_GCTS_DEP_ATC_CHECK

ZCL_GCTS_TR_ANALYZER (persist_result):
  - Remove (len) substring forms on string values (lv_risk(10),
    ls_dep-kind(20), pull_action(30), source_object(60), etc.).

ZCL_GCTS_DEP_ATC_CHECK (constructor + run + raise_finding):
  - Drop (001) text-symbol reference; class has no text elements.
  - Replace deprecated static GV_TR_ID with instance constructor.
EOF

# 2. Commit using -F (file), not -m (string)
git commit -F .git-commit-msg.tmp

# 3. Push and clean up
git push tr-dep main
rm .git-commit-msg.tmp
```

The single-quoted `<<'EOF'` heredoc disables every shell expansion, so
parentheses, `&`, `!`, `$`, backticks — none of them matter.

### Other safe alternatives

| Situation | Command |
|---|---|
| Multi-line message, ad-hoc | `git commit` (no `-m`) → opens `$EDITOR` |
| Multi-line message, scripted | `git commit -F path/to/msg.txt` |
| Single short line, plain ASCII | `git commit -m 'short message'` (single quotes) |
| Want to amend the last commit's message | `git commit --amend -F newmsg.txt` |

### Rules of thumb for `-m`

`git commit -m "..."` is **only** safe when ALL of these are true:

1. The message fits on one line.
2. It contains no `(`, `)`, `&`, `!`, `` ` ``, `$`, `"`, `'`.
3. It is plain ASCII (no smart quotes pasted from a doc).

If even one of those is in doubt → use `-F` with a file. It's two extra
lines and saves you from a hung terminal.

### Recovering when you ARE stuck at `dquote>`

If your terminal already shows `dquote>` (or `quote>` for single-quote):

1. Type a single `"` (or `'`) and press Enter — closes the dangling string.
2. Press `Ctrl+C` to abort the now-malformed command.
3. Verify nothing was committed: `git log -1 --oneline`.
4. Retry using the file-based pattern above.

Do **not** keep pasting more lines hoping it'll figure itself out — every
new line just gets appended to the same broken string.

---

## A note on the "two-remote" pattern

This project pushes to both an internal (`github.tools.sap`) and a public
(`github.com`) Git server. That's a deliberate setup, not an accident:

- **Internal remote** carries the work-in-progress, customer-specific
  branches, and anything that mentions internal system names, I-numbers, etc.
- **Public remote** carries only the parts intended for external sharing —
  documentation, generic ABAP code, the Eclipse plugin source.

**Be careful not to push internal-only content to the public remote.**
Before any `git push tr-dep main`, scan for accidental leaks:

```bash
# Dry-run search for anything that looks internal
grep -rn "github.tools.sap\|I[0-9]\{6\}\|<internal-host>" \
    --exclude-dir=node_modules --exclude-dir=.git \
    --exclude="HOW_TO_PUSH_TO_GITHUB.md" .
```

If anything sensitive comes back, scrub it before the public push.

---

## Tagging a release (when you ship a version)

Once a milestone is reached (Step 9 of `NEXT_STEPS.md` — public release):

```bash
# Tag the current commit
git tag -a v1.0.0 -m "TR Analyser v1.0.0 - first public release"

# Push the tag (separately from branches)
git push origin v1.0.0
git push tr-dep v1.0.0
```

GitHub then automatically creates a "Release" page from the tag, which is
where you upload the Eclipse update-site ZIP for users to download.

---

## A 30-second cheat sheet to print

```
                  ┌──────── EVERYDAY ──────────┐
                  │                            │
   git status     │  see what changed          │
   git add -A     │  stage every change        │
   git commit -m  │  commit with a real msg    │
   git push       │  send to remote            │
                  │                            │
                   ├─── BEFORE YOU PUSH ────────┤
                   │  1. Build: mvn package     │
                   │  2. Tests: python3 sims    │
                   │  3. Grep: no debug flags   │
                   │  4. Msg:  is it useful?    │
                   │  5. Remote: origin / github│
                   │  6. Multi-line msg? -F file│
                   │                            │
                   └────────────────────────────┘
```

---

## What this guide deliberately does NOT do

- It does not assume you'll be using GitHub Actions, Jenkins, or any CI
  service. Add those when (a) the team is more than 1 person and (b) the
  manual checks above start being skipped.
- It does not push you toward Git LFS or submodules. The repo is small
  enough that plain `git` handles it.
- It does not recommend force-pushing to `main`. If you ever feel the urge
  to `git push --force`, stop and ask — there is almost always a safer
  option.