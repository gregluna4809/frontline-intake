import { useEffect, useRef, useState } from 'react';
import { request } from './api.js';
import { useAuth } from './auth.js';

export default function SimulatorControl() {
  const auth = useAuth();
  const canReadSwitches = ['CENTER_MANAGER', 'ADMINISTRATOR', 'SUPERVISING_LSR'].includes(auth?.role);
  const canManageSwitches = ['CENTER_MANAGER', 'ADMINISTRATOR'].includes(auth?.role);
  const [switches, setSwitches] = useState([]);
  const [status, setStatus] = useState(null);
  const [business, setBusiness] = useState(null);
  const [pending, setPending] = useState(false);
  const [error, setError] = useState('');
  const busy = useRef(false);
  const revision = useRef(0);
  useEffect(() => {
    let active = true;
    let timer;
    const controller = new AbortController();
    async function load() {
      const currentRevision = revision.current;
      try {
        const [data, businessStatus, switchData] = await Promise.all([
          request('/api/simulator/status', { signal: controller.signal }),
          request('/api/business-status', { signal: controller.signal }),
          canReadSwitches ? request('/api/simulator/switches', { signal: controller.signal }) : Promise.resolve([]),
        ]);
        if (active && currentRevision === revision.current && !busy.current) {
          setStatus(data); setBusiness(businessStatus); setSwitches(switchData); setError('');
        }
      } catch (failure) {
        if (active && currentRevision === revision.current) {
          setStatus(null); setBusiness(null); setError(failure.message);
        }
      } finally {
        if (active) timer = setTimeout(load, 5000);
      }
    }
    load();
    return () => { active = false; controller.abort(); clearTimeout(timer); };
  }, [canReadSwitches]);
  async function toggle() {
    if (busy.current || !status) return;
    busy.current = true; revision.current++; setPending(true); setError('');
    try {
      setStatus(await request('/api/simulator/' + (status.running ? 'stop' : 'start'), { method: 'POST' }));
    } catch (failure) {
      setStatus(null); setError(failure.message);
    } finally {
      revision.current++; busy.current = false; setPending(false);
    }
  }
  async function toggleSwitch(item) {
    if (busy.current) return;
    busy.current = true; revision.current++; setPending(true); setError('');
    try {
      const updated = await request('/api/simulator/switches/' + item.role, {
        method: 'PUT', body: JSON.stringify({ enabled: !item.enabled }),
      });
      setSwitches(current => current.map(value => value.role === updated.role ? updated : value));
    } catch (failure) { setError(failure.message); }
    finally { revision.current++; busy.current = false; setPending(false); }
  }
  return (
    <div className="simulator-control" aria-label="Traffic simulator">
      <span className={'simulator-dot' + (status?.running ? ' running' : '')} aria-hidden="true" />
      <span>Simulator <small>{status ? (status.running ? 'Running' : 'Stopped') : 'Unavailable'}</small></span>
      {business && <span className="business-status"><strong>{business.isOpen ? 'Open' : 'Closed'}</strong>
        <small>Business day {business.currentBusinessDate}</small>
      </span>}
      {status && <span className="simulator-rate">{status.arrivalsSoFar || 0} / {status.targetArrivals || 0} arrivals</span>}
      {canManageSwitches && switches.map(item => <label key={item.role}>
        <input type="checkbox" checked={item.enabled} disabled={pending} onChange={() => toggleSwitch(item)} />
        {item.role}
      </label>)}
      <button className="secondary" disabled={!status || pending} onClick={toggle}
        title="Business-hours control automatically updates the simulator every minute">
        {status?.running ? 'Stop' : 'Start'}
      </button>
      {error && <span role="alert" className="simulator-error">{error}</span>}
    </div>
  );
}
