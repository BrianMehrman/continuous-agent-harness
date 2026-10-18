import { useEffect, useRef, useState } from 'react';
import { api, type Profile, type WorkDetail, type WorkEvent, type WorkList, type WorkSummary } from './api';

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

export default function App() {
  const [list, setList] = useState<WorkList>({ active: null, benchmarkActive: null, queue: [], history: [], paused: [], pausedRuns: [] });
  const [profiles, setProfiles] = useState<Profile[]>([]);
  const [selected, setSelected] = useState<string | null>(null);
  const [detail, setDetail] = useState<WorkDetail | null>(null);
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
  const cursor = useRef(0);
  const pendingCancel = useRef<{ id: string; version: number } | null>(null);
  const [extra, setExtra] = useState({ turns: 20, tools: 50, minutes: 15, hint: '' });
  const pendingControl = useRef<{ id: string; version: number; kind: string } | null>(null);

  useEffect(() => {
    api.profiles().then(items => { setProfiles(items); setProfile(current => current || items.find(p => p.toolRoundTripVerified)?.id || ''); }).catch(e => setError(String(e)));
    api.base().then(item => setBase(item.commitSha)).catch(e => setError(String(e)));
    const saved = new URLSearchParams(location.search).get('work');
    if (saved) choose(saved);
    void refresh();
    const timer = setInterval(() => { setNow(Date.now()); void refresh(); }, 2500);
    return () => clearInterval(timer);
  }, []);

  function choose(id: string) {
    pendingCancel.current = null;
    pendingControl.current = null;
    selectedRef.current = id;
    cursor.current = 0;
    setEvents([]);
    setSelected(id);
    history.replaceState({}, '', `/?work=${encodeURIComponent(id)}`);
    void refresh(id);
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

  async function control(kind: 'resume' | 'stop') {
    if (!detail || busy) return;
    setBusy(true);
    try {
      const same = pendingControl.current?.version === detail.version && pendingControl.current.kind === kind;
      const commandId = same ? pendingControl.current!.id : freshId();
      pendingControl.current = { id: commandId, version: detail.version, kind };
      if (kind === 'resume') await api.resume(detail.workId, { commandId, expectedVersion: detail.version, extraTurns: extra.turns, extraToolCalls: extra.tools, extraMinutes: extra.minutes, ...(extra.hint.trim() ? { hint: extra.hint.trim() } : {}) });
      else await api.stop(detail.workId, commandId, detail.version);
      pendingControl.current = null;
      setExtra({ turns: 20, tools: 50, minutes: 15, hint: '' });
      await refresh(detail.workId);
    } catch (failure) { setError(`${kind === 'resume' ? 'Resume' : 'Stop'} failed: ${String(failure)}`); }
    finally { setBusy(false); }
  }
  const resume = (event: React.FormEvent) => { event.preventDefault(); void control('resume'); };
  const stop = () => void control('stop');

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
        {list.pausedRuns.map(run => <a key={run.runId} href={`/api/runs/${encodeURIComponent(run.runId)}`}>benchmark {short(run.runId)} ↗</a>)}
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
          <section className="panel"><div className="panel-heading"><h2>Working now</h2><span>{list.active || list.benchmarkActive ? '1 / 1 SLOT' : 'IDLE'}</span></div>{list.active ? <WorkRow work={list.active} select={choose} now={now} active/> : list.benchmarkActive ? <p className="instructions">Benchmark run <a href={`/api/runs/${encodeURIComponent(list.benchmarkActive.runId)}`}>{short(list.benchmarkActive.runId)} ↗</a> · {list.benchmarkActive.status.replaceAll('_', ' ')}<br/>Running {elapsed(list.benchmarkActive.startedAt, now)} · updated {elapsed(list.benchmarkActive.updatedAt, now)} ago</p> : <p className="empty">No task currently holds the execution slot.</p>}</section>
          <section className="panel"><div className="panel-heading"><h2>Paused</h2><span>{list.paused.length} WAITING</span></div>{list.paused.length ? list.paused.map(item => <WorkRow key={item.workId} work={item} select={choose} now={now}/>) : <p className="empty">No paused work.</p>}</section>
          <section className="panel"><div className="panel-heading"><h2>Queue</h2><span>{list.queue.length} WAITING</span></div>{list.queue.length ? list.queue.map(item => <WorkRow key={item.workId} work={item} select={choose} now={now}/>) : <p className="empty">Nothing waiting. Add a task above to get started.</p>}</section>
          <section className="panel"><div className="panel-heading"><h2>History</h2><span>RECENT</span></div>{list.history.length ? list.history.map(item => <WorkRow key={item.workId} work={item} select={choose} now={now}/>) : <p className="empty">Finished work will appear here.</p>}</section>
        </div>
        <aside className="panel detail"><div className="panel-heading"><h2>Task detail</h2><span>{detail ? short(detail.workId) : 'SELECT A TASK'}</span></div>
          {detail && selected === detail.workId ? <>
            <h3>{detail.title}</h3><div className="status-line"><span className="status-pill">{detail.status.replaceAll('_',' ')}</span>{detail.stale && <span className="warning">No recent confirmed update · view may be stale</span>}</div>
            {detail.status === 'PAUSED' && detail.pause && <section className="pause-panel" aria-label="Paused run">
              <h4>Paused · {detail.pause.reason.replaceAll('_', ' ').toLowerCase()}{detail.pause.signal ? ` (${detail.pause.signal.replaceAll('_', ' ').toLowerCase()})` : ''}</h4>
              {detail.pause.evidence.length > 0 && <ol className="events">{detail.pause.evidence.map((line, i) => <li key={i}>{line}</li>)}</ol>}
              {detail.pause.note && <p className="instructions">{detail.pause.note}</p>}
              <p className="instructions">Turns {detail.pause.turnsUsed}/{detail.pause.turnsGranted} · tool calls {detail.pause.toolCallsUsed}/{detail.pause.toolCallsGranted} · active {Math.round(detail.pause.activeMillisUsed / 60000)}/{Math.round(detail.pause.activeMillisGranted / 60000)} min{detail.pause.waitUntil ? ` · waits until ${new Date(detail.pause.waitUntil).toLocaleString()}` : ''}</p>
              <form onSubmit={resume}>
                <label>Extra turns (max 40)<input type="number" min={0} max={40} value={extra.turns} onChange={e => setExtra({ ...extra, turns: Number(e.target.value) })} /></label>
                <label>Extra tool calls (max 100)<input type="number" min={0} max={100} value={extra.tools} onChange={e => setExtra({ ...extra, tools: Number(e.target.value) })} /></label>
                <label>Extra minutes (max 30)<input type="number" min={0} max={30} value={extra.minutes} onChange={e => setExtra({ ...extra, minutes: Number(e.target.value) })} /></label>
                <label>Hint for the agent (optional; marks the run assisted)<textarea maxLength={2000} rows={3} value={extra.hint} onChange={e => setExtra({ ...extra, hint: e.target.value })} /></label>
                <div className="form-footer"><button className="primary" disabled={busy}>Resume</button><button type="button" className="secondary" onClick={stop} disabled={busy}>Stop</button></div>
              </form>
            </section>}
            <dl><dt>Base commit</dt><dd>{detail.baseCommit}</dd><dt>Model profile</dt><dd>{detail.profileRevision}</dd><dt>Seed snapshot</dt><dd title={detail.seedSnapshotSha256}>{short(detail.seedSnapshotSha256)}</dd></dl>
            <h4>Loop</h4><div className="loop" role="list" aria-label="Coding loop">{stages.map((stage, index) => <div role="listitem" key={stage} className={`stage ${index === currentStage ? 'current' : ''}`}><span>{String(index + 1).padStart(2,'0')}</span>{stage}</div>)}</div>
            <h4>Activity</h4>{events.length ? <ol className="events">{events.map(e => <li key={e.sequence}><span>{e.sequence}</span>{e.summary}</li>)}</ol> : <p className="empty">Waiting for the first durable event.</p>}
            <h4>Checks</h4>{detail.checks.length > 0 ? <ol className="events">{detail.checks.map(check => <li key={check.invocationId}>{check.operation.toLowerCase()} · {check.status.toLowerCase()}{check.exitCode !== null ? ` · exit ${check.exitCode}` : ''}{check.uncertain ? ' · uncertain' : ''}{check.forFinalSnapshot ? '' : ' · earlier snapshot'}{check.logBlobId && <> · <a href={`/api/runs/${encodeURIComponent(detail.workId)}/artifacts/${check.logBlobId}`}>log ↗</a></>}</li>)}</ol> : <p className="empty">No host check receipts recorded.</p>}
            {detail.resumes.length > 0 && <><h4>Resume history {detail.label && <span className="status-pill">{detail.label}</span>}</h4><ol className="events">{detail.resumes.map(r => <li key={r.commandId}>+{r.extraTurns} turns · +{r.extraToolCalls} tool calls · +{r.extraMinutes} min{r.hint ? ` · hint: ${r.hint}` : ''}</li>)}</ol></>}
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
