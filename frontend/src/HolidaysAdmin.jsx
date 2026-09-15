import { useState } from 'react';
import { calendarDate } from './time.js';
import useAdminData, { AdminFeedback } from './useAdminData.jsx';

export default function HolidaysAdmin() {
  const state = useAdminData('/api/admin/holidays');
  const [date, setDate] = useState('');
  const [label, setLabel] = useState('');
  const [showPast, setShowPast] = useState(false);
  const today = calendarDate(Date.now());
  const visible = state.data?.filter(holiday => showPast || holiday.date >= today) || [];

  return <section aria-label="Holiday management">
    <div className="section-heading"><h2>Holidays</h2><button className="secondary" disabled={state.loading || state.saving} onClick={state.refresh}>Refresh holidays</button></div>
    <AdminFeedback state={state} />
    <form className="admin-form" aria-label="Add holiday" onSubmit={async event => {
      event.preventDefault();
      if (await state.save('/api/admin/holidays', 'POST', { date, label: label.trim() }, 'Holiday added.')) { setDate(''); setLabel(''); }
    }}><fieldset disabled={state.disabled}>
      <label>Holiday date<input type="date" required value={date} onChange={event => setDate(event.target.value)} /></label>
      <label>Holiday label<input required maxLength={100} value={label} onChange={event => setLabel(event.target.value)} /></label>
      <button className="primary" disabled={!label.trim()}>Add holiday</button>
    </fieldset></form>
    <label className="admin-checkbox"><input type="checkbox" checked={showPast} onChange={event => setShowPast(event.target.checked)} />Include past holidays</label>
    <div className="queue-panel table-scroll"><table><caption className="sr-only">{showPast ? 'All holidays' : 'Upcoming holidays'}</caption>
      <thead><tr><th>Date</th><th>Label</th><th>Action</th></tr></thead>
      <tbody>{visible.map(holiday => <tr key={holiday.id}><td>{holiday.date}</td><td>{holiday.label}</td><td>
        <button className="secondary" disabled={state.disabled} aria-label={'Remove ' + holiday.label + ' on ' + holiday.date}
          onClick={() => state.save('/api/admin/holidays/' + holiday.id, 'DELETE', undefined, 'Holiday removed.')}>Remove</button>
      </td></tr>)}</tbody>
    </table></div>
    {state.data && visible.length === 0 && <p>No {showPast ? '' : 'upcoming '}holidays.</p>}
  </section>;
}
