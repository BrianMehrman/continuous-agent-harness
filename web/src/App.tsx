import { useEffect, useRef, useState } from 'react';
import { api, ApiError, type Pause, type Profile, type Resume, type RunDetail, type WorkDetail, type WorkEvent, type WorkList, type WorkSummary } from './api';

const stages = ['Queued', 'Model', 'Tool', 'Check', 'Submit', 'Review', 'Paused', 'Terminal'];
const terminal = new Set(['READY_FOR_REVIEW', 'SUCCEEDED', 'FAILED', 'CANCELLED', 'EXPIRED', 'LIMIT_EXHAUSTED', 'INFRASTRUCTURE_FAILED', 'CONTEXT_OVERFLOW', 'STALLED']);
const freshId = () => crypto.randomUUID();
const short = (value: string) => value.slice(0, 12);

function elapsed(since: string, now: number) {
  const seconds = Math.max(0, Math.floor((now - Date.parse(since)) / 1000));
  return seconds < 60 ? `${seconds}s` : `${Math.floor(seconds / 60)}m ${seconds % 60}s`;
}

function WorkRow({ work, select, now, active = false }: { work: WorkSummary; select: (id: string) => void; now: number; active?: boolean }) {
  return <button className="work-row" onClick={() => select(work.workId)}>
    <span><strong>{work.title}</strong><small>{short(work.baseCommit)} · {work.profileRevision}</small>{active && <small>Running {elapsed(work.startedAt ?? work.createdAt, now)} · updated {elapsed(work.updatedAt, now)} ago</small>}</span>
    <span className="row-status">{work.position ? `#${work.position} in queue` : work.status.replaceAll('_', ' ')}<small>{work.deliveryStatus.toLowerCase()}</small></span>
  </button>;
}

type Extras = { turns: number; tools: number; minutes: number; hint: string };
const defaultExtras: Extras = { turns: 20, tools: 50, minutes: 15, hint: '' };

function PausePanel({ pause, busy, onResume, onStop, onEdit }: { pause: Pause; busy: boolean; onResume: (extras: Extras) => Promise<boolean>; onStop: () => void; onEdit: () => void }) {
  const [extra, setExtra] = useState(defaultExtras);
  const edit = (change: Partial<Extras>) => { setExtra({ ...extra, ...change }); onEdit(); };
  const submit = async (event: React.FormEvent) => { event.preventDefault(); if (await onResume(extra)) setExtra(defaultExtras); };
  const stop = () => { if (window.confirm('Stop this run? It ends and cannot be resumed.')) onStop(); };
  return <section className="pause-panel" aria-label="Paused run">
    <h4>Paused · {pause.reason.replaceAll('_', ' ').toLowerCase()}{pause.signal ? ` (${pause.signal.replaceAll('_', ' ').toLowerCase()})` : ''}</h4>
    {pause.evidence.length > 0 && <ol className="evidence">{pause.evidence.map((line, i) => <li key={i}>{line}</li>)}</ol>}
    {pause.note && <p className="instructions">{pause.note}</p>}
    <p className="instructions">Turns {pause.turnsUsed}/{pause.turnsGranted} · tool calls {pause.toolCallsUsed}/{pause.toolCallsGranted} · active {Math.round(pause.activeMillisUsed / 60000)}/{Math.round(pause.activeMillisGranted / 60000)} min{pause.waitUntil ? ` · waits until ${new Date(pause.waitUntil).toLocaleString()}` : ''}</p>
    <form onSubmit={submit}>
      <label>Extra turns (max 40)<input type="number" min={0} max={40} value={extra.turns} onChange={e => edit({ turns: Number(e.target.value) })} /></label>
      <label>Extra tool calls (max 100)<input type="number" min={0} max={100} value={extra.tools} onChange={e => edit({ tools: Number(e.target.value) })} /></label>
      <label>Extra minutes (max 30)<input type="number" min={0} max={30} value={extra.minutes} onChange={e => edit({ minutes: Number(e.target.value) })} /></label>
      <label>Hint for the agent (optional; marks the run assisted)<textarea maxLength={2000} rows={3} value={extra.hint} onChange={e => edit({ hint: e.target.value })} /></label>
      <div className="form-footer"><button className="primary" disabled={busy}>Resume</button><button type="button" className="secondary" onClick={stop} disabled={busy}>Stop</button></div>
    </form>
  </section>;
}

function ResumeHistory({ resumes, label }: { resumes: Resume[]; label: 'assisted' | 'extended' | null }) {
  if (!resumes.length) return null;
  return <><h4>Resume history {label && <span className="status-pill">{label}</span>}</h4><ol className="events">{resumes.map(r => <li key={r.commandId}>+{r.extraTurns} turns · +{r.extraToolCalls} tool calls · +{r.extraMinutes} min{r.hint ? ` · hint: ${r.hint}` : ''}</li>)}</ol></>;
}

export default function App() {
  const [list, setList] = useState<WorkList>({ active: null, benchmarkActive: null, queue: [], history: [], paused: [], pausedRuns: [] });
  const [profiles, setProfiles] = useState<Profile[]>([]);
  const [selected, setSelected] = useState<string | null>(null);
  const [detail, setDetail] = useState<WorkDetail | null>(null);
  const [selectedRun, setSelectedRun] = useState<string | null>(null);
  const [runDetail, setRunDetail] = useState<RunDetail | null>(null);
  const [events, setEvents] = useState<WorkEvent[]>([]);
  const [title, setTitle] = useState('');
  const [instructions, setInstructions] = useState('');
  const [profile, setProfile] = useState('');
  const [modelTag, setModelTag] = useState('');
  const [base, setBase] = useState<string>('');
  const [pendingId, setPendingId] = useState(freshId);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const [now, setNow] = useState(Date.now());
  const selectedRef = useRef<string | null>(null);
  const selectedRunRef = useRef<string | null>(null);
  const cursor = useRef(0);
  const pendingCancel = useRef<{ id: string; version: number } | null>(null);
  const pendingControl = useRef<{ id: string; version: number; kind: string } | null>(null);

  useEffect(() => {
    api.profiles().then(items => { setProfiles(items); setProfile(current => current || items.find(p => p.toolRoundTripVerified)?.id || ''); }).catch(e => setError(String(e)));
    api.base().then(item => setBase(item.commitSha)).catch(e => setError(String(e)));
    const params = new URLSearchParams(location.search);
    const saved = params.get('work');
    const savedRun = params.get('run');
    if (saved) choose(saved); else if (savedRun) chooseRun(savedRun);
    void refresh();
    const timer = setInterval(() => { setNow(Date.now()); void refresh(); }, 2500);
    return () => clearInterval(timer);
  }, []);

  function choose(id: string) {
    pendingCancel.current = null;
    pendingControl.current = null;
    selectedRef.current = id;
    selectedRunRef.current = null;
    setSelectedRun(null);
    setRunDetail(null);
    cursor.current = 0;
    setEvents([]);
    setSelected(id);
    history.replaceState({}, '', `/?work=${encodeURIComponent(id)}`);
    void refresh(id);
  }

  function chooseRun(id: string) {
    pendingControl.current = null;
    selectedRef.current = null;
    selectedRunRef.current = id;
    setSelected(null);
    setDetail(null);
    setEvents([]);
    setSelectedRun(id);
    history.replaceState({}, '', `/?run=${encodeURIComponent(id)}`);
    void refresh();
  }

  async function refresh(id = selectedRef.current) {
    try {
      const work = await api.list();
      setList(work);
      if (id) {
        const item = await api.detail(id);
        if (selectedRef.current !== id) return;
        setDetail(item);
        const newer = await api.events(id, cursor.current);
        if (selectedRef.current !== id) return;
        if (newer.length) {
          cursor.current = Math.max(cursor.current, ...newer.map(e => e.sequence));
          setEvents(old => [...old, ...newer.filter(e => !old.some(previous => previous.sequence === e.sequence))].sort((a, b) => a.sequence - b.sequence));
        }
      }
      const runId = selectedRunRef.current;
      if (runId) {
        try {
          const item = await api.run(runId);
          if (selectedRunRef.current !== runId) return;
          setRunDetail(item);
        } catch (failure) {
          if (!(failure instanceof ApiError && failure.status === 404)) throw failure;
          if (selectedRunRef.current !== runId) return;
          selectedRunRef.current = null;
          setSelectedRun(null);
          setRunDetail(null);
          history.replaceState({}, '', '/');
          setError(`Run ${short(runId)} was not found.`);
          return;
        }
      }
      setError(current => current.startsWith('Connection') ? '' : current);
    } catch (failure) { setError(`Connection or authority unavailable: ${String(failure)}`); }
  }

  async function add(event: React.FormEvent) {
    event.preventDefault();
    if (!profile || busy) return;
    setBusy(true);
    try {
      const result = await api.create({ commandId: pendingId, title: title.trim(), instructions: instructions.trim(), profileRevision: profile });
      setTitle(''); setInstructions(''); setPendingId(freshId()); setError('');
      choose(result.workId);
    } catch (failure) { setError(`Submit failed: ${String(failure)}. Retry will use the same command ID.`); }
    finally { setBusy(false); }
  }

  async function verifyModel() {
    if (!modelTag.trim() || busy) return;
    setBusy(true);
    try {
      const verified = await api.probe(modelTag.trim());
      setProfiles(items => [verified, ...items.filter(item => item.id !== verified.id)]);
      setProfile(verified.id);
      setPendingId(freshId());
      setError('');
    } catch (failure) { setError(`Model verification failed: ${String(failure)}`); }
    finally { setBusy(false); }
  }

  async function cancel() {
    if (!detail || busy) return;
    setBusy(true);
    try {
      const commandId = pendingCancel.current?.version === detail.version ? pendingCancel.current.id : freshId();
      pendingCancel.current = { id: commandId, version: detail.version };
      await api.cancel(detail.workId, commandId, detail.version);
      pendingCancel.current = null;
      await refresh(detail.workId);
    } catch (failure) { setError(`Cancel failed: ${String(failure)}`); }
    finally { setBusy(false); }
  }

  function resetPending() { pendingControl.current = null; }

  async function control(kind: 'resume' | 'stop', extra: Extras = defaultExtras): Promise<boolean> {
    const run = !detail && runDetail ? runDetail : null;
    const target = run ? { id: run.projection.runId, version: run.projection.version } : detail ? { id: detail.workId, version: detail.version } : null;
    if (!target || busy) return false;
    setBusy(true);
    try {
      const same = pendingControl.current?.version === target.version && pendingControl.current.kind === kind;
      const commandId = same ? pendingControl.current!.id : freshId();
      pendingControl.current = { id: commandId, version: target.version, kind };
      if (kind === 'resume') {
        const body = { commandId, expectedVersion: target.version, extraTurns: extra.turns, extraToolCalls: extra.tools, extraMinutes: extra.minutes, ...(extra.hint.trim() ? { hint: extra.hint.trim() } : {}) };
        await (run ? api.resumeRun(target.id, body) : api.resume(target.id, body));
      } else await (run ? api.stopRun(target.id, commandId, target.version) : api.stop(target.id, commandId, target.version));
      pendingControl.current = null;
      await refresh(run ? null : target.id);
      return true;
    } catch (failure) { setError(`${kind === 'resume' ? 'Resume' : 'Stop'} failed: ${String(failure)}`); return false; }
    finally { setBusy(false); }
  }
  const pausePanel = (pause: Pause, key: string) => <PausePanel key={key} pause={pause} busy={busy} onResume={extras => control('resume', extras)} onStop={() => void control('stop')} onEdit={resetPending} />;

  const currentStage = detail?.status === 'QUEUED' ? 0 : detail?.status === 'PAUSED' ? stages.indexOf('Paused') : detail?.status === 'READY_FOR_REVIEW' ? 5 :
    Math.max(0, stages.findIndex(stage => stage.toLowerCase() === events.at(-1)?.stage));

  return <div className="shell">
    <header><div className="brand"><span className="mark">◈</span><div><strong>Continuous Agent Harness</strong><small>Local work console</small></div></div><span className="local-badge">LOCAL · ONE ACTIVE TASK</span></header>
    <main>
      <div className="intro"><div><span className="eyebrow">WORKSPACE / REPOSITORY</span><h1>Work queue</h1><p>Give the local agent a repository task. Changes stay in isolated snapshots until you review the diff.</p></div><div className="count"><strong>{list.queue.length}</strong><span>waiting</span></div></div>
      {error && <div className="alert" role="alert">{error}</div>}
      {(list.paused.length + list.pausedRuns.length) > 0 && <div className="alert paused-banner" role="status">
        {list.paused.length + list.pausedRuns.length} paused · waiting for you:{' '}
        {list.paused.map(item => <button key={item.workId} className="link" onClick={() => choose(item.workId)}>{item.title}</button>)}
        {list.pausedRuns.map(run => <span key={run.runId}><button className="link" onClick={() => chooseRun(run.runId)}>benchmark {short(run.runId)}</button> <a href={`/api/runs/${encodeURIComponent(run.runId)}`}>raw JSON ↗</a></span>)}
      </div>}
      <div className="columns">
        <div className="left">
          <section className="panel"><div className="panel-heading"><h2>Add work</h2><span>FREE TEXT</span></div><form onSubmit={add}>
            <label>Task title<input required maxLength={160} value={title} onChange={e => {setTitle(e.target.value); setPendingId(freshId());}} placeholder="What should be changed?" /></label>
            <label>Instructions<textarea required maxLength={16000} value={instructions} onChange={e => {setInstructions(e.target.value); setPendingId(freshId());}} rows={5} placeholder="Describe the change and how you want it to behave." /></label>
            <label>Verified local model<select value={profile} onChange={e => {setProfile(e.target.value); setPendingId(freshId());}} required><option value="">Select a profile</option>{profiles.filter(p => p.toolRoundTripVerified).map(p => <option key={p.id} value={p.id}>{p.modelTag} · {short(p.fullDigest)}</option>)}</select></label>
            <div className="model-verify"><label>Installed Ollama model tag<input value={modelTag} onChange={e => setModelTag(e.target.value)} placeholder="e.g. qwen3.8:latest" /></label><button type="button" className="secondary" onClick={verifyModel} disabled={busy || !modelTag.trim()}>Verify model</button></div>
            <div className="form-footer"><span>Current main: <code title={base}>{base || 'unavailable'}</code><br/>Rechecked when submitted</span><button className="primary" disabled={busy || !profile || !base}>{busy ? 'Submitting…' : 'Add to queue →'}</button></div>
          </form></section>
          <section className="panel"><div className="panel-heading"><h2>Working now</h2><span>{list.active || list.benchmarkActive ? '1 / 1 SLOT' : 'IDLE'}</span></div>{list.active ? <WorkRow work={list.active} select={choose} now={now} active/> : list.benchmarkActive ? <p className="instructions">Benchmark run <button className="link" onClick={() => chooseRun(list.benchmarkActive!.runId)}>{short(list.benchmarkActive.runId)}</button> <a href={`/api/runs/${encodeURIComponent(list.benchmarkActive.runId)}`}>raw JSON ↗</a> · {list.benchmarkActive.status.replaceAll('_', ' ')}<br/>Running {elapsed(list.benchmarkActive.startedAt, now)} · updated {elapsed(list.benchmarkActive.updatedAt, now)} ago</p> : <p className="empty">No task currently holds the execution slot.</p>}</section>
          <section className="panel"><div className="panel-heading"><h2>Paused</h2><span>{list.paused.length} WAITING</span></div>{list.paused.length ? list.paused.map(item => <WorkRow key={item.workId} work={item} select={choose} now={now}/>) : <p className="empty">No paused work.</p>}</section>
          <section className="panel"><div className="panel-heading"><h2>Queue</h2><span>{list.queue.length} WAITING</span></div>{list.queue.length ? list.queue.map(item => <WorkRow key={item.workId} work={item} select={choose} now={now}/>) : <p className="empty">Nothing waiting. Add a task above to get started.</p>}</section>
          <section className="panel"><div className="panel-heading"><h2>History</h2><span>RECENT</span></div>{list.history.length ? list.history.map(item => <WorkRow key={item.workId} work={item} select={choose} now={now}/>) : <p className="empty">Finished work will appear here.</p>}</section>
        </div>
        <aside className="panel detail"><div className="panel-heading"><h2>Task detail</h2><span>{runDetail && selectedRun ? short(runDetail.projection.runId) : detail ? short(detail.workId) : 'SELECT A TASK'}</span></div>
          {runDetail && selectedRun === runDetail.projection.runId ? <>
            <h3>Benchmark run {short(runDetail.projection.runId)}</h3><div className="status-line"><span className="status-pill">{runDetail.projection.status.replaceAll('_',' ')}</span>{runDetail.projection.stale && <span className="warning">No recent confirmed update · view may be stale</span>}</div>
            {runDetail.projection.status === 'PAUSED' && runDetail.pause && pausePanel(runDetail.pause, runDetail.projection.runId)}
            <ResumeHistory resumes={runDetail.resumes} label={runDetail.label} />
            <h4>Commands</h4>{runDetail.commands.length > 0 ? <ol className="events">{runDetail.commands.map(c => <li key={c.commandId}>{c.kind.replaceAll('_', ' ').toLowerCase()} · {c.deliveryStatus.toLowerCase()}{c.applicationOutcome ? ` · ${c.applicationOutcome.replaceAll('_', ' ').toLowerCase()}` : ''}</li>)}</ol> : <p className="empty">No commands recorded.</p>}
            <a className="download" href={`/api/runs/${encodeURIComponent(runDetail.projection.runId)}`}>raw JSON ↗</a>
          </> : !selectedRun && detail && selected === detail.workId ? <>
            <h3>{detail.title}</h3><div className="status-line"><span className="status-pill">{detail.status.replaceAll('_',' ')}</span>{detail.stale && <span className="warning">No recent confirmed update · view may be stale</span>}</div>
            {detail.status === 'PAUSED' && detail.pause && pausePanel(detail.pause, detail.workId)}
            <dl><dt>Base commit</dt><dd>{detail.baseCommit}</dd><dt>Model profile</dt><dd>{detail.profileRevision}</dd><dt>Seed snapshot</dt><dd title={detail.seedSnapshotSha256}>{short(detail.seedSnapshotSha256)}</dd></dl>
            <h4>Loop</h4><div className="loop" role="list" aria-label="Coding loop">{stages.map((stage, index) => <div role="listitem" key={stage} className={`stage ${index === currentStage ? 'current' : ''}`}><span>{String(index + 1).padStart(2,'0')}</span>{stage}</div>)}</div>
            <h4>Activity</h4>{events.length ? <ol className="events">{events.map(e => <li key={e.sequence}><span>{e.sequence}</span>{e.summary}</li>)}</ol> : <p className="empty">Waiting for the first durable event.</p>}
            <h4>Checks</h4>{detail.checks.length > 0 ? <ol className="events">{detail.checks.map(check => <li key={check.invocationId}>{check.operation.toLowerCase()} · {check.status.toLowerCase()}{check.exitCode !== null ? ` · exit ${check.exitCode}` : ''}{check.uncertain ? ' · uncertain' : ''}{check.forFinalSnapshot ? '' : ' · earlier snapshot'}{check.logBlobId && <> · <a href={`/api/runs/${encodeURIComponent(detail.workId)}/artifacts/${check.logBlobId}`}>log ↗</a></>}</li>)}</ol> : <p className="empty">No host check receipts recorded.</p>}
            <ResumeHistory resumes={detail.resumes} label={detail.label} />
            {terminal.has(detail.status) && !detail.checks.some(check => check.forFinalSnapshot) && <p className="warning">The final snapshot has no host check.</p>}
            {detail.modelSummary && <><h4>Model summary</h4><p className="instructions">{detail.modelSummary}</p></>}
            {detail.cancellations.length > 0 && <><h4>Cancellation</h4><p className="instructions">{detail.cancellations.at(-1)?.deliveryStatus.toLowerCase()} · {detail.cancellations.at(-1)?.applicationOutcome}</p></>}
            <h4>Instructions</h4><p className="instructions">{detail.instructions}</p>
            {detail.reviewBlobId && <a className="download" href={`/api/runs/${encodeURIComponent(detail.workId)}/artifacts/${detail.reviewBlobId}`}>Download review diff ↗</a>}
            {!terminal.has(detail.status) && <button className="secondary" onClick={cancel} disabled={busy}>Cancel task</button>}
          </> : <p className="empty">Choose a task from the active, queued, or history lists to see its progress and review result.</p>}
        </aside>
      </div>
    </main>
  </div>;
}
