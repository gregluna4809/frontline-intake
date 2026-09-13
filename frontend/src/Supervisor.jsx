import { useState } from 'react';
import { appointmentTypeLabel, appointmentStatusLabel } from './api.js';
import { waitingTime } from './time.js';
import useAppointmentQueue from './useAppointmentQueue.js';
import CoveragePanel from './CoveragePanel.jsx';

function AssignmentRow({ visit, staff, queue }) {
  const [staffId, setStaffId] = useState(visit.assignedStaffId == null ? '' : String(visit.assignedStaffId));
  const hasTarget = staff.some(person => String(person.id) === staffId);
  const orphaned = queue.snapshot.coverage.some(person =>
    person.staffId === visit.assignedStaffId && person.dutyType === 'RESOURCE_ROOM');
  return (
    <tr>
      <td className="table-ticket">{visit.ticket}
        {orphaned && <small className="orphaned-badge">Orphaned — staff on Resource Room today</small>}
      </td>
      <td>{appointmentTypeLabel(visit.appointmentType)}</td>
      <td>{appointmentStatusLabel(visit.appointmentStatus)}</td>
      <td>{waitingTime(visit.waitingSince, queue.now)}</td>
      <td>
        <div className="staff-assignment">
          <select aria-label={'Staff for ' + visit.ticket} value={hasTarget ? staffId : ''}
            disabled={queue.disabled} onChange={event => setStaffId(event.target.value)}>
            <option value="">Choose LSR</option>
            {staff.map(person => <option key={person.id} value={person.id}>{person.displayName}</option>)}
          </select>
          <button className="primary" disabled={queue.disabled || !hasTarget}
            aria-label={(visit.assignedStaffId == null ? 'Assign ' : 'Reassign ') + visit.ticket}
            onClick={() => queue.mutate(visit, 'assign-staff', { staffId: Number(staffId) })}>
            {queue.pendingId === visit.id ? 'Saving…' : visit.assignedStaffId == null ? 'Assign' : 'Reassign'}
          </button>
        </div>
      </td>
      <td><button className="secondary" disabled={queue.disabled}
        aria-label={'Reschedule ' + visit.ticket} onClick={() => queue.mutate(visit, 'reschedule')}>
        Reschedule
      </button></td>
    </tr>
  );
}

export default function Supervisor() {
  const queue = useAppointmentQueue({ includeCoverage: true });
  const staff = queue.snapshot?.staff.filter(person => person.role === 'LSR') || [];
  const groups = [
    { id: null, label: 'Unassigned' },
    ...staff.map(person => ({ id: person.id, label: person.displayName })),
  ];
  // Keep any appointments assigned outside the LSR list visible for reassignment.
  const other = queue.snapshot?.visits.filter(visit => visit.assignedStaffId != null &&
    !staff.some(person => person.id === visit.assignedStaffId)) || [];
  if (other.length) groups.push({ id: 'other', label: 'Other assignments' });

  return (
    <section>
      <div className="section-heading">
        <div>
          <span className="eyebrow">Supervisor</span>
          <h1>Supervisor board</h1>
          <p className="intro">Assign appointment visits to an LSR or change their assignment.</p>
        </div>
        <button className="secondary" disabled={queue.pendingId !== null} onClick={queue.refresh}>Refresh queue</button>
      </div>
      {queue.queueError && <p role="alert" className="error">{queue.queueError} The board may be out of date.</p>}
      {queue.actionError && <p role="alert" className="error">{queue.actionError}</p>}
      {queue.notice && <p role="status" className="routing-result">{queue.notice}</p>}
      {queue.snapshot && <CoveragePanel key={queue.today} queue={queue} />}
      {!queue.snapshot ? <p role="status" className="empty">
        {queue.queueError ? 'Board unavailable.' : 'Loading supervisor board…'}
      </p> : (
        <div className="supervisor-groups" aria-busy={queue.disabled}>
          {groups.map(group => {
            const visits = group.id === 'other' ? other :
              queue.snapshot.visits.filter(visit => visit.assignedStaffId === group.id);
            return (
              <section className="queue-panel" key={group.id ?? 'unassigned'} aria-label={group.label}>
                <div className="queue-heading"><h2>{group.label}</h2><span className="count">{visits.length}</span></div>
                {visits.length === 0 ? <p className="empty">No appointments in this group.</p> : (
                  <div className="table-scroll">
                    <table>
                      <thead><tr><th>Ticket</th><th>Type</th><th>Status</th><th>Waiting time</th><th>Assignment</th><th>Action</th></tr></thead>
                      <tbody>{visits.map(visit => <AssignmentRow key={visit.id + ':' + visit.version}
                        visit={visit} staff={staff} queue={queue} />)}</tbody>
                    </table>
                  </div>
                )}
              </section>
            );
          })}
        </div>
      )}
      <p className="queue-note">Refreshes every 5 seconds. Waiting time starts when the visit is sent to appointments.</p>
    </section>
  );
}
