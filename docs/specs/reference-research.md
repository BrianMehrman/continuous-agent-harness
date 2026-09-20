# Reference research

Reviewed: 2026-09-20

Scope: read-only source inspection and official documentation. Neither reference application was started or tested during this review.

## rails-llm-demo

Location: `/Users/brianmehrman/projects/rails-llm-demo`

| Evidence | Observation | Architectural implication |
|---|---|---|
| `app/services/llm_client.rb` | Reads endpoint and model from environment; calls `/chat/completions` | Separate endpoint placement from request protocol and run logic |
| Same file | Uses non-streaming requests and does not set an authorization header | Add credential-aware adapters; do not assume compatibility with authenticated remote services |
| Same file | Emits model span, duration histogram, and usage counters when supplied | Measure model calls at the adapter boundary; preserve unknown usage |
| `app/jobs/llm_response_job.rb` | Runs inference in a job, persists output, broadcasts UI updates | Browser lifetime should not own task lifetime |
| `config/initializers/opentelemetry.rb`, `lograge.rb` | Instruments HTTP/database activity and correlates JSON logs with trace IDs | Observe the application as well as agent calls |
| `docs/observability.md` | Describes Jaeger, Prometheus, Loki, Grafana, and failure/recovery demo scenarios | Reuse the three-signal approach and operational scenarios without requiring Kubernetes |

The request client is useful evidence of endpoint substitution, but the inspected code is not proof of authenticated remote-provider support. Error bodies can flow into application errors; the new harness should sanitize diagnostic payloads. Deployment chart contents and all remote compatibility paths have not been audited.

## remote-agentic-coding-system

Location: `/Users/brianmehrman/projects/remote-agentic-coding-system`

| Evidence | Observation | Architectural implication |
|---|---|---|
| `src/types/index.ts` | Separate platform and assistant interfaces, streaming message union | Decouple operator transport from runtime execution |
| `src/clients/factory.ts` | Chooses Claude or Codex assistant clients | Agent runtime choice is distinct from inference endpoint choice |
| `src/orchestrator/orchestrator.ts` | Routes commands, selects client, creates/resumes stored sessions | Preserve explicit run/session identities and command handling |
| `migrations/001_initial_schema.sql` | Stores codebases, conversations, and assistant session references | Persistence of an external session ID alone does not establish durable workflow recovery |
| `src/utils/conversation-lock.ts` | Uses in-memory maps, promises, and message queues | Restart loses pending work; do not reuse this as a durable queue |
| `src/clients/claude.ts` | Bypasses tool permissions and forwards the process environment | Use explicitly scoped tools and credentials in the harness |
| `src/clients/codex.ts` | Maps selected events and may start a fresh thread after resume failure | Preserve terminal errors and expose discontinuities instead of presenting a new session as resumed work |

The shared event interface omits some provider-native events. A harness adapter should expose supported lifecycle information and report limitations explicitly. These observations describe this local checkout, not current upstream SDK guarantees.

## Official sources

- [LangGraph overview](https://docs.langchain.com/oss/javascript/langgraph/overview): a stateful orchestration runtime supporting deterministic and agentic steps. Candidate for graphs and persistent execution.
- [Temporal workflow execution](https://docs.temporal.io/workflow-execution): durable execution with recoverable workflow state. Candidate when crash recovery and long waits become required.
- [OpenTelemetry signals](https://opentelemetry.io/docs/concepts/signals/): traces, metrics, and logs provide complementary diagnostic signals. The proposed telemetry boundary uses these concepts.
- [Ollama OpenAI compatibility](https://docs.ollama.com/api/openai-compatibility): compatibility is API-specific. Verify the chosen model and endpoint against the required tool/message behavior.

These sources informed architectural options; no dependency versions or runtime compatibility have been validated. Pin and verify actual dependencies when the stack is selected.

## Synthesis

Use the Rails project's application observability and endpoint configuration as reference patterns. Use the remote coding project's separation of communication and assistant execution as a second reference. Add explicit durable run state, capability checks, configuration revisions, and independently verified outcomes.

The first implementation should own a small bounded loop. Reassess execution-engine adoption before adding recovery and graphs. This recommendation is an architectural judgment, not a finding that either reference implements the proposed harness.
