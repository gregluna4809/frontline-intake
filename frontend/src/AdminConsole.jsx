import { useState } from 'react';
import StaffAdmin from './StaffAdmin.jsx';
import RotationAdmin from './RotationAdmin.jsx';
import HolidaysAdmin from './HolidaysAdmin.jsx';

// Staff management is not a daily-operations screen. Never wrap this console
// in BusinessScreen or require business-status/snapshot data to render it.
export default function AdminConsole() {
  const [tab, setTab] = useState('Staff');
  return <main className="admin-console">
    <span className="eyebrow">Administration</span><h1>Administrator console</h1>
    <p className="intro">Manage staff access, weekly duties, and center holidays.</p>
    <div className="admin-tabs" role="group" aria-label="Administration sections">
      {['Staff', 'Rotation', 'Holidays'].map(name => <button key={name} className={tab === name ? 'primary' : 'secondary'}
        aria-pressed={tab === name} onClick={() => setTab(name)}>{name}</button>)}
    </div>
    {tab === 'Staff' ? <StaffAdmin /> : tab === 'Rotation' ? <RotationAdmin /> : <HolidaysAdmin />}
  </main>;
}
