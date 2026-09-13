import { useEffect, useRef, useState } from 'react';
import { request } from './api.js';

export default function SimulatorControl() {
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
        const [data, businessStatus] = await Promise.all([
          request('/api/simulator/status', { signal: controller.signal }),
          request('/api/business-status', { signal: controller.signal }),
        ]);
        if (active && currentRevision === revision.current && !busy.current) {
          setStatus(data); setBusiness(businessStatus); setError('');
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
  }, []);
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
  return (
    <div className="simulator-control" aria-label="Traffic simulator">
      <span className={'simulator-dot' + (status?.running ? ' running' : '')} aria-hidden="true" />
      <span>Simulator <small>{status ? (status.running ? 'Running' : 'Stopped') : 'Unavailable'}</small></span>
      {business && <span className="business-status"><strong>{business.isOpen ? 'Open' : 'Closed'}</strong>
        <small>Business day {business.currentBusinessDate}</small>
      </span>}
      {status && <span className="simulator-rate">{status.arrivalsPerMinute}/min</span>}
      <button className="secondary" disabled={!status || pending} onClick={toggle}
        title="Business-hours control automatically updates the simulator every minute">
        {status?.running ? 'Stop' : 'Start'}
      </button>
      {error && <span role="alert" className="simulator-error">{error}</span>}
    </div>
  );
}
