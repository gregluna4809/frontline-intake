import { appointmentCategoryLabel, reasonLabel } from './api.js';

const titles = { kiosk: 'Final activity', 'front-desk': 'Front desk final activity',
  supervisor: 'Supervisor final activity', 'my-queue': 'My Queue final activity',
  'resource-room': 'Resource room final activity' };
const label = value => value ? value.replaceAll('_', ' ') : '\u2014';

export default function SnapshotView({ snapshot, role, staffId }) {
  const staff = new Map(snapshot.staff.map(person => [person.id, person.displayName]));
  const visits = snapshot.visits.filter(visit => {
    if (role === 'my-queue') return String(visit.assignedStaffId) === staffId;
    if (role === 'supervisor') return visit.visitType === 'APPOINTMENT';
    if (role === 'resource-room') return visit.visitType === 'WALK_IN';
    return true;
  });
  return (
    <section className="snapshot-view">
      <h1>{titles[role]}</h1>
      {role === 'my-queue' && <p>{staff.get(Number(staffId)) || 'Select a staff view'}</p>}
      <p>{visits.length} visits recorded for {snapshot.businessDate}.</p>
      <div className="snapshot-table-scroll">
        <table className="snapshot-table">
          <caption>Visits at close</caption>
          <thead><tr><th>Ticket</th><th>Customer</th><th>Reason / category</th><th>Final status</th><th>Staff</th></tr></thead>
          <tbody>{visits.map(visit => <tr key={visit.id}>
            <td>{visit.ticket}</td><td>{visit.customer?.displayName || 'Not identified'}</td>
            <td>{visit.visitType === 'APPOINTMENT' ? appointmentCategoryLabel(visit.appointmentCategory) : reasonLabel(visit.reason)}</td>
            <td>{label(visit.status)}</td><td>{staff.get(visit.assignedStaffId) || 'Unassigned'}</td>
          </tr>)}</tbody>
        </table>
      </div>
      {role === 'resource-room' && <section aria-label="Resources at close">
        <h2>Resources at close</h2>
        <ul className="snapshot-resources">{snapshot.resources.map(resource => <li key={resource.id}>
          <strong>{resource.label}</strong><span>{resource.status}</span>
          {resource.ticket && <span>{resource.ticket} &mdash; {resource.customer?.displayName || 'Not identified'}</span>}
        </li>)}</ul>
      </section>}
      {role === 'supervisor' && <section aria-label="Coverage at close">
        <h2>Coverage at close</h2>
        <ul>{snapshot.coverage.map(person => <li key={person.staffId}>{person.displayName}: {label(person.dutyType)}</li>)}</ul>
      </section>}
    </section>
  );
}
