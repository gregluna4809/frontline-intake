import { useEffect, useRef, useState } from 'react';
import { appointmentCategoryLabel, request } from './api.js';
import useVisitReasons from './useVisitReasons.js';

function purpose(visit, reasons) {
  return visit.visitType === 'APPOINTMENT' ? appointmentCategoryLabel(visit.appointmentCategory)
    : reasons.find(reason => reason.code === visit.reason)?.label || '\u2014';
}

function routingMessage(detail) {
  if (detail.visit.status === 'AT_RESOURCE') return 'Assigned to ' + detail.resource.label;
  if (detail.visit.status === 'WAITING_RESOURCE') {
    return 'Waiting — no ' + detail.requiredResourceType.toLowerCase() + ' free';
  }
  if (detail.visit.status === 'WAITING_STAFF') return 'Sent to appointments';
  return null;
}

function VisitPanel({ visitId, onChanged, reasons }) {
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
  const [customerError, setCustomerError] = useState('');
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

  function createCustomer(event) {
    event.preventDefault();
    const fields = new FormData(event.currentTarget);
    const displayName = fields.get('displayName').trim();
    if (!displayName) {
      setCustomerError('Enter a customer name.');
      return;
    }
    mutate('identify', null, { displayName, language: fields.get('language'), veteran: fields.has('veteran') });
  }

  async function mutate(action, body, newCustomer) {
    if (submitting.current || needsReload) return;
    submitting.current = true;
    setPending(true);
    setError('');
    try {
      if (newCustomer) {
        setCustomerError('');
        try {
          const customer = await request('/api/customers', { method: 'POST', body: JSON.stringify(newCustomer) });
          body = { customerId: customer.id };
        } catch (failure) {
          setCustomerError(failure.message);
          return;
        }
      }
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
          <p className="visit-purpose">{purpose(detail.visit, reasons)}</p>
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
              {detail.visit.status === 'CHECKED_IN' && (
                <details>
                  <summary>New customer</summary>
                  <form onSubmit={createCustomer}>
                    <fieldset disabled={!canIdentify}>
                      <label>Name<input name="displayName" required maxLength={100} /></label>
                      <label>Language<select name="language" defaultValue="EN">
                        <option value="EN">English</option>
                        <option value="ES">Spanish</option>
                        <option value="OTHER">Other</option>
                      </select></label>
                      <label><input type="checkbox" name="veteran" /> Veteran</label>
                      <button type="submit">Create and select</button>
                    </fieldset>
                    {customerError && <p role="alert" className="error">{customerError}</p>}
                  </form>
                </details>
              )}
            </div>
          )}
          {detail.missedAppointment && <p role="status" className="ftr-notice">
            <strong>Customer has a missed appointment (FTR).</strong><br />
            Ticket {detail.missedAppointment.ticket} from {detail.missedAppointment.businessDate}.
          </p>}
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

export default function FrontDesk({ businessDate }) {
  const activeReasons = useVisitReasons(businessDate);
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
        const [checkedIn, identified] = await Promise.all([
          request('/api/visits?status=CHECKED_IN', { signal: controller.signal }),
          request('/api/visits?status=IDENTIFIED', { signal: controller.signal }),
        ]);
        const data = [...new Map([...checkedIn, ...identified].map(visit => [visit.id, visit])).values()]
          .sort((a, b) => new Date(a.createdAt) - new Date(b.createdAt) || a.id - b.id);
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
    if (visit.status === 'CHECKED_IN' || visit.status === 'IDENTIFIED') {
      setVisits(current => current.map(item => item.id === visit.id ? visit : item));
    } else {
      setVisits(current => current.filter(item => item.id !== visit.id));
    }
    setRefresh(value => value + 1);
  }

  return (
    <section>
      <div className="section-heading">
        <div>
          <span className="eyebrow">Front desk</span>
          <h1>Front-desk queue</h1>
          <p className="intro">Select a visit, attach a customer, and route to the next destination.</p>
        </div>
        <button className="secondary" onClick={() => setRefresh(value => value + 1)}>Refresh queue</button>
      </div>
      {(error || activeReasons.error) && <p role="alert" className="error">{error || activeReasons.error} The queue may be out of date.</p>}
      <div className={selectedId ? 'desk-layout with-panel' : 'desk-layout'}>
        <div>
          <div className="queue-panel" aria-busy={loading}>
            <div className="queue-heading"><h2>Waiting at the front desk</h2><span className="count">{visits.length}</span></div>
            {loading ? <p role="status" className="empty">Loading visits…</p> :
              visits.length === 0 ? <p className="empty">{error ? 'Queue unavailable.' : 'No visits waiting at the front desk. New kiosk check-ins will appear here.'}</p> :
              <div className="table-scroll">
                <table>
                  <thead><tr><th>Ticket</th><th>Status</th><th>Reason / category</th><th>Checked in</th></tr></thead>
                  <tbody>{visits.map(visit => (
                    <tr key={visit.id} className={selectedId === String(visit.id) ? 'selected-row' : ''}>
                      <td><button className="ticket-link" onClick={() => selectVisit(visit)}>{visit.ticket}</button></td>
                      <td><small>{visit.status === 'IDENTIFIED' ? 'Ready to route' : 'Needs ID'}</small></td>
                      <td>{purpose(visit, activeReasons.reasons)}</td>
                      <td>{new Date(visit.createdAt).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' })}</td>
                    </tr>
                  ))}</tbody>
                </table>
              </div>}
          </div>
          <p className="queue-note">Queue refreshes every 5 seconds.</p>
        </div>
        {selectedId && <VisitPanel key={selectedId} visitId={selectedId} onChanged={visitChanged} reasons={activeReasons.reasons} />}
      </div>
    </section>
  );
}
