import { useState } from 'react';

export default function CoveragePanel({ queue }) {
  const [selectedStaffId, setSelectedStaffId] = useState(null);
  const [reason, setReason] = useState('');
  const selected = queue.snapshot.coverage.find(person => person.staffId === selectedStaffId);

  async function submit(event) {
    event.preventDefault();
    if (await queue.markCalledOut(selectedStaffId, reason)) {
      setSelectedStaffId(null);
      setReason('');
    }
  }

  return (
    <section className="queue-panel coverage-panel" aria-label="Business-day coverage" aria-busy={queue.disabled}>
      <div className="queue-heading"><h2>Business-day coverage</h2><time dateTime={queue.snapshot.date}>{queue.snapshot.date}</time></div>
      {!queue.canCallOut && <p className="queue-note coverage-note">Showing the last business day. Call-outs can be entered for the current calendar day.</p>}
      <ul className="coverage-list">
        {queue.snapshot.coverage.map(person => (
          <li key={person.staffId}>
            <div><strong>{person.displayName}</strong>
              <span>{person.dutyType === 'RESOURCE_ROOM' ? 'Resource Room' : 'Appointments'}</span>
            </div>
            <button className="secondary" disabled={queue.disabled || !queue.canCallOut}
              aria-label={'Mark called out ' + person.displayName}
              onClick={() => { setSelectedStaffId(person.staffId); setReason(''); }}>
              Mark called out
            </button>
          </li>
        ))}
      </ul>
      {selected && (
        <form className="call-out-form" aria-label={'Call-out for ' + selected.displayName} onSubmit={submit}>
          <h3>Call-out for {selected.displayName}</h3>
          <p>This marks the LSR as covering Resource Room for {queue.snapshot.date}.</p>
          <label htmlFor="call-out-reason">Reason (optional, synthetic only)</label>
          <textarea id="call-out-reason" value={reason} disabled={queue.disabled || !queue.canCallOut}
            onChange={event => setReason(event.target.value)} rows={2} />
          <div className="visit-actions">
            <button className="primary" disabled={queue.disabled || !queue.canCallOut}>Save call-out</button>
            <button type="button" className="secondary" disabled={queue.pendingId !== null}
              onClick={() => { setSelectedStaffId(null); setReason(''); }}>Cancel</button>
          </div>
        </form>
      )}
    </section>
  );
}
