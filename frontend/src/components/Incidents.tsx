import { useCallback, useEffect, useState } from 'react'
import { api } from '../api'
import { usePolling } from '../usePolling'

const time = (s: string | null) => (s ? s.slice(11, 23) : '-')

export default function Incidents({ focusId }: { focusId: number | null }) {
  const list = usePolling(useCallback(() => api.incidents(), []), 1500)
  const [selected, setSelected] = useState<number | null>(focusId)
  useEffect(() => { if (focusId) setSelected(focusId) }, [focusId])
  const detail = usePolling(useCallback(() => (selected ? api.incident(selected) : Promise.resolve(null)), [selected]), 1000)
  const [msg, setMsg] = useState<string | null>(null)

  const d = detail.data
  const canReopen = d && (d.incident.state === 'FAILED' || d.incident.state === 'ESCALATED')

  async function reopen(action?: string) {
    if (!selected) return
    setMsg(null)
    try { await api.reopen(selected, action); detail.refresh(); list.refresh() } catch (e) { setMsg((e as Error).message) }
  }

  return (
    <div className="split">
      <section className="panel list">
        <h2>Incidents <small>{list.data?.total ?? 0} total</small></h2>
        {(list.data?.items ?? []).length === 0 && <p className="empty">None yet. Inject a fault from the Overview tab.</p>}
        {(list.data?.items ?? []).map((i) => (
          <button key={i.id} className={`incident-row ${selected === i.id ? 'on' : ''}`} onClick={() => setSelected(i.id)}>
            <span className={`chip ${i.state}`}>{i.state}</span>
            <strong>#{i.id} {i.serviceName}</strong>
            <small>{i.failureType} &middot; {i.policyName}{i.recoverySec !== null ? ` · recovered in ${i.recoverySec.toFixed(1)}s` : ''}</small>
          </button>
        ))}
      </section>

      <section className="panel detail">
        {!d && <p className="empty">Select an incident to see its timeline, decisions and actions.</p>}
        {d && (
          <>
            <h2>#{d.incident.id} {d.incident.serviceName} <span className={`chip ${d.incident.state}`}>{d.incident.state}</span></h2>
            <p className="hint">{d.incident.summary} &middot; policy {d.incident.policyName} &middot; epoch {d.incident.epoch}</p>
            {canReopen && (
              <div className="actions">
                <button className="btn" onClick={() => reopen()}>Re-open (run policy again)</button>
                <button className="btn" onClick={() => reopen('RESTART')}>Re-open &amp; restart</button>
                <button className="btn" onClick={() => reopen('FAILOVER')}>Re-open &amp; fail over</button>
                {msg && <span className="err">{msg}</span>}
              </div>
            )}

            <h3>State transitions</h3>
            <ol className="timeline">
              {d.transitions.map((t, i) => (
                <li key={i}>
                  <span className="mono">{time(t.at)}</span>
                  <span className="path">{t.from ?? 'start'} &rarr; <b>{t.to}</b></span>
                  <small>{t.reason}</small>
                </li>
              ))}
            </ol>

            <h3>Policy decisions</h3>
            <table className="tbl small">
              <thead><tr><th>Time</th><th>Decision</th><th>Why</th><th>Rejected alternatives</th></tr></thead>
              <tbody>
                {d.decisions.map((p, i) => (
                  <tr key={i}><td className="mono">{time(p.at)}</td><td><b>{p.action}</b>{p.delayMs ? ` (+${p.delayMs} ms)` : ''}</td><td>{p.reason}</td><td>{p.rejected || '-'}</td></tr>
                ))}
              </tbody>
            </table>

            <h3>Remediation actions</h3>
            <table className="tbl small">
              <thead><tr><th>#</th><th>Action</th><th>Status</th><th>Attempts</th><th>Verified</th><th>Result</th><th>Idempotency key</th></tr></thead>
              <tbody>
                {d.actions.map((a) => (
                  <tr key={a.id}>
                    <td>{a.seqNo}</td><td><b>{a.type}</b></td><td>{a.status}</td><td>{a.attempts}</td>
                    <td>{a.verifiedHealthy === null ? '-' : a.verifiedHealthy ? 'healthy' : 'still failing'}</td>
                    <td>{a.result ?? '-'}</td><td className="mono">{a.idempotencyKey}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </>
        )}
      </section>
    </div>
  )
}
