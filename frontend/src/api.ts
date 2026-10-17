export interface NodeHealth {
  id: number; serviceName: string; region: 'A' | 'B'; role: string; active: boolean
  healthy: boolean | null; latencyMs: number | null; status: number | null
  availabilityPct: number | null; recent: boolean[]
}
export interface Incident {
  id: number; serviceName: string; failureType: string; state: string; policyName: string
  detectedAt: string; recoveredAt: string | null; recoverySec: number | null
  faultInjectionId: number | null; epoch: number; summary: string
}
export interface Transition { from: string | null; to: string; reason: string; at: string }
export interface Action {
  id: number; seqNo: number; type: string; status: string; idempotencyKey: string
  durationMs: number | null; attempts: number; result: string | null; verifiedHealthy: boolean | null
}
export interface Decision { policy: string; failureType: string; action: string; delayMs: number; reason: string; rejected: string; at: string }
export interface IncidentDetail { incident: Incident; transitions: Transition[]; actions: Action[]; decisions: Decision[] }
export interface Fault {
  id: number; type: string; nodeId: number | null; region: string | null; severity: number
  startedAt: string; endedAt: string | null; endReason: string | null
}
export interface PolicyInfo { name: string; description: string; active: boolean }
export interface Page<T> { items: T[]; page: number; size: number; total: number }
export interface Stat { mean: number | null; sd: number | null; n: number }
export interface Cell {
  scenario: string; policy: string; trials: number; recoverySuccessRate: number; escalationRate: number
  detectionDelaySec: Stat; recoveryTimeSec: Stat; totalTimeSec: Stat; availability: Stat; cost: Stat
  meanDuplicateOps: number; meanRetries: number; meanRestarts: number; meanFailovers: number; idempotencyEnabled: boolean
}
export interface ExperimentSummary { batchId: string; totalTrials: number; cells: Cell[]; overall: Cell[] }
export interface MetricDef { name: string; definition: string; kind: string }
export interface Live {
  incidentsByState: Record<string, number>
  perPolicy: { policy: string; incidents: number; recovered: number; meanRecoverySec: number | null; meanDetectionDelaySec: number | null }[]
  duplicateEffects: number
}

async function call<T>(path: string, init?: RequestInit): Promise<T> {
  const res = await fetch(path, { headers: { 'Content-Type': 'application/json' }, ...init })
  if (!res.ok) {
    let msg = res.statusText
    try { msg = (await res.json()).message ?? msg } catch { /* not JSON */ }
    throw new Error(msg)
  }
  return res.json() as Promise<T>
}

export const api = {
  services: () => call<NodeHealth[]>('/api/services'),
  incidents: () => call<Page<Incident>>('/api/incidents?size=50'),
  incident: (id: number) => call<IncidentDetail>(`/api/incidents/${id}`),
  reopen: (id: number, action?: string) =>
    call<Incident>(`/api/incidents/${id}/reopen`, { method: 'POST', body: JSON.stringify({ action: action ?? null }) }),
  faults: () => call<Fault[]>('/api/faults'),
  inject: (body: object) => call<Fault>('/api/faults', { method: 'POST', body: JSON.stringify(body) }),
  resetFaults: () => call<{ ended: number }>('/api/faults/reset', { method: 'POST' }),
  policies: () => call<PolicyInfo[]>('/api/policies'),
  setPolicy: (name: string) => call<PolicyInfo[]>('/api/policies/active', { method: 'PUT', body: JSON.stringify({ name }) }),
  runExperiment: (body: object) => call<ExperimentSummary>('/api/experiments', { method: 'POST', body: JSON.stringify(body) }),
  metrics: () => call<MetricDef[]>('/api/analytics/metrics'),
  live: () => call<Live>('/api/analytics/live'),
}
