import { useCallback, useMemo, useState } from 'react'
import { Bar, BarChart, CartesianGrid, Legend, ResponsiveContainer, Tooltip, XAxis, YAxis } from 'recharts'
import { api, Cell, ExperimentSummary } from '../api'
import { usePolling } from '../usePolling'

const POLICY_COLORS: Record<string, string> = { FIXED_RETRY: '#8a97a6', EXPONENTIAL_BACKOFF: '#d98e04', COST_AWARE: '#1b3a5c' }

const METRICS: { key: string; label: string; pick: (c: Cell) => number | null; unit: string }[] = [
  { key: 'success', label: 'Recovery success rate', pick: (c) => c.recoverySuccessRate * 100, unit: '%' },
  { key: 'avail', label: 'Availability', pick: (c) => (c.availability.mean ?? 0) * 100, unit: '%' },
  { key: 'mttr', label: 'Recovery time (recovered trials)', pick: (c) => c.recoveryTimeSec.mean, unit: 's' },
  { key: 'dup', label: 'Duplicate operations / trial', pick: (c) => c.meanDuplicateOps, unit: '' },
  { key: 'cost', label: 'Cost (assumed units)', pick: (c) => c.cost.mean, unit: '' },
]

const fmt = (s: { mean: number | null; sd: number | null; n: number }, d = 1, scale = 1) =>
  s.mean === null ? 'n/a' : `${(s.mean * scale).toFixed(d)} ± ${((s.sd ?? 0) * scale).toFixed(d)} (n=${s.n})`

export default function Experiments() {
  const defs = usePolling(useCallback(() => api.metrics(), []), 60000)
  const [trials, setTrials] = useState(30)
  const [seed, setSeed] = useState(1000)
  const [idem, setIdem] = useState(true)
  const [busy, setBusy] = useState(false)
  const [err, setErr] = useState<string | null>(null)
  const [result, setResult] = useState<ExperimentSummary | null>(null)
  const [metric, setMetric] = useState('success')

  async function run() {
    setBusy(true); setErr(null)
    try { setResult(await api.runExperiment({ trials, seed, idempotencyEnabled: idem })) } catch (e) { setErr((e as Error).message) }
    setBusy(false)
  }

  const m = METRICS.find((x) => x.key === metric)!
  const policies = useMemo(() => Array.from(new Set((result?.cells ?? []).map((c) => c.policy))), [result])
  const chartData = useMemo(() => {
    const byScenario = new Map<string, Record<string, number | string | null>>()
    for (const c of result?.cells ?? []) {
      const row = byScenario.get(c.scenario) ?? { scenario: c.scenario.replace(/_/g, ' ').toLowerCase() }
      row[c.policy] = m.pick(c)
      byScenario.set(c.scenario, row)
    }
    return Array.from(byScenario.values())
  }, [result, m])

  return (
    <div className="exp">
      <section className="panel">
        <h2>Policy experiment <small>virtual clock, seeded, repeatable</small></h2>
        <p className="hint">
          Runs every scenario under every policy for the same seeds, using the same policy classes as the live system.
          Same seed + same settings = identical numbers.
        </p>
        <div className="form-row">
          <label>Trials per cell<input type="number" min={1} max={200} value={trials} onChange={(e) => setTrials(Number(e.target.value))} /></label>
          <label>Base seed<input type="number" value={seed} onChange={(e) => setSeed(Number(e.target.value))} /></label>
          <label className="check"><input type="checkbox" checked={idem} onChange={(e) => setIdem(e.target.checked)} /> Idempotency enabled</label>
          <button className="btn primary" disabled={busy} onClick={run}>{busy ? 'Running...' : 'Run experiment'}</button>
        </div>
        {err && <p className="err">{err}</p>}
      </section>

      {result && (
        <>
          <section className="panel">
            <div className="chart-head">
              <h2>Comparison <small>{result.totalTrials} trials &middot; batch {result.batchId.slice(0, 8)}</small></h2>
              <select value={metric} onChange={(e) => setMetric(e.target.value)}>{METRICS.map((x) => <option key={x.key} value={x.key}>{x.label}</option>)}</select>
            </div>
            <div className="chart">
              <ResponsiveContainer width="100%" height={320}>
                <BarChart data={chartData} margin={{ top: 8, right: 16, left: 0, bottom: 8 }}>
                  <CartesianGrid strokeDasharray="3 3" stroke="#d9dfe5" />
                  <XAxis dataKey="scenario" tick={{ fontSize: 11 }} interval={0} />
                  <YAxis tick={{ fontSize: 11 }} unit={m.unit} />
                  <Tooltip formatter={(v: number | string) => (typeof v === 'number' ? v.toFixed(2) : v)} />
                  <Legend />
                  {policies.map((p) => <Bar key={p} dataKey={p} fill={POLICY_COLORS[p] ?? '#4a8'} radius={[3, 3, 0, 0]} />)}
                </BarChart>
              </ResponsiveContainer>
            </div>
            {metric === 'mttr' && <p className="hint">Bars are missing where no trial recovered (recovery time is undefined, not zero).</p>}
          </section>

          <section className="panel">
            <h2>Overall per policy <small>all scenarios pooled</small></h2>
            <table className="tbl">
              <thead><tr><th>Policy</th><th>Success</th><th>Recovery time (s)</th><th>Availability (%)</th><th>Duplicates</th><th>Cost*</th><th>Retries / restarts / failovers</th></tr></thead>
              <tbody>
                {result.overall.map((c) => (
                  <tr key={c.policy}>
                    <td><b>{c.policy}</b></td><td>{(c.recoverySuccessRate * 100).toFixed(0)}%</td>
                    <td>{fmt(c.recoveryTimeSec)}</td><td>{fmt(c.availability, 1, 100)}</td>
                    <td>{c.meanDuplicateOps.toFixed(2)}</td><td>{fmt(c.cost, 2)}</td>
                    <td>{c.meanRetries.toFixed(1)} / {c.meanRestarts.toFixed(1)} / {c.meanFailovers.toFixed(1)}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </section>

          <section className="panel">
            <h2>Every scenario <small>mean ± sample sd (n)</small></h2>
            <div className="scroll">
              <table className="tbl small">
                <thead><tr><th>Scenario</th><th>Policy</th><th>Success</th><th>Detection (s)</th><th>Recovery (s)</th><th>Availability (%)</th><th>Dup.</th><th>Cost*</th></tr></thead>
                <tbody>
                  {result.cells.map((c) => (
                    <tr key={c.scenario + c.policy}>
                      <td>{c.scenario}</td><td>{c.policy}</td><td>{(c.recoverySuccessRate * 100).toFixed(0)}%</td>
                      <td>{fmt(c.detectionDelaySec)}</td><td>{fmt(c.recoveryTimeSec)}</td><td>{fmt(c.availability, 1, 100)}</td>
                      <td>{c.meanDuplicateOps.toFixed(2)}</td><td>{fmt(c.cost, 2)}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          </section>
        </>
      )}

      <section className="panel">
        <h2>Metric definitions</h2>
        <dl className="defs">
          {(defs.data ?? []).map((d) => (
            <div key={d.name}><dt>{d.name} {d.kind === 'assumption' && <span className="chip FAILED">assumption</span>}</dt><dd>{d.definition}</dd></div>
          ))}
        </dl>
        <p className="hint">* Cost uses assumed weights and is not a measurement. Results come from a simulation, not real cloud infrastructure.</p>
      </section>
    </div>
  )
}
