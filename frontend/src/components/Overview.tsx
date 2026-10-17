import { useCallback, useState } from 'react'
import { api, NodeHealth } from '../api'
import { usePolling } from '../usePolling'

const FAULT_TYPES = ['CRASH', 'ERROR_RATE', 'LATENCY', 'REGION_OUTAGE', 'RESTART_FAILURE', 'ACK_LOSS']
const FAULT_HELP: Record<string, string> = {
  CRASH: 'Node is unavailable until restarted.',
  ERROR_RATE: 'Severity 0-1 = probability of HTTP 500.',
  LATENCY: 'Severity = extra latency in ms (above 1000 ms times out).',
  REGION_OUTAGE: 'Entire region becomes unreachable.',
  RESTART_FAILURE: 'Severity 0-1 = probability a restart fails.',
  ACK_LOSS: 'Severity 0-1 = probability a restart/failover acknowledgement is lost.',
}

function Heartbeat({ recent }: { recent: boolean[] }) {
  const pad = Array.from({ length: Math.max(0, 30 - recent.length) }, () => null)
  return (
    <div className="beat" aria-label="last 30 probes">
      {[...pad, ...recent].map((v, i) => <span key={i} className={v === null ? 'tick none' : v ? 'tick ok' : 'tick bad'} />)}
    </div>
  )
}

function NodeRow({ n }: { n: NodeHealth }) {
  const state = n.healthy === null ? 'unknown' : n.healthy ? 'up' : 'down'
  return (
    <div className={`node ${n.active ? 'serving' : 'standby'}`}>
      <div className="node-main">
        <span className={`dot ${state}`} />
        <div>
          <strong>{n.serviceName}</strong>
          <small>{n.role.toLowerCase()} &middot; {n.active ? 'serving traffic' : 'standby'}</small>
        </div>
        <span className="num">{n.latencyMs === null ? '-' : `${n.latencyMs} ms`}</span>
        <span className="num dim">{n.availabilityPct === null ? '-' : `${n.availabilityPct.toFixed(0)}%`}</span>
      </div>
      <Heartbeat recent={n.recent} />
    </div>
  )
}

export default function Overview({ onOpenIncident }: { onOpenIncident: (id: number) => void }) {
  const services = usePolling(useCallback(() => api.services(), []), 1000)
  const incidents = usePolling(useCallback(() => api.incidents(), []), 1500)
  const policies = usePolling(useCallback(() => api.policies(), []), 4000)
  const faults = usePolling(useCallback(() => api.faults(), []), 2000)

  const [type, setType] = useState('CRASH')
  const [nodeId, setNodeId] = useState<number | ''>('')
  const [region, setRegion] = useState('A')
  const [severity, setSeverity] = useState('')
  const [duration, setDuration] = useState('')
  const [msg, setMsg] = useState<string | null>(null)

  const nodes = services.data ?? []
  const open = (incidents.data?.items ?? []).filter((i) => i.state !== 'RECOVERED')
  const serving = nodes.filter((n) => n.active)
  const up = serving.filter((n) => n.healthy).length

  async function inject() {
    setMsg(null)
    try {
      await api.inject({
        type,
        nodeId: type === 'REGION_OUTAGE' ? null : nodeId === '' ? null : nodeId,
        region: type === 'REGION_OUTAGE' ? region : null,
        severity: severity === '' ? null : Number(severity),
        durationSec: duration === '' ? null : Number(duration),
      })
      faults.refresh()
    } catch (e) { setMsg((e as Error).message) }
  }

  const activePolicy = policies.data?.find((p) => p.active)
    const targetLabel = (id: number) => {
    const n = nodes.find((x) => x.id === id)
    return n ? `${n.serviceName} @ ${n.region} (${n.role.toLowerCase()})` : `node ${id}`
  }
  const brokenStandbys = nodes.filter((n) => !n.active && n.healthy === false)

  return (
    <div className="grid-overview">
      <section className="kpis">
        <div className="kpi"><span>Services serving</span><b>{up}/{serving.length || 3}</b></div>
        <div className="kpi"><span>Open incidents</span><b className={open.length ? 'warn' : ''}>{open.length}</b></div>
        <div className="kpi policy">
          <span>Recovery policy for new incidents</span>
          <select value={activePolicy?.name ?? ''} onChange={async (e) => { await api.setPolicy(e.target.value); policies.refresh() }}>
            {(policies.data ?? []).map((p) => <option key={p.name} value={p.name}>{p.name}</option>)}
          </select>
          <small>{activePolicy?.description}</small>
        </div>
      </section>

      {services.error && <div className="banner bad">
              {brokenStandbys.length > 0 && (
        <div className="banner warnbox">
          Standby down: {brokenStandbys.map((n) => `${n.serviceName} @ ${n.region}`).join(', ')}. Failover is
          unavailable for it, and standbys are not repaired automatically. Use <b>Reset all faults</b> to clear it.
        </div>
      )}
        Backend unreachable: {services.error}</div>}

      <section className="regions">
        {(['A', 'B'] as const).map((r) => (
          <div className="region" key={r}>
            <h2>Region {r} <small>{r === 'A' ? 'primary' : 'backup'}</small></h2>
            {nodes.filter((n) => n.region === r).map((n) => <NodeRow key={n.id} n={n} />)}
          </div>
        ))}
      </section>

      <section className="panel">
        <h2>Fault injection <small>development / test only</small></h2>
        <div className="form-row">
          <label>Fault
            <select value={type} onChange={(e) => setType(e.target.value)}>{FAULT_TYPES.map((t) => <option key={t}>{t}</option>)}</select>
          </label>
          {type === 'REGION_OUTAGE' ? (
            <label>Region<select value={region} onChange={(e) => setRegion(e.target.value)}><option>A</option><option>B</option></select></label>
          ) : (
            <label>Target node
              <select value={nodeId} onChange={(e) => setNodeId(e.target.value === '' ? '' : Number(e.target.value))}>
                <option value="">select...</option>
                {nodes.map((n) => <option key={n.id} value={n.id}>{n.serviceName} @ {n.region}</option>)}
              </select>
            </label>
          )}
          <label>Severity<input value={severity} onChange={(e) => setSeverity(e.target.value)} placeholder="default" /></label>
          <label>Duration (s)<input value={duration} onChange={(e) => setDuration(e.target.value)} placeholder="until fixed" /></label>
        </div>
        <p className="hint">{FAULT_HELP[type]}</p>
        <div className="actions">
          <button className="btn danger" onClick={inject}>Inject fault</button>
          <button className="btn" onClick={async () => { await api.resetFaults(); faults.refresh() }}>Reset all faults</button>
          {msg && <span className="err">{msg}</span>}
        </div>
        <table className="tbl small">
          <thead><tr><th>#</th><th>Type</th><th>Target</th><th>Started (UTC)</th><th>Status</th></tr></thead>
          <tbody>
            {(faults.data ?? []).slice(0, 6).map((f) => (
              <tr key={f.id}>
                <td>{f.id}</td><td>{f.type}</td>
                <td>{f.nodeId ? targetLabel(f.nodeId) : `region ${f.region}`}</td>
                <td className="mono">{f.startedAt.slice(11, 19)}</td>
                <td>{f.endedAt ? `ended (${f.endReason})` : <b className="warn">active</b>}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </section>

      <section className="panel">
        <h2>Open incidents</h2>
        {open.length === 0 && <p className="empty">No unresolved incidents.</p>}
        {open.map((i) => (
          <button key={i.id} className="incident-row" onClick={() => onOpenIncident(i.id)}>
            <span className={`chip ${i.state}`}>{i.state}</span>
            <strong>#{i.id} {i.serviceName}</strong>
            <small>{i.failureType} &middot; {i.policyName}</small>
          </button>
        ))}
      </section>
    </div>
  )
}
