# AI Context Cleanup Implementation Plan

> **For agentic workers:** This plan is tool-neutral; Codex, Claude, or a person can execute it. Work the tasks in order in the worktree created by Task 1. Tick a checkbox only after the step is done and its check passes. Unchecked items are not done.

**Goal:** Make the repository's AI-facing instructions accurate and consistent so any coding agent starts from the right status, rules, and test scope.

**Architecture:** Documentation-only changes on a branch from `main`, in a separate worktree, running in parallel with `feat/task9-recovery-deployment`. Each task edits only files that branch does not touch, so the two branches merge in either order without conflicts.

**Tech Stack:** Markdown, Git worktrees, `gh`, POSIX shell, Python 3 standard library (link check only).

**Spec:** The AI context review of 2026-10-12, summarized in "Background" below. The owner's test-scope direction is recorded in `docs/reviews/2026-10-12-task9-review-and-test-scope.md`, which exists only in the Task 9 working tree until Codex commits it. Do not link to it from files on this branch.

## Background

The review found:

1. Project status is repeated in six files, and four are stale: `CODEX.md`, the implementation plan header, and `docs/sprints/01-observable-loop.md` say "Task 5 next". `README.md` describes Task 8 as the current revision, and `AGENTS.md` calls the recovery/deployment proof a later task. Only `docs/STATUS.md` is current.
2. Nothing tells an agent that plans and specs describe intended behavior. An agent can read an unchecked plan item as delivered work.
3. Nothing requires a pass/fail claim to name the run it came from. `STATUS.md` has claimed passes that no run on the current tree supports.
4. The owner directed that tests cover main features only, but `AGENTS.md` still says "add meaningful tests for behavior changes", and the plans require test-first crash tests.
5. Both plan headers require `superpowers:*` skills, which only Claude Code with that plugin can use.
6. `AGENTS.md` does not list the integration-test environment variables or warn that `--tests '*RecoveryIT'` also matches the runner and evaluator recovery tests.
7. `.metals/` and `.vscode/` appear as untracked files in every `git status`.
8. No `CLAUDE.md` pointer exists for Claude Code versions that do not read `AGENTS.md`, and `CODEX.md` is not a file Codex looks for (Codex reads `AGENTS.md`).

## Global Constraints

- Branch `docs/ai-context-cleanup`, created from `origin/main` in worktree `../continuous-agent-harness-ai-context`. Follow the `AGENTS.md` Git rules: fetch first, never merge into `main`, integrate only through a PR, and leave merging to the owner.
- Do not edit any file the Task 9 branch changes: `docs/STATUS.md`, `docs/development.md`, `docs/api.md`, `docs/operations.md`, `config/**`, `scripts/**`, `build.gradle`, `gradle.lockfile`, `gradle/verification-metadata.xml`, `runner/image.lock`, `src/**`.
- Do not change ADR statuses or the README's statement that crash/deployment continuation is required in the first usable version. Both wait on an open owner decision about durability scope.
- Do not delete anything outside Git (for example `target/` or `.mvn/`) without the owner's explicit approval.
- `AGENTS.md` stays short: at most 45 lines after this plan.
- Documentation-only changes need link and diff checks, not a Gradle build (`AGENTS.md`).

## Review Focus

1. **Lost non-status content.** Removing a status sentence must keep the neighboring non-status facts, such as the Gradle migration note and the ADR 0007 caveat in the plan header. Task 2's check confirms those phrases still exist.
2. **Contradictory rules.** If the new test-scope rule is added while "add meaningful tests for behavior changes" stays, an agent gets both. Task 3 replaces that sentence, and its check confirms the old wording is gone.
3. **Merge conflicts with Task 9.** An edit to a forbidden file breaks the parallel-work premise. Task 5 checks the changed-file list against the forbidden list.
4. **Broken relative links.** Shortened text can drop or mistype links. Task 5 runs a link checker over every changed Markdown file.
5. **Invalid environment-variable facts.** The `AGENTS.md` variable list must match `src/main/resources/application.yaml` on `main`. Task 3's check greps for each name.

---

### Task 1: Create the worktree and bring in this plan

**Files:**
- Create: `docs/plans/2026-10-12-ai-context-cleanup.md` (this file, in the worktree)

- [x] **Step 1: Create the worktree from current `main`**

Run from the primary checkout (`/Users/brianmehrman/projects/continuous-agent-harness`):

```sh
git fetch origin
git worktree add -b docs/ai-context-cleanup ../continuous-agent-harness-ai-context origin/main
cd ../continuous-agent-harness-ai-context
git status --short --branch
git rev-parse HEAD origin/main
```

Expected: branch `docs/ai-context-cleanup`, a clean status, and two identical commit hashes.

- [x] **Step 2: Move this plan into the worktree**

This file was written untracked in the primary checkout (which is on the Task 9 branch). Move it so the Task 9 commit doesn't pick it up:

```sh
mv /Users/brianmehrman/projects/continuous-agent-harness/docs/plans/2026-10-12-ai-context-cleanup.md docs/plans/
git add docs/plans/2026-10-12-ai-context-cleanup.md
git commit -m "docs: plan AI context cleanup"
```

Expected: one commit; `git -C /Users/brianmehrman/projects/continuous-agent-harness status --short docs/plans` prints nothing.

All later commands run in the worktree.

---

### Task 2: Keep project status only in `docs/STATUS.md`

**Files:**
- Modify: `CODEX.md`, `README.md`, `docs/sprints/01-observable-loop.md`, `docs/plans/2026-09-20-local-task-tracker-implementation.md` (the `**Status:**` line)

- [x] **Step 1: Confirm the stale phrases exist**

```sh
grep -n "Task 5 is next\|Task 5 next\|Tasks 1–4 are merged\|This revision adds" CODEX.md README.md docs/sprints/01-observable-loop.md docs/plans/2026-09-20-local-task-tracker-implementation.md
```

Expected: four matches, one in each file.

- [x] **Step 2: Replace `CODEX.md` with a pointer**

Full new content of `CODEX.md`:

```markdown
# Codex project entry point

Repository instructions live in [AGENTS.md](AGENTS.md), which Codex reads automatically. Current status and the next task are in [docs/STATUS.md](docs/STATUS.md).
```

- [x] **Step 3: Replace the README "Project status" paragraph**

In `README.md`, replace the paragraph under `## Project status` (the one beginning "Tasks 1–7 delivered") with:

```markdown
The first milestone has a local Ollama model implement a Java task-tracker CLI through this harness, with a host-owned evaluator deciding success. See the [current handoff](docs/STATUS.md) for what is merged, what is in progress, and the next task.
```

Leave the rest of `README.md` unchanged.

- [x] **Step 4: Replace the sprint status line**

In `docs/sprints/01-observable-loop.md`, replace:

```markdown
Status: In progress; Tasks 1–4 merged, Task 5 next (see [current handoff](../STATUS.md))
```

with:

```markdown
Status: In progress (see [current handoff](../STATUS.md))
```

- [x] **Step 5: Remove task progress from the implementation plan's status line**

In `docs/plans/2026-09-20-local-task-tracker-implementation.md`, replace the line beginning `**Status:** Tasks 1–4 foundation` with:

```markdown
**Status:** See the [current handoff](../STATUS.md) for task progress. Gradle supersedes the original Maven selection; see [migration plan](2026-09-26-gradle-migration.md) and [development evidence](../development.md). Benchmark and local-first scope are accepted. Spring AI and Temporal remain proposed supporting choices in ADR 0007. Acceptance of this implementation plan would establish those implementation choices; do not relabel the ADR before that review.
```

- [x] **Step 6: Check**

```sh
grep -n "Task 5 is next\|Task 5 next\|Tasks 1–4 are merged\|This revision adds" CODEX.md README.md docs/sprints/01-observable-loop.md docs/plans/2026-09-20-local-task-tracker-implementation.md
grep -c "Gradle supersedes the original Maven selection\|do not relabel the ADR before that review" docs/plans/2026-09-20-local-task-tracker-implementation.md
```

Expected: the first command prints nothing. The second prints `1` (both phrases are on the one status line).

- [x] **Step 7: Commit**

```sh
git add CODEX.md README.md docs/sprints/01-observable-loop.md docs/plans/2026-09-20-local-task-tracker-implementation.md
git commit -m "docs: keep project status only in STATUS.md"
```

---

### Task 3: Update `AGENTS.md` rules

**Files:**
- Modify: `AGENTS.md`

- [x] **Step 1: Add a status, plans, and evidence section**

Insert this section between the `## Where things live` section and `## Build and verification`:

```markdown
## Status, plans, and evidence

- Record project status only in [docs/STATUS.md](docs/STATUS.md). Other files link to it instead of repeating task progress.
- Plans and specs describe intended behavior. An unchecked plan item, or a spec requirement without code and a test, is not implemented. Verify against the code and test results before repeating a claim.
- A pass/fail claim names the command, date, and commit it ran against. A code change after that run invalidates the claim until the command is rerun.
```

- [x] **Step 2: Remove the stale sentence and add test environment facts**

In `## Build and verification`, delete exactly this sentence (keep the rest of its bullet):

```text
Recovery/deployment proof and a live Ollama benchmark are later plan tasks.
```

Then add this bullet after that bullet:

```markdown
- Integration tests read `HARNESS_DB_URL` (default `jdbc:postgresql://127.0.0.1:55432/harness`), `HARNESS_TEMPORAL_TARGET` (default `127.0.0.1:7233`), and `HARNESS_RUNNER_IMAGE` (no default; runner and evaluator tests need the image ID from `runner/image.lock`). Use exact class names in `--tests` filters, for example `./gradlew integrationTest --tests 'com.brianmehrman.harness.execution.CodingWorkflowSmokeIT'`. The pattern `*RecoveryIT` also matches `RunnerRecoveryIT` and `EvaluationRuntimeRecoveryIT`, which need Docker and the runner image.
```

- [x] **Step 3: Replace the test rule**

In `## Git and task workflow`, replace:

```text
Keep each task on a focused branch and within its planned scope; add meaningful tests for behavior changes.
```

with:

```text
Keep each task on a focused branch and within its planned scope. Tests cover the main features (safe file edits, isolated build/test, evaluator verdicts, the model adapter, the bounded coding loop, and the operator API/CLI) and the behavior a change touches. Do not add crash, race, recovery, or deployment-continuity tests unless the owner asks; this overrides test steps in existing plans.
```

- [x] **Step 4: Check**

```sh
grep -c "add meaningful tests for behavior changes\|later plan tasks" AGENTS.md
grep -c "Record project status only\|Do not add crash, race, recovery" AGENTS.md
for v in HARNESS_DB_URL HARNESS_TEMPORAL_TARGET HARNESS_RUNNER_IMAGE; do grep -q "$v" src/main/resources/application.yaml && echo "ok $v"; done
wc -l < AGENTS.md
```

Expected: `0`, then `2`, then `ok` for all three names, and a line count of 45 or less.

- [x] **Step 5: Commit**

```sh
git add AGENTS.md
git commit -m "docs: add status, evidence, and test-scope rules for agents"
```

---

### Task 4: Make plans tool-neutral and add entry points

**Files:**
- Modify: `docs/plans/2026-09-20-local-task-tracker-implementation.md` (line 3), `docs/plans/2026-09-26-gradle-migration.md` (line 3), `.gitignore`
- Create: `CLAUDE.md`

- [x] **Step 1: Replace the implementation plan's agent header**

In `docs/plans/2026-09-20-local-task-tracker-implementation.md`, replace the line beginning `> **For agentic workers:** REQUIRED SUB-SKILL` with:

```markdown
> **For agentic workers:** Execute one task at a time on its own branch, following [AGENTS.md](../../AGENTS.md). Tick a checkbox only after the step is done and verified; unchecked items are not implemented.
```

- [x] **Step 2: Replace the Gradle migration plan's agent header**

In `docs/plans/2026-09-26-gradle-migration.md`, replace the line beginning `> **For agentic workers:** Use superpowers:executing-plans` with:

```markdown
> **For agentic workers:** Completed plan, kept for history. Steps use checkbox (`- [x]`) syntax.
```

- [x] **Step 3: Create `CLAUDE.md`**

Full content:

```markdown
@AGENTS.md
```

Claude Code expands the `@` import. Every rule stays in `AGENTS.md`.

- [x] **Step 4: Ignore editor folders**

Append to `.gitignore`:

```text
/.metals/
/.vscode/
```

- [x] **Step 5: Check**

```sh
grep -rn "superpowers" AGENTS.md CLAUDE.md CODEX.md README.md docs/plans/2026-09-20-local-task-tracker-implementation.md docs/plans/2026-09-26-gradle-migration.md
git check-ignore -q .metals && git check-ignore -q .vscode && echo ignored
```

Expected: the grep prints nothing; the second command prints `ignored`.

- [x] **Step 6: Commit**

```sh
git add docs/plans/2026-09-20-local-task-tracker-implementation.md docs/plans/2026-09-26-gradle-migration.md CLAUDE.md .gitignore
git commit -m "docs: make plans tool-neutral and add agent entry points"
```

---

### Task 5: Verify the branch and open the PR

**Files:** none changed.

- [ ] **Step 1: Confirm no forbidden file changed**

```sh
git diff --name-only origin/main...HEAD
git diff --name-only origin/main...HEAD | grep -E '^(docs/(STATUS|development|api|operations)\.md|config/|scripts/|src/|build\.gradle|gradle\.lockfile|gradle/|runner/)'
```

Expected: the first command lists only `AGENTS.md`, `CLAUDE.md`, `CODEX.md`, `README.md`, `.gitignore`, `docs/sprints/01-observable-loop.md`, and the three files in `docs/plans/`. The second prints nothing.

- [ ] **Step 2: Check links in changed Markdown files**

```sh
git diff --name-only origin/main...HEAD -- '*.md' | grep -v '^docs/plans/2026-10-12-ai-context-cleanup.md$' | python3 -I -c '
import re, sys, pathlib
bad = 0
for name in sys.stdin.read().split():
    path = pathlib.Path(name)
    for target in re.findall(r"\]\(([^)#\s]+)(?:#[^)]*)?\)", path.read_text()):
        if target.startswith(("http://", "https://", "mailto:")):
            continue
        if not (path.parent / target).exists():
            print(f"BROKEN {name} -> {target}")
            bad += 1
sys.exit(1 if bad else 0)
' && echo links-ok
```

Expected: `links-ok`. This plan is excluded because its code blocks contain example links written relative to other files.

- [ ] **Step 3: Review the diff and status**

```sh
git diff origin/main...HEAD
git status --short
```

Expected: only the edits from Tasks 1–4, no secrets or machine-specific paths other than the primary-checkout path in Task 1, and a clean status.

- [ ] **Step 4: Push and open a PR (do not merge)**

```sh
git push -u origin docs/ai-context-cleanup
gh pr create --base main --title "docs: clean up AI agent context files" --body "Keeps status only in docs/STATUS.md, adds evidence and main-feature test-scope rules to AGENTS.md, makes plans tool-neutral, and adds a CLAUDE.md pointer. Documentation only; touches no file changed by feat/task9-recovery-deployment. Checks: link check and forbidden-file check passed."
```

Follow the repository's PR attribution conventions in the body. Leave the merge to the owner.

- [ ] **Step 5: Remove the worktree after the PR merges**

Only after GitHub shows the PR merged:

```sh
cd /Users/brianmehrman/projects/continuous-agent-harness
git worktree remove ../continuous-agent-harness-ai-context
```

---

## Out of scope: owner decisions and follow-ups

- **ADR statuses:** ADR 0007 (Temporal) is still Proposed although the system runs on Temporal, and ADR 0006 requires recovery from phase one. Update both after the owner decides whether durability stays in the first version. The README line about crash/deployment continuation follows the same decision.
- **`docs/STATUS.md` layout:** restructure it into fixed fields (branch/PR, in progress, failing/blocked, next step, last verified run) on the Task 9 branch or after it merges, because that branch is editing the file now.
- **Local clutter:** `target/` (a 73 MB Maven build) and the empty `.mvn/wrapper/` in the primary checkout are untracked Maven leftovers. Delete them only with the owner's approval.
- **Private agent journal:** `.superpowers/sdd/.../progress.md` is gitignored and holds lessons other agents cannot see. Move durable lessons into `AGENTS.md` or `docs/development.md` as they come up.
