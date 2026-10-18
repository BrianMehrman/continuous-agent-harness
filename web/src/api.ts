export type WorkSummary = { workId: string; title: string; baseCommit: string; profileRevision: string; status: string; version: number; deliveryStatus: string; createdAt: string; updatedAt: string; startedAt: string | null; position: number };
export type Pause = { reason: string; signal?: string | null; sinceTurn?: number | null; evidence: string[]; note?: string | null; turnsUsed: number; turnsGranted: number; toolCallsUsed?: number; toolCallsGranted?: number; activeMillisUsed?: number; activeMillisGranted?: number; waitUntil?: string | null; summary: string };
export type Resume = { commandId: string; extraTurns: number; extraToolCalls: number; extraMinutes: number; hint: string | null; summary: string };
export type WorkList = { active: WorkSummary | null; benchmarkActive: { runId: string; status: string; startedAt: string; updatedAt: string } | null; queue: WorkSummary[]; history: WorkSummary[]; paused: WorkSummary[]; pausedRuns: { runId: string; status: string; updatedAt: string }[] };
export type WorkDetail = { workId: string; title: string; instructions: string; baseCommit: string; seedSnapshotSha256: string; finalSnapshotSha256: string | null; profileRevision: string; status: string; version: number; stale: boolean; lastSequence: number; reviewBlobId: string | null; modelSummary: string | null; deliveryStatus: 'ACCEPTED' | 'DELIVERED'; cancellations: {commandId: string; deliveryStatus: string; applicationOutcome: string}[]; checks: {invocationId: string; operation: string; snapshotSha256: string; status: string; exitCode: number | null; uncertain: boolean; logBlobId: string | null; forFinalSnapshot: boolean}[]; pause: Pause | null; resumes: Resume[]; nudges: number; label: 'assisted' | 'extended' | null };
export type RunDetail = { projection: { runId: string; version: number; status: string; snapshotSha256: string; lastSequence: number; stale: boolean; updatedAt: string; terminalResultBlobId: string | null }; commands: { commandId: string; kind: string; deliveryStatus: string; applicationOutcome: unknown; lastErrorCode: string | null }[]; pause: Pause | null; resumes: Resume[]; nudges: number; label: 'assisted' | 'extended' | null };
export type ResumeBody = { commandId: string; expectedVersion: number; extraTurns: number; extraToolCalls: number; extraMinutes: number; hint?: string };
export type WorkEvent = { sequence: number; type: string; stage: string; summary: string; artifactDigest: string | null };
export type Profile = { id: string; modelTag: string; fullDigest: string; toolRoundTripVerified: boolean };

export class ApiError extends Error { constructor(message: string, readonly status: number) { super(message); } }

async function request<T>(path: string, init?: RequestInit): Promise<T> {
  const response = await fetch(path, { ...init, headers: { 'Content-Type': 'application/json', ...init?.headers } });
  if (!response.ok) {
    let message = `${response.status} ${response.statusText}`;
    try { const body = await response.json(); message = body.message ?? body.detail ?? message; } catch { /* no JSON body */ }
    throw new ApiError(message, response.status);
  }
  return response.json() as Promise<T>;
}

export const api = {
  list: () => request<WorkList>('/api/work'),
  base: () => request<{commitSha: string; snapshotSha256: string}>('/api/work/base'),
  detail: (id: string) => request<WorkDetail>(`/api/work/${encodeURIComponent(id)}`),
  profiles: () => request<Profile[]>('/api/profiles'),
  probe: (modelTag: string) => request<Profile>('/api/profiles/probe', { method: 'POST', body: JSON.stringify({ modelTag }) }),
  create: (body: { commandId: string; title: string; instructions: string; profileRevision: string }) => request<{workId: string}>('/api/work', { method: 'POST', body: JSON.stringify(body) }),
  cancel: (id: string, commandId: string, expectedVersion: number) => request<unknown>(`/api/work/${encodeURIComponent(id)}/cancel`, { method: 'POST', body: JSON.stringify({ commandId, expectedVersion }) }),
  resume: (id: string, body: ResumeBody) => request<unknown>(`/api/work/${encodeURIComponent(id)}/resume`, { method: 'POST', body: JSON.stringify(body) }),
  stop: (id: string, commandId: string, expectedVersion: number) => request<unknown>(`/api/work/${encodeURIComponent(id)}/stop`, { method: 'POST', body: JSON.stringify({ commandId, expectedVersion }) }),
  run: (id: string) => request<RunDetail>(`/api/runs/${encodeURIComponent(id)}`),
  resumeRun: (id: string, body: ResumeBody) => request<unknown>(`/api/runs/${encodeURIComponent(id)}/resume`, { method: 'POST', body: JSON.stringify(body) }),
  stopRun: (id: string, commandId: string, expectedVersion: number) => request<unknown>(`/api/runs/${encodeURIComponent(id)}/stop`, { method: 'POST', body: JSON.stringify({ commandId, expectedVersion }) }),
  events: (id: string, after: number) => request<WorkEvent[]>(`/api/work/${encodeURIComponent(id)}/events?after=${after}`),
};
