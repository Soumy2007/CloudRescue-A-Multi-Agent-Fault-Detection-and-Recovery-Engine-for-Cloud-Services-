import { useState } from 'react'
import Overview from './components/Overview'
import Incidents from './components/Incidents'
import Experiments from './components/Experiments'

const TABS = ['Overview', 'Incidents', 'Experiments'] as const
type Tab = (typeof TABS)[number]

export default function App() {
  const [tab, setTab] = useState<Tab>('Overview')
  const [focusIncident, setFocusIncident] = useState<number | null>(null)

  return (
    <div className="shell">
      <header className="topbar">
        <div className="brand">
          <span className="brand-mark" aria-hidden />
          <div>
            <h1>CloudRescue</h1>
            <p>Failure-aware recovery manager &middot; simulated regions</p>
          </div>
        </div>
        <nav className="tabs" role="tablist">
          {TABS.map((t) => (
            <button key={t} role="tab" aria-selected={tab === t} className={tab === t ? 'tab on' : 'tab'} onClick={() => setTab(t)}>{t}</button>
          ))}
        </nav>
      </header>
      <main>
        {tab === 'Overview' && <Overview onOpenIncident={(id) => { setFocusIncident(id); setTab('Incidents') }} />}
        {tab === 'Incidents' && <Incidents focusId={focusIncident} />}
        {tab === 'Experiments' && <Experiments />}
      </main>
      <footer className="foot">All regions, services and failures are simulated. Times are UTC. Cost values are assumptions, not measurements.</footer>
    </div>
  )
}
