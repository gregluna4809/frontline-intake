import { useEffect, useState } from 'react';
import useAdminData, { AdminFeedback } from './useAdminData.jsx';

const days = ['Monday', 'Tuesday', 'Wednesday', 'Thursday', 'Friday', 'Saturday', 'Sunday'];

export default function RotationAdmin() {
  const state = useAdminData('/api/admin/rotation');
  const [draft, setDraft] = useState([]);
  const [dirty, setDirty] = useState(false);
  useEffect(() => { if (state.data) { setDraft(state.data); setDirty(false); } }, [state.data]);
  const staff = [...new Map(draft.map(row => [row.staffId, row.displayName])).entries()];

  return <section aria-label="Weekly rotation management">
    <div className="section-heading"><h2>Weekly base rotation</h2><button className="secondary" disabled={state.loading || state.saving} onClick={state.refresh}>Reload rotation</button></div>
    <p className="intro">Set each active LSR's regular duty. Date-specific call-outs still override the base rotation.</p>
    <AdminFeedback state={state} />
    <form onSubmit={async event => {
      event.preventDefault();
      await state.save('/api/admin/rotation', 'PUT', { assignments: draft.map(({ staffId, dayOfWeek, dutyType }) => ({ staffId, dayOfWeek, dutyType })) }, 'Weekly rotation saved.');
    }}>
      <fieldset disabled={state.disabled}>
        <div className="queue-panel table-scroll"><table className="rotation-grid"><caption className="sr-only">Weekly duty assignments</caption>
          <thead><tr><th>Day</th>{staff.map(([id, name]) => <th key={id}>{name}</th>)}</tr></thead>
          <tbody>{days.map((day, index) => <tr key={day}><th scope="row">{day}</th>{staff.map(([id, name]) => {
            const row = draft.find(item => item.staffId === id && item.dayOfWeek === index + 1);
            return <td key={id}><select aria-label={day + ' duty for ' + name} value={row?.dutyType || 'APPOINTMENTS'} onChange={event => {
              setDraft(draft.map(item => item === row ? { ...item, dutyType: event.target.value } : item)); setDirty(true);
            }}><option value="APPOINTMENTS">Appointments</option><option value="RESOURCE_ROOM">Resource room</option></select></td>;
          })}</tr>)}</tbody>
        </table></div>
        {state.data?.length === 0 && <p>No active LSRs. Add an LSR in Staff to configure rotation.</p>}
        <button className="primary admin-save" disabled={!dirty || !staff.length}>Save rotation</button>
      </fieldset>
    </form>
  </section>;
}
