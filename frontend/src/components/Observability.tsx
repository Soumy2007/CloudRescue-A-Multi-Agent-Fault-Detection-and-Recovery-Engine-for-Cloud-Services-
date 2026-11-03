import { usePolling } from '../usePolling'
import './observability.css'

interface DurationSummary { meanSec: number | null; medianSec: number | null; maxSec: number | null; n: number }
interface ServiceAvailability { service: string; servingRegion: string; healthyNow: boolean | null; availabilityRatio: number | null; samples: number }
interface RecentIncident { id: number; serviceName: string; failureType: string; state: string; policyName: string; recoverySec: number | null; epoch: number }
interface Summary {
  generatedAt: string
  totalIncidents: number
  activeIncidents: number
  incidentsByState: Record<string, number>
  recoveredIncidents: number
  failedIncidents: number
  escalatedIncidents: number
  recoverySuccessRate: number | null
  recoveryDuration: DurationSummary
  verificationOutcomes: Record<string, number>
  actionResults: Record<string, number>
  services: ServiceAvailability[]
  recentIncidents: RecentIncident[]
  meta: { measurement: string; availabilityWindowSamples: number; notes: string[] }
}

async function fetchSummary(): Promise<Summary> {
  const res = await fetch('/api/observability/summary')
  if (!res.ok) throw new Error(`HTTP ${res.status}`)
  return res.json() as Promise<Summary>
}

const pct = (v: number | null) => (v === null ? 'n/a' : `${(v * 100).toFixed(1)}%`)
const secs = (v: number | null) => (v === null ? 'n/a' : `${v.toFixed(1)} s`)

function Bar({ label, value, max, tone }: { label: string; value: number; max: number; tone: 'ok' | 'bad' | 'warn' | '' }) {
  const width = max === 0 ? 0 : Math.round((value / max) * 100)
  return (
    <div className="obs-bar-row">
      <span>{label}</span>
      <div className="obs-track"><div className={`obs-fill ${tone}`} style={{ width: `${width}%` }} /></div>
      <span className="obs-count">{value}</span>
    </div>
  )
}

export default function Observability() {
  const { data, error } = usePolling(fetchSummary, 2000)

  if (!data) {
    return error
      ? <div className="banner bad">Could not load observability data: {error}</div>
      : <p className="empty">Loading observability data...</p>
  }

  const v = data.verificationOutcomes
  const verificationFailures = (v.ACTION_FAILED ?? 0) + (v.POSTCONDITION_FAILED ?? 0)
  const verificationMax = Math.max(1, v.VERIFIED ?? 0, v.ACTION_FAILED ?? 0, v.POSTCONDITION_FAILED ?? 0)
  const d = data.recoveryDuration
  const stateMax = Math.max(1, ...Object.values(data.incidentsByState))

  return (
    <div>
      {error && <div className="banner bad">Refresh failed ({error}); showing the last data received.</div>}

      <details className="obs-note" open>
        <summary>Simulated measurements, not real infrastructure monitoring</summary>
        <ul>{data.meta.notes.map((n) => <li key={n}>{n}</li>)}</ul>
      </details>

      <section className="obs-kpis">
        <div className="obs-kpi"><span>Total incidents</span><b>{data.totalIncidents}</b></div>
        <div className="obs-kpi"><span>Active (unresolved)</span><b className={data.activeIncidents ? 'warn' : ''}>{data.activeIncidents}</b><small>every state except RECOVERED</small></div>
        <div className="obs-kpi good"><span>Recovered</span><b>{data.recoveredIncidents}</b></div>
        <div className="obs-kpi bad"><span>Failed / escalated</span><b>{data.failedIncidents + data.escalatedIncidents}</b><small>{data.failedIncidents} failed, {data.escalatedIncidents} escalated</small></div>
        <div className="obs-kpi"><span>Recovery success rate</span><b>{pct(data.recoverySuccessRate)}</b><small>{data.recoverySuccessRate === null ? 'nothing has finished yet' : 'of finished incidents'}</small></div>
        <div className="obs-kpi"><span>Mean recovery time</span><b>{secs(d.meanSec)}</b><small>{d.n === 0 ? 'no automatic recoveries yet' : `median ${secs(d.medianSec)}, max ${secs(d.maxSec)}, n=${d.n}`}</small></div>
        <div className={verificationFailures ? 'obs-kpi bad' : 'obs-kpi'}><span>Verification failures</span><b>{verificationFailures}</b><small>actions that did not verify</small></div>
      </section>

      <div className="obs-cols">
        <section className="panel">
          <h2>Verification outcomes <small>every executed action</small></h2>
          <Bar label="Verified" value={v.VERIFIED ?? 0} max={verificationMax} tone="ok" />
          <Bar label="Action failed" value={v.ACTION_FAILED ?? 0} max={verificationMax} tone="bad" />
          <Bar label="Postcondition failed" value={v.POSTCONDITION_FAILED ?? 0} max={verificationMax} tone="warn" />
          <p className="obs-foot">An incident is recovered only after a VERIFIED action. Counts come from the remediation audit table in PostgreSQL.</p>
        </section>
        <section className="panel">
          <h2>Incidents by state</h2>
          {Object.entries(data.incidentsByState).map(([state, count]) => (
            <Bar key={state} label={state} value={count} max={stateMax} tone={state === 'RECOVERED' ? 'ok' : state === 'FAILED' || state === 'ESCALATED' ? 'bad' : 'warn'} />
          ))}
        </section>
      </div>

      <section className="panel">
        <h2>Service availability <small>last {data.meta.availabilityWindowSamples} monitor polls, simulated</small></h2>
        {data.services.length === 0 && <p className="empty">No services reported yet.</p>}
        {data.services.length > 0 && (
          <table className="tbl">
            <thead><tr><th>Service</th><th>Serving region</th><th>Healthy now</th><th>Availability</th><th>Samples</th></tr></thead>
            <tbody>
              {data.services.map((s) => (
                <tr key={s.service}>
                  <td><b>{s.service}</b></td>
                  <td>Region {s.servingRegion}</td>
                  <td>{s.healthyNow === null ? 'unknown' : s.healthyNow ? 'yes' : <b className="warn">no</b>}</td>
                  <td className="mono">{s.availabilityRatio === null ? 'no samples yet' : pct(s.availabilityRatio)}</td>
                  <td className="mono">{s.samples}</td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </section>

      <section className="panel">
        <h2>Recent incidents</h2>
        {data.recentIncidents.length === 0 && <p className="empty">No incidents yet. Inject a fault from the Overview tab.</p>}
        {data.recentIncidents.length > 0 && (
          <table className="tbl small">
            <thead><tr><th>#</th><th>Service</th><th>Failure</th><th>Policy</th><th>State</th><th>Recovery time</th></tr></thead>
            <tbody>
              {data.recentIncidents.map((i) => (
                <tr key={i.id}>
                  <td>{i.id}</td><td>{i.serviceName}</td><td>{i.failureType}</td><td>{i.policyName}</td>
                  <td><span className={`chip ${i.state}`}>{i.state}</span></td>
                  <td className="mono">{i.recoverySec !== null ? secs(i.recoverySec) : i.state === 'RECOVERED' ? 'operator re-opened' : '-'}</td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
        <p className="obs-foot">
          Prometheus metrics: http://localhost:8081/actuator/prometheus (backend port). Updated {data.generatedAt.slice(11, 19)} UTC, refreshed every 2 s.
        </p>
      </section>
    </div>
  )
}
