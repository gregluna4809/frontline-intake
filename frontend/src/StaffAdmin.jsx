import { useState } from 'react';
import { roleLabels } from './auth.js';
import useAdminData, { AdminFeedback } from './useAdminData.jsx';

const emptyStaff = { displayName: '', role: 'LSR', active: true };

export default function StaffAdmin() {
  const state = useAdminData('/api/admin/staff');
  const [editing, setEditing] = useState(null);
  const [form, setForm] = useState(emptyStaff);
  const [account, setAccount] = useState(null);
  const [username, setUsername] = useState('');
  const [password, setPassword] = useState('');

  async function saveStaff(event) {
    event.preventDefault();
    if (await state.save('/api/admin/staff' + (editing ? '/' + editing.id : ''), editing ? 'PUT' : 'POST',
      { displayName: form.displayName.trim(), role: form.role, ...(editing ? { active: form.active } : {}) },
      editing ? 'Staff member updated.' : 'Staff member added.')) {
      setEditing(null); setForm(emptyStaff);
    }
  }

  async function saveAccount(event) {
    event.preventDefault();
    const reset = account.accountId != null;
    if (await state.save(reset ? '/api/admin/accounts/' + account.accountId + '/reset-password' : '/api/admin/accounts',
      reset ? 'PUT' : 'POST', reset ? { password } : { staffId: account.id, username: username.trim(), password },
      reset ? 'Password reset.' : 'Login account created.')) {
      setAccount(null); setUsername(''); setPassword('');
    }
  }

  return <section aria-label="Staff management">
    <div className="section-heading"><h2>Staff</h2><button className="secondary" disabled={state.loading || state.saving} onClick={state.refresh}>Refresh staff</button></div>
    <AdminFeedback state={state} />
    <form className="admin-form" onSubmit={saveStaff} aria-label={editing ? 'Edit staff member' : 'Add staff member'}>
      <h3>{editing ? 'Edit staff member' : 'Add staff member'}</h3>
      <fieldset disabled={state.disabled}>
        <label>Display name<input required maxLength={100} value={form.displayName} onChange={event => setForm({ ...form, displayName: event.target.value })} /></label>
        <label>Staff role<select value={form.role} onChange={event => setForm({ ...form, role: event.target.value })}>
          {Object.entries(roleLabels).map(([value, label]) => <option key={value} value={value}>{label}</option>)}
        </select></label>
        {editing && <label className="admin-checkbox"><input type="checkbox" checked={form.active} onChange={event => setForm({ ...form, active: event.target.checked })} />Active</label>}
        <div className="admin-actions"><button className="primary" disabled={!form.displayName.trim()}>{editing ? 'Save staff changes' : 'Add staff'}</button>
          {editing && <button type="button" className="secondary" onClick={() => { setEditing(null); setForm(emptyStaff); }}>Cancel edit</button>}
        </div>
      </fieldset>
    </form>
    <div className="queue-panel table-scroll"><table>
      <caption className="sr-only">All staff, including inactive members</caption>
      <thead><tr><th>Name</th><th>Role</th><th>Status</th><th>Account</th><th>Actions</th></tr></thead>
      <tbody>{state.data?.map(person => <tr key={person.id}>
        <td>{person.displayName}</td><td>{roleLabels[person.role]}</td><td>{person.active ? 'Active' : 'Inactive'}</td><td>{person.username || 'No login account'}</td>
        <td><div className="admin-actions">
          <button className="secondary" disabled={state.disabled} aria-label={'Edit ' + person.displayName}
            onClick={() => { setEditing(person); setForm({ displayName: person.displayName, role: person.role, active: person.active }); }}>Edit</button>
          <button className="secondary" disabled={state.disabled} aria-label={(person.active ? 'Deactivate ' : 'Reactivate ') + person.displayName}
            onClick={async () => {
              if (await state.save('/api/admin/staff/' + person.id, 'PUT', { displayName: person.displayName, role: person.role, active: !person.active },
                person.active ? 'Staff member deactivated. Historical assignments are preserved.' : 'Staff member reactivated.')) {
                setEditing(null); setForm(emptyStaff); setAccount(null); setPassword('');
              }
            }}>{person.active ? 'Deactivate' : 'Reactivate'}</button>
          <button className="secondary" disabled={state.disabled || (!person.active && !person.accountId)}
            aria-label={(person.accountId ? 'Reset password for ' : 'Create account for ') + person.displayName}
            onClick={() => { setAccount(person); setUsername(''); setPassword(''); }}>{person.accountId ? 'Reset password' : 'Create account'}</button>
        </div></td>
      </tr>)}</tbody>
    </table></div>
    {account && <form className="admin-form" onSubmit={saveAccount} aria-label="Manage login account">
      <h3>{account.accountId ? 'Reset password' : 'Create login account'} — {account.displayName}</h3>
      <fieldset disabled={state.disabled}>
        {account.accountId ? <p>Username: {account.username}</p> : <label>Username<input required maxLength={100} pattern="[A-Za-z0-9][A-Za-z0-9._-]*" autoComplete="off"
          value={username} onChange={event => setUsername(event.target.value)} /></label>}
        <label>{account.accountId ? 'New password' : 'Initial password'}<input type="password" required minLength={8} maxLength={72} autoComplete="new-password"
          value={password} onChange={event => setPassword(event.target.value)} /></label>
        <p className="queue-note">Use 8–72 characters (at most 72 UTF-8 bytes).</p>
        <div className="admin-actions"><button className="primary">{account.accountId ? 'Set new password' : 'Create login'}</button>
          <button type="button" className="secondary" onClick={() => { setAccount(null); setPassword(''); }}>Cancel account changes</button></div>
      </fieldset>
    </form>}
  </section>;
}
