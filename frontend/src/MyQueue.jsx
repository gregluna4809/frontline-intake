import { appointmentTypeLabel, appointmentStatusLabel } from './api.js';
import { waitingTime } from './time.js';
import useAppointmentQueue from './useAppointmentQueue.js';

export default function MyQueue({ staffId }) {
  const queue = useAppointmentQueue();
  const staff = queue.snapshot?.staff.find(person => person.role === 'LSR' && String(person.id) === staffId);
  const visits = staff ? queue.snapshot.visits.filter(visit => visit.assignedStaffId === staff.id) : [];

  return (
    <section>
      <div className="section-heading">
        <div>
          <span className="eyebrow">My Queue</span>
          <h1>{staff ? staff.displayName + ' — My Queue' : 'My Queue'}</h1>
          <p className="intro">Appointment visits assigned to the selected LSR.</p>
        </div>
        <button className="secondary" disabled={queue.pendingId !== null} onClick={queue.refresh}>Refresh queue</button>
      </div>
      {queue.queueError && <p role="alert" className="error">{queue.queueError} The queue may be out of date.</p>}
      {queue.actionError && <p role="alert" className="error">{queue.actionError}</p>}
      {queue.notice && <p role="status" className="routing-result">{queue.notice}</p>}
      <div className="queue-panel" aria-busy={queue.disabled}>
        <div className="queue-heading"><h2>Assigned appointments</h2><span className="count">{visits.length}</span></div>
        {!queue.snapshot ? <p role="status" className="empty">{queue.queueError ? 'Queue unavailable.' : 'Loading my queue…'}</p> :
          !staff ? <p className="empty">Choose an LSR from View as to see their queue.</p> :
          visits.length === 0 ? <p className="empty">No appointments assigned to you.</p> : (
            <div className="table-scroll">
              <table>
                <thead><tr><th>Ticket</th><th>Type</th><th>Status</th><th>Waiting time</th><th>Action</th></tr></thead>
                <tbody>{visits.map(visit => (
                  <tr key={visit.id}>
                    <td className="table-ticket">{visit.ticket}</td>
                    <td>{appointmentTypeLabel(visit.appointmentType)}</td>
                    <td>{appointmentStatusLabel(visit.appointmentStatus)}</td>
                    <td>{waitingTime(visit.waitingSince, queue.now)}</td>
                    <td><div className="visit-actions"><button className="primary" aria-label={'Close ' + visit.ticket}
                      disabled={queue.disabled} onClick={() => queue.mutate(visit, 'close')}>
                      {queue.pendingId === visit.id ? 'Saving…' : 'Close'}
                    </button>
                      <button className="secondary" aria-label={'Reschedule ' + visit.ticket}
                        disabled={queue.disabled} onClick={() => queue.mutate(visit, 'reschedule')}>
                        Reschedule
                      </button>
                    </div></td>
                  </tr>
                ))}</tbody>
              </table>
            </div>
          )}
      </div>
      <p className="queue-note">Refreshes every 5 seconds. Waiting time starts when the visit is sent to appointments.</p>
    </section>
  );
}
