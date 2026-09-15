export default function StaffQueueSelector({ staff, staffId, onChange }) {
  return <label className="queue-staff-picker">Staff member
    <select value={staff.some(person => person.role === 'LSR' && String(person.id) === staffId) ? staffId : ''}
      onChange={event => onChange(event.target.value)}>
      <option value="">Choose an LSR</option>
      {staff.filter(person => person.role === 'LSR').map(person =>
        <option key={person.id} value={person.id}>{person.displayName}</option>)}
    </select>
  </label>;
}
