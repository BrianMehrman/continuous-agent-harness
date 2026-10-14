# Work Queue and Loop Console Design

Date: 2026-10-12
Status: Approved conversational design; implementation pending

## Purpose

Make the harness usable for a trusted local operator who wants to submit free-text coding work for this repository, see what is active and queued, inspect the current execution loop, and review the resulting changes. This corrects the current benchmark-only focus. The existing task-tracker benchmark and its trusted evaluator remain available, but a benchmark run is not the only kind of work displayed.

The source for this scope is the owner's 2026-10-12 direction, the original [project overview](../../../README.md), and the [phase-one operator goal](../../specs/phase-1-observable-loop.md). The owner selected free-text repository tasks, one active task at a time, a loop visualization, and reviewable changes without automatic application.

## Operator experience

The home page opens on `127.0.0.1` with four visible areas:

1. **Add work:** Enter a title and free-text coding instructions, select a verified local model profile, and submit. The form shows the repository commit that will be frozen as the task's base. Submission returns an idempotent work ID; a lost response can be retried with the same command ID.
2. **Working now:** Show the admitted task, its current step, elapsed time, last update, and a link to detail. If no task owns the slot, say so. Do not infer activity from a browser connection.
3. **Queue:** Show accepted tasks in durable FIFO order, their position, and whether start delivery is still pending. A queued task can be cancelled. A terminal task leaves the queue without erasing history.
4. **History:** Show terminal tasks and their outcome. A task detail page shows instructions, frozen profile and base commit, ordered events, model/tool attempts where available, build/test results, and downloadable diff and artifacts.

The detail page renders a fixed loop diagram: queued → model → tool/edit → build/test → submit → ready for review, with terminal failure/cancellation branches. Highlight a node only from durable run events or receipts. Show a stale or authority-unavailable indicator when the projection cannot confirm current execution. The diagram visualizes the implemented loop; it is not a graph authoring tool or a claim that branch/join scheduling exists. Poll ordered events after the last sequence and reconnect without duplicating them.

## Repository task execution

A work item is a durable request with its own ID, command ID, title, instructions, selected immutable profile revision, base commit, accepted time, and status. The configured base ref defaults to local `main`; submission resolves it to a full commit SHA and imports tracked UTF-8 files from that Git tree, independently of uncommitted checkout changes. It excludes `.git`, `.secrets`, build outputs, private evaluator fixtures, credentials, and machine-specific model selections. The task records the exact commit and snapshot digest before entering the queue. Invalid or oversized inputs fail before acceptance; the UI shows the limit and reason.

Only the oldest eligible queued start may acquire the single execution slot shared with benchmark runs. Queue order and slot ownership survive API and worker restarts. A queued cancellation prevents admission. The existing benchmark workflow remains a separate run kind, visibly identified as such, and cannot masquerade as a repository task. A repository task has an isolated source snapshot and model conversation using the supplied instructions. Its tools can read and edit permitted source files and run bounded checks in a network-disabled container with trusted build configuration. Model output, tool requests, logs, and file content remain untrusted. No model operation can write to the live checkout, push, or merge.

For this task type, `submit` means the model has finished proposing changes. The host packages a diff from the frozen base snapshot, the final snapshot digest, the model's summary, and actual check receipts. The terminal label is **Ready for review**, not **Succeeded**, because there is no general trusted evaluator for arbitrary instructions. A failed or unverified check remains visible and cannot be converted into a pass by model prose. If the model exhausts limits or fails before submitting, preserve its last snapshot and evidence with the failure reason.

## API and UI boundaries

Add work-list/create/detail/events/cancel and artifact routes for repository tasks, keeping the existing `/api/runs` benchmark contract stable. Expose only bounded, redacted operator data; keep credentials and raw private evaluator material out of JSON. The React console is built into the Spring Boot artifact and uses same-origin requests on the existing loopback binding. Preserve the current host/origin mutation checks and do not enable permissive CORS. The page must work with keyboard navigation, text labels for loop states, and an ordered event list alongside the diagram.

The frontend is a client of durable API state. Closing or refreshing the page does not control task execution. API loss shows a reconnecting state and retains the last confirmed cursor; it never invents progress. The UI must distinguish command accepted, command delivered, work admitted, work running, and work terminal.

## Delivery and verification

Implement the data/API and execution path before relying on the UI. Verify one focused API path that submits two tasks and shows one active plus one queued, then exercises queued cancellation and ordered events. Verify one scripted-model repository task that produces a bounded diff and check receipts without changing the live checkout. Verify one browser flow for submit → queue → active loop → ready for review → diff download. Keep normal tests focused on these main behaviors; do not add crash, race, or deployment-continuity suites. Run one explicitly selected local Ollama task through the console before claiming it usable, and report the actual result without equating model-submitted code with independently verified acceptance.

Package the UI and a clear local startup path in the same PR. Update `docs/STATUS.md` and usage instructions with the observed result and any limits. Push the feature branch and open a PR; the owner merges it.

## Deliberate limits

One repository and one active task. Local Ollama only. No automatic application of generated changes, arbitrary shell tool, remote provider, live operator messages, pause/resume, graph authoring, branches, joins, or multi-agent fleet. The original Task 10 task-tracker live benchmark remains separate acceptance work; this UI task does not claim that benchmark passed.
