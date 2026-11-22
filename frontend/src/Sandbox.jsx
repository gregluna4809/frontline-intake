import { useEffect, useState } from 'react';
import { request } from './api.js';
import { landingPage, navigate, saveAuth } from './auth.js';
import { ServerTime, useServerNow } from './ServerTime.jsx';

export const scenarioLabel = value => value === 'BEHAVIOR_UNCHANGED' ? 'Nothing changed' : 'New system works';
export function SandboxRoles({ landing = false } = {}) {
  const [pending, setPending] = useState(false);
  const [error, setError] = useState('');
  async function login(role) {
    setPending(true); setError('');
    try {
      const auth = await request('/api/sandbox/login', { method: 'POST', body: JSON.stringify({ role }) });
      saveAuth(auth); navigate(landingPage(auth.role), true);
    } catch (failure) { setError(failure.message); }
    finally { setPending(false); }
  }
  const roles = [['CLERK', 'Clerk'], ['LSR', 'LSR'], ['SUPERVISING_LSR', 'Supervising LSR']];
  if (!landing) roles.push(['CENTER_MANAGER', 'Center Manager']);
  return <section aria-label="Sandbox staff login">
    {landing && <button className="primary sandbox-manager" disabled={pending} onClick={() => login('CENTER_MANAGER')}>Jump in as Center Manager</button>}
    <div className="sandbox-actions">
    {roles
      .map(([role, label]) => <button key={role} disabled={pending} onClick={() => login(role)}>{label}</button>)}
  </div>{error && <p role="alert">{error}</p>}</section>;
}

export function useSandboxMode(active) {
  const [state, setState] = useState(null);
  useEffect(() => {
    if (!active) return;
    let mounted = true;
    let timer;
    async function load() {
      try { const data = await request('/api/sandbox/state'); if (mounted) setState(data); }
      catch { if (mounted) setState({ enabled: false }); }
      finally { if (mounted) timer = setTimeout(load, 5000); }
    }
    load();
    return () => { mounted = false; clearTimeout(timer); };
  }, [active]);
  return state;
}

function Banner({ state, liveUrl }) {
  const now = useServerNow();
  return <div className="sandbox-banner">Sandbox · dummy data · {new Intl.DateTimeFormat('en-US', {
    timeZone: 'America/New_York', weekday: 'short', month: 'short', day: 'numeric', hour: 'numeric', minute: '2-digit',
  }).format(new Date(now))} · {state.speed}× · {scenarioLabel(state.scenario)}
    {' · '}<a href="/" onClick={event => { event.preventDefault(); navigate('/'); }}>New day</a>
    {liveUrl && <> · <a href={liveUrl}>Live site</a></>}
  </div>;
}
export function SandboxBanner({ state, status }) {
  if (!state?.enabled) return null;
  return <ServerTime.Provider value={status}><Banner state={state} liveUrl={status?.liveUrl} /></ServerTime.Provider>;
}

const sandboxDate = new Intl.DateTimeFormat('en-US', {
  timeZone: 'America/New_York', weekday: 'short', month: 'short', day: 'numeric',
});
const sandboxTime = new Intl.DateTimeFormat('en-US', {
  timeZone: 'America/New_York', hour: 'numeric', minute: '2-digit',
});

function SandboxSummary() {
  const [summary, setSummary] = useState(null);
  useEffect(() => {
    let mounted = true;
    let timer;
    async function load() {
      try {
        const data = await request('/api/sandbox/summary');
        if (mounted) setSummary(data);
      } catch {
        // Keep the last successful summary while retrying.
      } finally {
        if (mounted) timer = setTimeout(load, 5000);
      }
    }
    load();
    return () => { mounted = false; clearTimeout(timer); };
  }, []);
  if (!summary?.sandboxNow) return null;
  const now = new Date(summary.sandboxNow);
  return <p className="sandbox-summary">{sandboxDate.format(now)}, {sandboxTime.format(now)}
    {' · '}{summary.beingServed} being served · {summary.waiting} waiting · {summary.openHelp} help {summary.openHelp === 1 ? 'request' : 'requests'}
  </p>;
}

export default function ForkPage({ status, state }) {
  const [mode, setMode] = useState('NEXT');
  const [date, setDate] = useState('');
  const [time, setTime] = useState('10:15');
  const [speed, setSpeed] = useState(5);
  const [scenario, setScenario] = useState('SHIFT_TO_ONLINE');
  const [pending, setPending] = useState(false);
  const [message, setMessage] = useState('');
  const [error, setError] = useState('');
  async function start(event) {
    event.preventDefault(); setPending(true); setError(''); setMessage('');
    try {
      const result = await request('/api/sandbox/day', { method: 'POST', body: JSON.stringify({ mode,
        ...(mode === 'DATE' ? { date } : {}), time, speed, scenario }) });
      setMessage('Day ready · ' + result.arrivalsSoFar + ' / ' + result.target + ' arrivals');
    } catch (failure) { setError(failure.message); }
    finally { setPending(false); }
  }
  if (!status) return <main><p>Loading Frontline...</p></main>;
  if (!status.sandbox) return <main className="fork-page"><h1>Frontline: Career Center Intake</h1>
    <p>Customer check-in, staff queues and resource coordination for a career center.</p>
    <div className="fork-cards"><section><h2>See the live center</h2><p>Real time, real business hours.</p>
      <a href="/kiosk">Kiosk</a> · <a href="/login">Staff login</a></section>
      <section><h2>Try the sandbox</h2><p>Dummy data, any day, sped up</p>
        {status.sandboxUrl ? <a className="sandbox-link" href={status.sandboxUrl}>Try the sandbox</a> : <p>Sandbox coming soon</p>}
      </section></div></main>;
  return <main className="fork-page"><h1>Frontline Sandbox</h1>
    <SandboxSummary />
    <SandboxRoles landing />
    <details><summary>Pick a different day</summary>
    <form onSubmit={start}><fieldset disabled={pending || state?.warming} className="sandbox-form">
      <label>Day<select value={mode} onChange={event => setMode(event.target.value)}>
        <option value="NEXT">Next business day</option><option value="RANDOM">Random business day</option><option value="DATE">Pick date</option>
      </select></label>
      {mode === 'DATE' && <label>Date<input type="date" required value={date} onChange={event => setDate(event.target.value)} /></label>}
      <label>Time<input type="time" min="08:30" max="16:30" required value={time} onChange={event => setTime(event.target.value)} /></label>
      <label>Speed<select value={speed} onChange={event => setSpeed(Number(event.target.value))}>{[1,5,10].map(value => <option key={value} value={value}>{value}×</option>)}</select></label>
      <label>Scenario<select value={scenario} onChange={event => setScenario(event.target.value)}>
        <option value="SHIFT_TO_ONLINE">New system works</option><option value="BEHAVIOR_UNCHANGED">Nothing changed</option>
      </select></label><button className="primary">Start</button>
    </fieldset></form>
    {(pending || state?.warming) && <p role="status">Warming up the day...</p>}
    {message && <p role="status">{message}</p>}{error && <p role="alert">{error}</p>}
    </details><p><a href="/kiosk">Kiosk</a>
      {status.liveUrl && <> · <a href={status.liveUrl}>Back to the live site</a></>}</p></main>;
}
