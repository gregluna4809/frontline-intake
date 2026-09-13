import { useEffect, useRef, useState } from 'react';
import { request } from './api.js';
import { waitingTime } from './time.js';

export const RESOURCE_ALERT_MINUTES = 45;
const groups = [['PHONE', 'Phones'], ['COMPUTER', 'Computers'], ['STATION', 'Stations']];
const typeLabels = { PHONE: 'Phone', COMPUTER: 'Computer', STATION: 'Station' };

export default function ResourceRoom() {
  const [snapshot, setSnapshot] = useState(null);
  const [queueError, setQueueError] = useState('');
  const [actionError, setActionError] = useState('');
  const [notice, setNotice] = useState('');
  const [pending, setPending] = useState(null);
  const [needsRefresh, setNeedsRefresh] = useState(true);
  const [refresh, setRefresh] = useState(0);
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
        const [resources, waiting] = await Promise.all([
          request('/api/resources', { signal: controller.signal }),
          request('/api/visits?status=WAITING_RESOURCE', { signal: controller.signal }),
        ]);
        if (active && currentRevision === revision.current && !submitting.current) {
          setSnapshot({ resources, waiting });
          setQueueError('');
          setNeedsRefresh(false);
          setNow(Date.now());
        }
      } catch (failure) {
        if (active && currentRevision === revision.current) {
          setQueueError(failure.message);
          setNeedsRefresh(true);
        }
      } finally {
        if (active) timer = setTimeout(load, 5000);
      }
    }
    load();
    return () => { active = false; controller.abort(); clearTimeout(timer); };
  }, [refresh]);

  useEffect(() => {
    const timer = setInterval(() => setNow(Date.now()), 1000);
    return () => clearInterval(timer);
  }, []);

  async function mutate(visit, action) {
    if (submitting.current || needsRefresh) return;
    submitting.current = true;
    revision.current++;
    setPending({ id: visit.id, action });
    setNeedsRefresh(true);
    setActionError('');
    setNotice('');
    try {
      const result = await request('/api/visits/' + visit.id + '/' + action, {
        method: 'POST', headers: { 'If-Match': '"' + visit.version + '"' },
      });
      const outcome = action === 'assign' ? 'assigned to ' + result.resource.label :
        action === 'left' ? 'marked left' : 'closed';
      setNotice('Ticket ' + visit.ticket + ' ' + outcome + '.');
    } catch (failure) {
      setActionError(failure.message + ' The inventory will refresh before another action is allowed.');
    } finally {
      revision.current++;
      submitting.current = false;
      setPending(null);
      setRefresh(value => value + 1);
    }
  }

  const disabled = needsRefresh || pending !== null;
  return (
    <section>
      <div className="section-heading">
        <div>
          <span className="eyebrow">Resource room</span>
          <h1>Resources and waiting visits</h1>
          <p className="intro">Assign available resources and complete visits when assistance ends.</p>
        </div>
        <button className="secondary" disabled={pending !== null} onClick={() => setRefresh(value => value + 1)}>
          Refresh inventory
        </button>
      </div>
      {queueError && <p role="alert" className="error">{queueError} Inventory may be out of date.</p>}
      {actionError && <p role="alert" className="error">{actionError}</p>}
      {notice && <p role="status" className="routing-result">{notice}</p>}
      {!snapshot ? <p role="status" className="empty">{queueError ? 'Inventory unavailable.' : 'Loading resource room…'}</p> :
        <div className="resource-room-layout" aria-busy={disabled}>
          <div className="resource-inventory">
            {groups.map(([type, label]) => (
              <section className="resource-group" key={type} aria-label={label}>
                <h2>{label}</h2>
                <div className="resource-grid">
                  {snapshot.resources.filter(resource => resource.type === type).map(resource => {
                    const occupied = resource.status === 'OCCUPIED';
                    const overdue = occupied && resource.assignedAt &&
                      now - new Date(resource.assignedAt).getTime() > RESOURCE_ALERT_MINUTES * 60000;
                    const visit = { id: resource.visitId, ticket: resource.ticket, version: resource.visitVersion };
                    const canAct = occupied && resource.visitId != null && resource.visitVersion != null;
                    return (
                      <article key={resource.id} aria-label={resource.label}
                        className={'resource-tile' + (occupied ? ' occupied' : '') + (overdue ? ' overdue' : '')}>
                        <div className="tile-heading"><h3>{resource.label}</h3>
                          <span className={'resource-status ' + (occupied ? 'occupied' : 'free')}>{resource.status}</span>
                        </div>
                        {occupied ? (
                          <>
                            <p className="tile-ticket">{resource.ticket || 'Occupied outside this business day'}</p>
                            <p className="tile-customer">{resource.customer?.displayName || 'Customer unavailable'}</p>
                            <p className="tile-time">At resource: {waitingTime(resource.assignedAt, now)}</p>
                            {overdue && <p className="overdue-label">Over {RESOURCE_ALERT_MINUTES} min</p>}
                            <div className="tile-actions">
                              <button className="primary" aria-label={'Close ' + resource.ticket}
                                disabled={disabled || !canAct} onClick={() => mutate(visit, 'close')}>Close</button>
                              <button className="secondary" aria-label={'Left ' + resource.ticket}
                                disabled={disabled || !canAct} onClick={() => mutate(visit, 'left')}>Left</button>
                            </div>
                          </>
                        ) : <p className="resource-available">Ready for assignment</p>}
                      </article>
                    );
                  })}
                </div>
              </section>
            ))}
          </div>
          <aside className="resource-waiting" aria-label="Waiting for a resource">
            <div className="queue-heading"><h2>Waiting for a resource</h2><span className="count">{snapshot.waiting.length}</span></div>
            {snapshot.waiting.length === 0 ? <p className="empty">No visits waiting for a resource.</p> :
              <ul className="waiting-resource-list">
                {snapshot.waiting.map(visit => {
                  const hasFree = snapshot.resources.some(resource =>
                    resource.type === visit.requiredResourceType && resource.status === 'FREE');
                  return (
                    <li key={visit.id}>
                      <article aria-label={'Waiting ' + visit.ticket} className="waiting-resource-card">
                        <h3>{visit.ticket}</h3>
                        <p>{visit.customer?.displayName || 'Customer unavailable'}</p>
                        <p>Needs: {typeLabels[visit.requiredResourceType]}</p>
                        <p className="tile-time">Waiting: {waitingTime(visit.waitingSince, now)}</p>
                        {!hasFree && <p className="no-resource">No matching resource free</p>}
                        <div className="tile-actions">
                          <button className="primary" aria-label={'Assign ' + visit.ticket}
                            disabled={disabled || !hasFree} onClick={() => mutate(visit, 'assign')}>Assign</button>
                          <button className="secondary" aria-label={'Left ' + visit.ticket}
                            disabled={disabled} onClick={() => mutate(visit, 'left')}>Left</button>
                        </div>
                      </article>
                    </li>
                  );
                })}
              </ul>}
          </aside>
        </div>}
      <p className="queue-note">Refreshes every 5 seconds. Occupied visits over {RESOURCE_ALERT_MINUTES} minutes are flagged.</p>
    </section>
  );
}
