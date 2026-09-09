import { useEffect, useRef, useState } from 'react';
import { appointmentCategoryLabel, reasonLabel, request } from './api.js';

function purpose(visit) {
  return visit.visitType === 'APPOINTMENT' ? appointmentCategoryLabel(visit.appointmentCategory) : reasonLabel(visit.reason);
}

function routingMessage(detail) {
  if (detail.visit.status === 'AT_RESOURCE') return 'Assigned to ' + detail.resource.label;
  if (detail.visit.status === 'WAITING_RESOURCE') {
    return 'Waiting — no ' + detail.requiredResourceType.toLowerCase() + ' free';
  }
  if (detail.visit.status === 'WAITING_STAFF') return 'Sent to appointments';
  return null;
}

function VisitPanel({ visitId, onChanged }) {
  const [detail, setDetail] = useState(null);
  const [error, setError] = useState('');
  const [loading, setLoading] = useState(true);
  const [pending, setPending] = useState(false);
  const [needsReload, setNeedsReload] = useState(false);
  const [refresh, setRefresh] = useState(0);
  const [query, setQuery] = useState('');
  const [customers, setCustomers] = useState([]);
  const [searching, setSearching] = useState(false);
  const [searchError, setSearchError] = useState('');
  const submitting = useRef(false);

  useEffect(() => {
    let active = true;
    const controller = new AbortController();
    setLoading(true);
    request('/api/visits/' + visitId, { signal: controller.signal })
      .then(data => {
        if (active) {
          setDetail(data);
          setNeedsReload(false);
          setError('');
        }
      })
      .catch(failure => { if (active) { setError(failure.message); setNeedsReload(true); } })
      .finally(() => { if (active) setLoading(false); });
    return () => { active = false; controller.abort(); };
  }, [visitId, refresh]);

  const canIdentify = detail?.visit.status === 'CHECKED_IN' && !loading && !pending && !needsReload;

  useEffect(() => {
    setCustomers([]);
    setSearchError('');
    if (!query.trim() || !canIdentify) {
      setSearching(false);
      return;
    }
    let active = true;
    const controller = new AbortController();
    setSearching(true);
    const timer = setTimeout(() => {
      request('/api/customers?q=' + encodeURIComponent(query.trim()), { signal: controller.signal })
        .then(data => { if (active) setCustomers(data); })
        .catch(failure => { if (active) setSearchError(failure.message); })
        .finally(() => { if (active) setSearching(false); });
    }, 250);
    return () => { active = false; clearTimeout(timer); controller.abort(); };
  }, [query, canIdentify]);

  async function mutate(action, body) {
    if (submitting.current || needsReload) return;
    submitting.current = true;
    setPending(true);
    setError('');
    try {
      const data = await request('/api/visits/' + visitId + '/' + action, {
        method: 'POST',
        headers: { 'If-Match': '"' + detail.visit.version + '"' },
        ...(body ? { body: JSON.stringify(body) } : {}),
      });
      setDetail(data);
      setCustomers([]);
      setQuery('');
      onChanged(data.visit);
    } catch (failure) {
      setError(failure.message);
      // A failed response can still follow a committed transaction. Reload
      // before retrying so actions always use the server's current version.
      setNeedsReload(true);
    } finally {
      submitting.current = false;
      setPending(false);
    }
  }

  const message = detail ? routingMessage(detail) : null;
  return (
    <aside className="visit-panel" aria-label="Selected visit" aria-busy={loading || pending}>
      <div className="panel-heading">
        <h2>{detail ? 'Ticket ' + detail.visit.ticket : 'Selected visit'}</h2>
        <button className="secondary" disabled={pending || loading} onClick={() => setRefresh(value => value + 1)}>
          Reload visit
        </button>
      </div>
      {error && <p role="alert" className="error">{error} Reload the visit before continuing.</p>}
      {loading ? <p role="status">Loading visit…</p> : detail && (
        <>
          <p className="eyebrow">{detail.visit.visitType === 'APPOINTMENT' ? 'Appointment category' : 'Reason for visit'}</p>
          <p className="visit-purpose">{purpose(detail.visit)}</p>
          {detail.customer ? (
            <div className="attached-customer" role="status">
              <span>Attached customer</span>
              <strong>{detail.customer.displayName}</strong>
              <span>ID: {detail.customer.id}</span>
            </div>
          ) : (
            <div className="customer-search">
              <label htmlFor="customer-query">Search synthetic customers</label>
              <input id="customer-query" type="search" value={query} disabled={!canIdentify}
                placeholder="Search by display name"
                onChange={event => { setQuery(event.target.value); setCustomers([]); }} />
              {searchError && <p role="alert" className="error">{searchError}</p>}
              {searching && <p role="status">Searching…</p>}
              {!searching && !searchError && query.trim() && customers.length === 0 && canIdentify &&
                <p>No customers found.</p>}
              {customers.length > 0 && (
                <ul className="customer-results" aria-label="Customer results">
                  {customers.map(customer => (
                    <li key={customer.id}>
                      <button disabled={!canIdentify} onClick={() => mutate('identify', { customerId: customer.id })}>
                        <span>{customer.displayName}</span><small>ID: {customer.id}</small>
                      </button>
                    </li>
                  ))}
                </ul>
              )}
            </div>
          )}
          {message && <p role="status" className="routing-result">{message}</p>}
          <button className="primary route-button"
            disabled={detail.visit.status !== 'IDENTIFIED' || pending || needsReload}
            onClick={() => mutate('route')}>
            {pending ? 'Saving…' : 'Route'}
          </button>
        </>
      )}
    </aside>
  );
}

export default function FrontDesk() {
  const [visits, setVisits] = useState([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');
  const [refresh, setRefresh] = useState(0);
  const [selectedId, setSelectedId] = useState(() => {
    const id = new URLSearchParams(window.location.search).get('visit');
    return id && /^[1-9][0-9]*$/.test(id) ? id : null;
  });

  useEffect(() => {
    let active = true;
    let timer;
    const controller = new AbortController();
    async function load() {
      try {
        const data = await request('/api/visits?status=CHECKED_IN', { signal: controller.signal });
        if (active) { setVisits(data); setError(''); }
      } catch (failure) {
        if (active) setError(failure.message);
      } finally {
        if (active) { setLoading(false); timer = setTimeout(load, 5000); }
      }
    }
    load();
    return () => { active = false; controller.abort(); clearTimeout(timer); };
  }, [refresh]);

  function selectVisit(visit) {
    setSelectedId(String(visit.id));
    window.history.replaceState({}, '', '/front-desk?visit=' + visit.id);
  }

  function visitChanged(visit) {
    if (visit.status !== 'CHECKED_IN') {
      setVisits(current => current.filter(item => item.id !== visit.id));
    }
    setRefresh(value => value + 1);
  }

  return (
    <section>
      <div className="section-heading">
        <div>
          <span className="eyebrow">Front desk</span>
          <h1>Checked-in queue</h1>
          <p className="intro">Select a visit, attach a customer, and route to the next destination.</p>
        </div>
        <button className="secondary" onClick={() => setRefresh(value => value + 1)}>Refresh queue</button>
      </div>
      {error && <p role="alert" className="error">{error} The queue may be out of date.</p>}
      <div className={selectedId ? 'desk-layout with-panel' : 'desk-layout'}>
        <div>
          <div className="queue-panel" aria-busy={loading}>
            <div className="queue-heading"><h2>Waiting at the front desk</h2><span className="count">{visits.length}</span></div>
            {loading ? <p role="status" className="empty">Loading visits…</p> :
              visits.length === 0 ? <p className="empty">{error ? 'Queue unavailable.' : 'No checked-in visits yet. New kiosk check-ins will appear here.'}</p> :
              <div className="table-scroll">
                <table>
                  <thead><tr><th>Ticket</th><th>Reason / category</th><th>Checked in</th></tr></thead>
                  <tbody>{visits.map(visit => (
                    <tr key={visit.id} className={selectedId === String(visit.id) ? 'selected-row' : ''}>
                      <td><button className="ticket-link" onClick={() => selectVisit(visit)}>{visit.ticket}</button></td>
                      <td>{purpose(visit)}</td>
                      <td>{new Date(visit.createdAt).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' })}</td>
                    </tr>
                  ))}</tbody>
                </table>
              </div>}
          </div>
          <p className="queue-note">Queue refreshes every 5 seconds.</p>
        </div>
        {selectedId && <VisitPanel key={selectedId} visitId={selectedId} onChanged={visitChanged} />}
      </div>
    </section>
  );
}
