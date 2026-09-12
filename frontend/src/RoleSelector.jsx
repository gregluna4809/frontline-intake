import { useEffect, useState } from 'react';
import { request } from './api.js';

export default function RoleSelector({ role, staffId, onNavigate }) {
  const [staff, setStaff] = useState([]);
  const [error, setError] = useState('');
  useEffect(() => {
    let active = true;
    const controller = new AbortController();
    request('/api/staff', { signal: controller.signal })
      .then(data => {
        if (active) { setStaff(data.filter(person => person.role === 'LSR')); setError(''); }
      })
      .catch(() => { if (active) setError('Staff views unavailable. Reload to try again.'); });
    return () => { active = false; controller.abort(); };
  }, []);
  const options = [
    ['kiosk', 'Kiosk', '/kiosk'],
    ['front-desk', 'Front desk', '/front-desk'],
    ['supervisor', 'Supervisor', '/supervisor'],
    ...staff.map(person => ['my-queue:' + person.id, 'My Queue — ' + person.displayName, '/my-queue?staff=' + person.id]),
    ...(role === 'my-queue' ? [] : [['resource-room', 'Resource room', '/resource-room']]),
  ];
  const selected = role === 'my-queue' ? 'my-queue:' + staffId : role;
  const supported = options.some(([value]) => value === selected);
  return (
    <label className="role-switcher">
      <span>View as</span>
      <select aria-label="Role" value={supported ? selected : ''} onChange={event => {
        const option = options.find(([value]) => value === event.target.value);
        if (option) onNavigate(option[2]);
      }}>
        {!supported && <option value="" disabled>Select role</option>}
        {options.map(([value, label]) => <option key={value} value={value}>{label}</option>)}
      </select>
      {error && <small className="staff-views-error">{error}</small>}
    </label>
  );
}
