# Work Queue and Loop Console Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Let a local operator submit free-text coding work for this repository, watch one active task and the durable queue, inspect the real loop, and download a reviewable diff.

**Architecture:** Extend the existing durable command and workflow substrate with a repository-task kind. Freeze tracked source from a Git commit into an isolated workspace, process tasks in accepted order using the existing single admission slot, and package a host-generated diff with actual check receipts. Serve a React console from the Spring Boot API origin. Keep the benchmark evaluator and API behavior intact.

**Tech Stack:** Java 21, Spring Boot 4.1.1, PostgreSQL/Flyway, Temporal 1.36.1, Docker runner, React/TypeScript/Vite, Gradle wrapper, Node 22/npm 10 for frontend builds.

**Spec:** [Work queue and loop console design](../specs/2026-10-12-work-queue-loop-console-design.md)

## Global constraints

- Work in the primary checkout on `feat/work-queue-loop-console`; integrate only through a PR. Never apply generated changes to the live checkout automatically.
- Preserve benchmark routes, trusted evaluator fixtures, immutable profile revisions, command idempotency, and explicit uncertain outcomes.
- Pin every repository task to a full local `main` commit and a source snapshot digest. Exclude `.git`, `.secrets`, private evaluator fixtures, generated files, and non-text source from model visibility.
- Import at most 512 text files, 8 MiB total, and 256 KiB per file. Editable paths are `src/main/java/**/*.java`, `src/test/java/**/*.java`, `src/main/resources/**/*.yaml`, `src/main/resources/db/migration/*.sql`, `web/src/**/*.{ts,tsx,css}`, `scripts/**/*.py`, `docs/**/*.md`, and `README.md`. Reject tasks that require changes outside this first-version allowlist rather than pretending they are supported.
- Permit one admitted task at a time; oldest accepted eligible start wins. Browser state is a projection and must reveal stale or unavailable authority.
- Keep focused feature checks. Do not add recovery, race, or deployment test suites. An explicitly requested local Ollama run is outside the normal build.
- Name the new migration for its schema purpose and verify its Flyway filename and journal description before commit.

## Review focus

1. A repeated create command after a lost response returns the same work item; a changed payload with that ID conflicts. Cover in Task 1's API check.
2. A task whose base ref moves after acceptance still runs against its recorded full commit and snapshot. Cover in Task 2's importer check.
3. A queued cancellation cannot later become active. Cover in Task 1's queue check.
4. A model claiming success after failed checks produces **Ready for review** with failed checks, never a benchmark pass. Cover in Task 2's scripted run.
5. A browser reconnect displays each ordered event once and marks uncertain progress as stale. Cover in Task 4's browser walkthrough.

## File map

- `V7__create_repository_work_items.sql`: repository task metadata, kind discriminator, queue order, and review artifact references; retain existing `run_request`/`run_command` identities and relationships.
- `RepositorySourceImporter.java`: bounded, read-only Git-tree import at a full SHA; never read dirty checkout content.
- `RepositoryWorkService.java` and `WorkController.java`: create/list/detail/cancel API, stable command IDs, queue position, and redacted views.
- `RepositoryWorkspacePolicy.java` plus focused changes to `JdbcWorkspaceStore`: larger repository snapshots and edit allowlist separate from the benchmark policy.
- `RepositoryTaskWorkflow.java`, activities, and dispatcher branch: one-task execution using the existing admission and model receipt rules.
- Repository runner manifest/image changes: host-owned, offline Gradle check with candidate source staged into a network-disabled container.
- `ReviewArtifactService.java`: deterministic diff and check summary from frozen base and final snapshots.
- `web/`: React/TypeScript client; production output packaged under `static/` in the Boot JAR.
- `docs/STATUS.md`, `README.md`, and `docs/development.md`: truthful task order, startup, limits, and local evidence.

## Task 1: Durable work submission and FIFO queue

**Files:** Create `src/main/resources/db/migration/V7__create_repository_work_items.sql`, `src/main/java/com/brianmehrman/harness/runs/{RepositoryWorkService,RepositorySourceImporter}.java`, `src/main/java/com/brianmehrman/harness/api/WorkController.java`; modify `RunCommandService`, `RunQueryService`, `RunAdmissionStore`, `CommandDispatcher`; add one focused `WorkApiIT`.

**Interfaces:** `POST /api/work` accepts `{commandId,title,instructions,profileRevision}`. `GET /api/work` returns active, queued, and recent terminal summaries. `GET /api/work/{id}` returns task metadata and command state. `POST /api/work/{id}/cancel` accepts `{commandId,expectedVersion}`. `RepositorySourceImporter.capture(String baseRef)` returns full commit SHA and bounded source map; the service freezes both before accepting work.

```java
record CreateWorkRequest(String commandId, String title, String instructions, String profileRevision) {}
record CapturedSource(String commitSha, SortedMap<String, String> files, String snapshotSha256) {}
// Repeating an identical commandId returns the original workId; changing its payload throws Conflict.
WorkAccepted create(CreateWorkRequest request);
```

- [x] **Step 1: Define the migration and request records.** Add a `repository_work_item` row keyed by existing `run_id`, with title, instruction blob ID, base commit, seed digest, review status, and accepted sequence/time. Add a run-kind discriminator without rewriting prior benchmark rows. Verify the migration filename and Flyway description are `create repository work items`.
- [x] **Step 2: Write one API integration check.** The check submits two distinct command IDs, repeats the first request, asserts the same ID, changes its payload and asserts conflict, observes one active and one queued in acceptance order, cancels the queued item, and verifies it never gains the slot. Use a scripted gateway and the existing isolated database schema pattern.
- [x] **Step 3: Implement the host-side API and queue.** Reuse transactional command acceptance and `run_request` identity. Resolve `main` to a full commit before insert; return `400/409/503` for invalid input, conflicting identity, or unavailable local Git/profile services. Select the oldest accepted eligible item when admission is free; keep benchmark starts visibly tagged.
- [x] **Step 4: Run the focused API check and `./gradlew test --offline`.** Inspect the actual discovered test count and failure report. Commit the migration and API slice after `git diff --check`.

## Task 2: Isolated repository editing and review result

**Files:** Create `RepositoryWorkspacePolicy`, `RepositoryTaskWorkflow`, `RepositoryTaskActivities`, `ReviewArtifactService`; modify `JdbcWorkspaceStore`, `ToolRouter`, `OllamaModelAdapter`, runner manifests/image, and dispatcher. Add one scripted `RepositoryTaskIT`.

**Interfaces:** A repository task consumes its frozen source snapshot, title/instructions, profile revision, and limits. It exposes `list_files`, `read_file`, `write_file`, `run_tests`, `build`, and `submit`. `submit` stores a model summary and final snapshot; host-generated review artifacts include a unified diff, base/final digests, and every check receipt. The terminal status is `READY_FOR_REVIEW`, not `SUCCEEDED`.

```java
record ReviewBundle(String baseCommit, String seedSha256, String finalSha256,
        String diffBlobId, List<CheckReceipt> checks, String modelSummary) {}
// The host constructs this from persisted snapshots and receipts, not model prose.
ReviewBundle packageReview(String workId, String submittedSnapshotSha256, String summary);
```

- [ ] **Step 1: Make source capture safe.** Read tracked files from `git archive` of the resolved full SHA using argument-list process execution and bounded tar decoding. Reject links, binary data, path escapes, files over 256 KiB, more than 512 files, and total text over 8 MiB. Import only the global-constraint paths; exclude private fixtures, credentials, build outputs, and generated trees. Preserve the current benchmark's 64-file/1-MiB policy separately. Check that moving `main` after capture leaves the task's committed tree unchanged.
- [ ] **Step 2: Write one scripted execution check.** The scripted model reads a file, writes a small allowed edit, invokes a check, and submits. Assert the diff contains only that edit, the live checkout bytes are unchanged, and the status is `READY_FOR_REVIEW`. Make the check command fail once and assert the failure remains visible in the review bundle rather than becoming a pass claim.
- [ ] **Step 3: Implement repository execution.** Branch by run kind at command dispatch. Reuse durable model attempts, bounded loop limits, cancellation, and one admission slot. Give the model repository instructions and a distinct tool contract. Run trusted offline Gradle commands from a pinned image against an isolated snapshot; never run candidate code on the host or use candidate-provided build settings/arguments. Store the model's submission separately from host check receipts.
- [ ] **Step 4: Generate review artifacts.** Diff the frozen seed and submitted snapshot on the host with stable path ordering and bounded output; include new/modified/deleted file entries supported by the write policy. Associate downloads with this work ID and serve attachments with `nosniff`. Preserve last snapshot and failure evidence for incomplete tasks.
- [ ] **Step 5: Run the scripted task check and relevant unit checks.** Inspect image/container cleanup and the diff. Commit the execution slice once the main behavior is verified.

## Task 3: Honest loop progress API

**Files:** Modify `RepositoryTaskActivities`, event projection/query code, and `WorkController`; add focused assertions to `RepositoryTaskIT` rather than another test class.

**Interfaces:** `GET /api/work/{id}/events?after=N` returns contiguous `{sequence,type,stage,summary,artifactDigest}` items. Detail exposes `projection.stale`, active stage, actual check receipts, and pending/delivered/applied command states.

```java
record WorkEvent(long sequence, String type, String stage, String summary, String artifactDigest) {}
List<WorkEvent> events(String workId, long after); // sequence > after, contiguous, at most 200
```

- [ ] **Step 1: Record semantic transitions.** Publish model-call, tool/edit, check, submission, and terminal events only after their durable receipts exist. Keep payloads bounded and avoid returning prompts, secrets, raw source, and raw logs in list responses.
- [ ] **Step 2: Query progress safely.** Return events only through the contiguous projection cursor; retain a stale indicator when Temporal cannot confirm authority. Distinguish accepted, delivered, admitted, running, and terminal states.
- [ ] **Step 3: Extend the scripted check.** Assert event sequence and stage correspond to the real tool calls, and that an `after` cursor yields only subsequent events. Run the focused check and commit.

## Task 4: Local React console

**Files:** Create `web/package.json`, `web/package-lock.json`, `web/vite.config.ts`, `web/tsconfig.json`, `web/src/App.tsx`, `web/src/api.ts`, `web/src/styles.css`; modify `build.gradle` to package built assets. Update `README.md` and `docs/development.md`.

**Interfaces:** One same-origin page at `/` with Add work, Working now, Queue, History, and detail. The detail renders the fixed loop and ordered events, a reviewable diff link, check outcomes, and cancel. Browser requests use the repository-work API only; benchmark runs are visibly marked and remain accessible through existing routes.

```ts
type Stage = 'queued' | 'model' | 'tool' | 'check' | 'submit' | 'review' | 'terminal';
type WorkEvent = { sequence: number; type: string; stage: Stage; summary: string };
// Keep the last confirmed cursor across refresh, and append only events with a higher sequence.
```

- [ ] **Step 1: Pin the frontend dependencies and build.** Use React/TypeScript/Vite with a committed lockfile. Build once into Boot static resources; a production JAR must serve the UI without a separate Vite server. No CDN or remote script at runtime.
- [ ] **Step 2: Implement the primary page.** Show the selected full base commit, verified profile, task form, active task, FIFO queue with positions, and terminal history. Disable controls during a pending mutation and keep the command ID stable for a retry after a lost response.
- [ ] **Step 3: Implement detail and loop view.** Poll events by sequence, deduplicate on reconnect, and display a text equivalent of the highlighted loop stage. Show projection stale/authority unavailable, check failures, submission summary, diff download, and cancellation delivery/application separately. Label unknown usage as unknown.
- [ ] **Step 4: Verify packaging and navigation.** Run the pinned frontend build and confirm the Boot JAR serves `/` and its assets from loopback. Inspect keyboard navigation and readable stage labels. Commit the UI slice; the real browser journey is Task 5.

## Task 5: Local use proof and PR

**Files:** Update `docs/STATUS.md`, `README.md`, `docs/development.md`, and a concise `docs/evidence/repository-work-console.md`.

- [ ] **Step 1: Start only the required local services and build the packaged application.** Use the selected installed Ollama model and the pinned runner image. Record exact commands, date, commit, model digest, run ID, elapsed time, and any task-owned resources.
- [ ] **Step 2: Run one real browser journey.** Submit a free-text repository task through the UI, queue and cancel a second task, refresh detail mid-run, and inspect active/queued order. Confirm the first task reaches a terminal state, shows its actual loop, and yields a downloadable diff and check receipts. Record a failed outcome truthfully and diagnose from evidence; do not hand-edit generated source to claim success.
- [ ] **Step 3: Run relevant final checks.** Run `./gradlew test --offline` and one uncached `./gradlew build --offline --rerun-tasks` with required services and pinned image. Inspect reports, final diff, staged files, sensitive additions, link targets, and `git status`. Stop task-owned services/containers, preserving database volumes.
- [ ] **Step 4: Push the branch and open a PR.** Write the PR body with Summary, Evidence, and Merge Danger; verify the remote head SHA and open state. Leave merging to the owner. Keep the original Task 10 live task-tracker benchmark separately named and unclaimed.
