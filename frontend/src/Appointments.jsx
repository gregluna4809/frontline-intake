import { useEffect, useRef, useState } from 'react';
import { appointmentCategoryLabel, request } from './api.js';

import { waitingTime } from './time.js';

export default function Appointments() {
  const [visits, setVisits] = useState([]);
  const [loading, setLoading] = useState(true);
  const [queueError, setQueueError] = useState('');
  const [actionError, setActionError] = useState('');
  const [notice, setNotice] = useState('');
  const [refresh, setRefresh] = useState(0);
  const [pendingId, setPendingId] = useState(null);
  const [needsRefresh, setNeedsRefresh] = useState(false);
  const [now, setNow] = useState(Date.now());
  const submitting = useRef(false);
  const revision = useRef(0);

  useEffect(() => {
    let active = true;
    let timer;
    const controller = new AbortController();
    async function load() {
      const currentRevision = revision.current;
      try {
        const data = await request('/api/visits?status=WAITING_STAFF', { signal: controller.signal });
        if (active && currentRevision === revision.current) {
          setVisits(data);
          setQueueError('');
          setNeedsRefresh(false);
        }
      } catch (failure) {
        if (active) setQueueError(failure.message);
      } finally {
        if (active) { setLoading(false); timer = setTimeout(load, 5000); }
      }
    }
    load();
    return () => { active = false; controller.abort(); clearTimeout(timer); };
  }, [refresh]);

  useEffect(() => {
    const timer = setInterval(() => setNow(Date.now()), 1000);
    return () => clearInterval(timer);
  }, []);

  async function close(visit) {
    if (submitting.current || needsRefresh) return;
    submitting.current = true;
    revision.current++;
    setPendingId(visit.id);
    setActionError('');
    setNotice('');
    try {
      await request('/api/visits/' + visit.id + '/close', {
        method: 'POST', headers: { 'If-Match': '"' + visit.version + '"' },
      });
      setVisits(current => current.filter(item => item.id !== visit.id));
      setNotice('Ticket ' + visit.ticket + ' closed.');
    } catch (failure) {
      setActionError(failure.message + ' Refresh the queue before trying again.');
      setNeedsRefresh(true);
    } finally {
      revision.current++;
      submitting.current = false;
      setPendingId(null);
      setRefresh(value => value + 1);
    }
  }

  return (
    <section>
      <div className="section-heading">
        <div>
          <span className="eyebrow">Appointments</span>
          <h1>Waiting for staff</h1>
          <p className="intro">Appointment visits ready for assistance.</p>
        </div>
        <button className="secondary" disabled={pendingId !== null} onClick={() => setRefresh(value => value + 1)}>
          Refresh queue
        </button>
      </div>
      {queueError && <p role="alert" className="error">{queueError} The queue may be out of date.</p>}
      {actionError && <p role="alert" className="error">{actionError}</p>}
      {notice && <p role="status" className="routing-result">{notice}</p>}
      <div className="queue-panel" aria-busy={loading || pendingId !== null}>
        <div className="queue-heading"><h2>Appointment queue</h2><span className="count">{visits.length}</span></div>
        {loading ? <p role="status" className="empty">Loading appointments…</p> :
          visits.length === 0 ? <p className="empty">{queueError ? 'Queue unavailable.' : 'No appointments waiting for staff.'}</p> :
            <div className="table-scroll">
              <table>
                <thead><tr><th>Ticket</th><th>Customer</th><th>Category</th><th>Waiting time</th><th>Action</th></tr></thead>
                <tbody>{visits.map(visit => (
                  <tr key={visit.id}>
                    <td className="table-ticket">{visit.ticket}</td>
                    <td>{visit.customer?.displayName || 'Customer unavailable'}</td>
                    <td>{appointmentCategoryLabel(visit.appointmentCategory)}</td>
                    <td>{waitingTime(visit.waitingSince, now)}</td>
                    <td><button className="primary" aria-label={'Close ' + visit.ticket}
                      disabled={pendingId !== null || needsRefresh || Boolean(queueError)}
                      onClick={() => close(visit)}>{pendingId === visit.id ? 'Closing…' : 'Close'}</button></td>
                  </tr>
                ))}</tbody>
              </table>
            </div>}
      </div>
      <p className="queue-note">Queue refreshes every 5 seconds. Waiting time starts when the visit is sent to appointments.</p>
    </section>
  );
}
