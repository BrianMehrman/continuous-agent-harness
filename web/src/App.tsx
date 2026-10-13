import { useEffect, useRef, useState } from 'react';
import { api, type Profile, type WorkDetail, type WorkEvent, type WorkList, type WorkSummary } from './api';

const stages = ['Queued', 'Model', 'Tool', 'Check', 'Submit', 'Review', 'Terminal'];
const terminal = new Set(['READY_FOR_REVIEW', 'SUCCEEDED', 'FAILED', 'CANCELLED', 'EXPIRED', 'LIMIT_EXHAUSTED', 'INFRASTRUCTURE_FAILED', 'CONTEXT_OVERFLOW']);
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
  const [list, setList] = useState<WorkList>({ active: null, benchmarkActive: null, queue: [], history: [] });
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

  const currentStage = detail?.status === 'QUEUED' ? 0 : detail?.status === 'READY_FOR_REVIEW' ? 5 :
    Math.max(0, stages.findIndex(stage => stage.toLowerCase() === events.at(-1)?.stage));

  return <div className="shell">
    <header><div className="brand"><span className="mark">◈</span><div><strong>Continuous Agent Harness</strong><small>Local work console</small></div></div><span className="local-badge">LOCAL · ONE ACTIVE TASK</span></header>
    <main>
      <div className="intro"><div><span className="eyebrow">WORKSPACE / REPOSITORY</span><h1>Work queue</h1><p>Give the local agent a repository task. Changes stay in isolated snapshots until you review the diff.</p></div><div className="count"><strong>{list.queue.length}</strong><span>waiting</span></div></div>
      {error && <div className="alert" role="alert">{error}</div>}
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
          <section className="panel"><div className="panel-heading"><h2>Queue</h2><span>{list.queue.length} WAITING</span></div>{list.queue.length ? list.queue.map(item => <WorkRow key={item.workId} work={item} select={choose} now={now}/>) : <p className="empty">Nothing waiting. Add a task above to get started.</p>}</section>
          <section className="panel"><div className="panel-heading"><h2>History</h2><span>RECENT</span></div>{list.history.length ? list.history.map(item => <WorkRow key={item.workId} work={item} select={choose} now={now}/>) : <p className="empty">Finished work will appear here.</p>}</section>
        </div>
        <aside className="panel detail"><div className="panel-heading"><h2>Task detail</h2><span>{detail ? short(detail.workId) : 'SELECT A TASK'}</span></div>
          {detail && selected === detail.workId ? <>
            <h3>{detail.title}</h3><div className="status-line"><span className="status-pill">{detail.status.replaceAll('_',' ')}</span>{detail.stale && <span className="warning">No recent confirmed update · view may be stale</span>}</div>
            <dl><dt>Base commit</dt><dd>{detail.baseCommit}</dd><dt>Model profile</dt><dd>{detail.profileRevision}</dd><dt>Seed snapshot</dt><dd title={detail.seedSnapshotSha256}>{short(detail.seedSnapshotSha256)}</dd></dl>
            <h4>Loop</h4><div className="loop" role="list" aria-label="Coding loop">{stages.map((stage, index) => <div role="listitem" key={stage} className={`stage ${index === currentStage ? 'current' : ''}`}><span>{String(index + 1).padStart(2,'0')}</span>{stage}</div>)}</div>
            <h4>Activity</h4>{events.length ? <ol className="events">{events.map(e => <li key={e.sequence}><span>{e.sequence}</span>{e.summary}</li>)}</ol> : <p className="empty">Waiting for the first durable event.</p>}
            <h4>Checks</h4>{detail.checks.length > 0 ? <ol className="events">{detail.checks.map(check => <li key={check.invocationId}>{check.operation.toLowerCase()} · {check.status.toLowerCase()}{check.exitCode !== null ? ` · exit ${check.exitCode}` : ''}{check.uncertain ? ' · uncertain' : ''}{check.forFinalSnapshot ? '' : ' · earlier snapshot'}{check.logBlobId && <> · <a href={`/api/runs/${encodeURIComponent(detail.workId)}/artifacts/${check.logBlobId}`}>log ↗</a></>}</li>)}</ol> : <p className="empty">No host check receipts recorded.</p>}
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
