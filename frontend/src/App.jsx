import { useRef, useState } from 'react';
import { reasons, reasonLabel, appointmentCategories, appointmentCategoryLabel, request } from './api.js';
import Appointments from './Appointments.jsx';
import FrontDesk from './FrontDesk.jsx';

function Kiosk() {
  const [visitType, setVisitType] = useState('');
  const [choice, setChoice] = useState('');
  const [visit, setVisit] = useState(null);
  const [pending, setPending] = useState(false);
  const [error, setError] = useState('');
  const submitting = useRef(false);
  const choices = visitType === 'APPOINTMENT' ? appointmentCategories : reasons;

  async function checkIn(event) {
    event.preventDefault();
    if (!visitType || !choice || submitting.current) return;
    submitting.current = true;
    setPending(true);
    setError('');
    const intake = visitType === 'APPOINTMENT'
      ? { visitType, appointmentCategory: choice }
      : { visitType, reason: choice };
    try {
      setVisit(await request('/api/visits', { method: 'POST', body: JSON.stringify(intake) }));
    } catch (failure) {
      setError(failure.message);
    } finally {
      submitting.current = false;
      setPending(false);
    }
  }

  if (visit) {
    return (
      <section className="ticket-panel" aria-live="polite">
        <span className="eyebrow">Check-in complete</span>
        <h1>You're checked in.</h1>
        <p>Your ticket</p>
        <div className="ticket">{visit.ticket}</div>
        <p className="ticket-reason">{visit.visitType === 'APPOINTMENT'
          ? appointmentCategoryLabel(visit.appointmentCategory) : reasonLabel(visit.reason)}</p>
        <p>Please take your ticket to the front desk.</p>
        <button className="primary" onClick={() => { setVisit(null); setVisitType(''); setChoice(''); }}>
          Start a new check-in
        </button>
      </section>
    );
  }

  return (
    <section className="kiosk">
      <span className="eyebrow">Welcome to the Career Center</span>
      <h1>What brings you in today?</h1>
      <p className="intro">Choose Walk-In or Appointment to begin. We'll give you a ticket for the front desk.</p>
      <form onSubmit={checkIn}>
        <fieldset disabled={pending} className="intake-type">
          <legend>Visit type</legend>
          <div className="reason-grid">
            {[['WALK_IN', 'Walk-In'], ['APPOINTMENT', 'Appointment']].map(([value, label]) => (
              <label key={value} className={visitType === value ? 'reason selected' : 'reason'}>
                <span>{label}</span>
                <input type="radio" name="visitType" value={value} checked={visitType === value}
                  onChange={() => { setVisitType(value); setChoice(''); setError(''); }} required />
              </label>
            ))}
          </div>
        </fieldset>
        {visitType && (
          <fieldset disabled={pending}>
            <legend>{visitType === 'APPOINTMENT' ? 'Appointment category' : 'Reason for visit'}</legend>
            <div className="reason-grid">
              {choices.map(([value, label], index) => (
                <label key={value} className={choice === value ? 'reason selected' : 'reason'}>
                  <span className="reason-number">0{index + 1}</span><span>{label}</span>
                  <input type="radio" name="intakeChoice" value={value} checked={choice === value}
                    onChange={() => setChoice(value)} required />
                </label>
              ))}
            </div>
          </fieldset>
        )}
        {error && <p role="alert" className="error">{error}</p>}
        <div className="form-footer">
          <span>No personal details needed at check-in.</span>
          <button className="primary" disabled={!visitType || !choice || pending}>
            {pending ? 'Checking in…' : 'Check in'}
          </button>
        </div>
      </form>
    </section>
  );
}

export default function App() {
  const path = window.location.pathname;
  const role = path === '/appointments' ? 'appointments' : path === '/front-desk' ? 'front-desk' : 'kiosk';
  const supported = ['/', '/kiosk', '/front-desk', '/appointments'].includes(path);

  return (
    <>
      <div className="demo-banner">Synthetic demo — no real data</div>
      <header className="topbar">
        <a className="brand" href="/kiosk"><span className="brand-mark">F</span><span>Frontline<small>Career Center</small></span></a>
        <label className="role-switcher">
          <span>View as</span>
          <select aria-label="Role" value={supported ? role : ''} onChange={event => { window.location.href = '/' + event.target.value; }}>
            {!supported && <option value="" disabled>Select role</option>}
            <option value="kiosk">Kiosk</option>
            <option value="front-desk">Front desk</option>
            <option value="appointments">Appointments</option>
            <option value="resource-room" disabled>Resource room (coming in step 3)</option>
          </select>
        </label>
      </header>
      <main>
        {!supported ? <section><h1>Page not available</h1><a href="/kiosk">Return to kiosk</a></section> :
          role === 'front-desk' ? <FrontDesk /> : role === 'appointments' ? <Appointments /> : <Kiosk />}
      </main>
      <footer>Career Center · Synthetic intake demo</footer>
    </>
  );
}
