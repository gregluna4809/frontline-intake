import React from 'react';
import { afterEach, beforeEach, expect, test, vi } from 'vitest';
import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import App from './App.jsx';
import { saveAuth } from './auth.js';

const ok = data => ({ ok: true, status: 200, json: async () => structuredClone(data) });
beforeEach(() => {
  window.history.replaceState({}, '', '/admin');
  saveAuth({ token: 'admin-test-token', staffId: 10, role: 'ADMINISTRATOR', displayName: 'Demo Administrator' });
  vi.spyOn(Date, 'now').mockReturnValue(Date.parse('2026-09-14T16:00:00Z'));
});
afterEach(() => { cleanup(); sessionStorage.clear(); vi.restoreAllMocks(); vi.unstubAllGlobals(); });

function fixture() {
  const state = {
    staff: [
      { id: 1, displayName: 'Demo LSR', role: 'LSR', active: true, accountId: 1, username: 'staff1' },
      { id: 2, displayName: 'Demo Clerk', role: 'CLERK', active: true, accountId: null, username: null },
      { id: 3, displayName: 'Demo Inactive', role: 'LSR', active: false, accountId: null, username: null },
    ],
    rotation: Array.from({ length: 7 }, (_, index) => ({ staffId: 1, displayName: 'Demo LSR', dayOfWeek: index + 1, dutyType: 'APPOINTMENTS' })),
    holidays: [{ id: 1, date: '2026-09-07', label: 'Labor Day' }, { id: 2, date: '2026-12-25', label: 'Christmas Day' }],
  };
  const fetchMock = vi.fn(async (path, options = {}) => {
    const method = options.method || 'GET';
    const body = options.body ? JSON.parse(options.body) : null;
    if (path === '/api/admin/staff' && method === 'GET') return ok(state.staff);
    if (path === '/api/admin/staff' && method === 'POST') {
      const person = { id: 4, ...body, active: true, accountId: null, username: null };
      state.staff.push(person); return ok(person);
    }
    if (path.match(/^\/api\/admin\/staff\/\d+$/) && method === 'PUT') {
      const person = state.staff.find(item => item.id === Number(path.split('/').at(-1)));
      Object.assign(person, body); return ok(person);
    }
    if (path === '/api/admin/accounts' && method === 'POST') {
      Object.assign(state.staff.find(person => person.id === body.staffId), { accountId: 2, username: body.username });
      return ok({ id: 2, staffId: body.staffId, username: body.username });
    }
    if (path === '/api/admin/accounts/2/reset-password' && method === 'PUT') return ok({ id: 2 });
    if (path === '/api/admin/rotation' && method === 'GET') return ok(state.rotation);
    if (path === '/api/admin/rotation' && method === 'PUT') {
      state.rotation = body.assignments.map(row => ({ ...row, displayName: 'Demo LSR' })); return ok(state.rotation);
    }
    if (path === '/api/admin/holidays' && method === 'GET') return ok(state.holidays);
    if (path === '/api/admin/holidays' && method === 'POST') {
      const holiday = { id: 3, ...body }; state.holidays.push(holiday); return ok(holiday);
    }
    if (path.startsWith('/api/admin/holidays/') && method === 'DELETE') {
      state.holidays = state.holidays.filter(item => item.id !== Number(path.split('/').at(-1)));
      return { ok: true, status: 204, json: async () => { throw new Error('204 has no body'); } };
    }
    throw new Error('Unexpected request: ' + method + ' ' + path);
  });
  vi.stubGlobal('fetch', fetchMock);
  return { state, fetchMock };
}

test('staff console adds, edits, and deactivates staff without deleting rows', async () => {
  const { state, fetchMock } = fixture(); render(<App />);
  const user = userEvent.setup();
  await screen.findByText('Demo Clerk');
  const add = screen.getByRole('form', { name: 'Add staff member' });
  await user.type(within(add).getByLabelText('Display name'), 'Demo Added');
  await user.selectOptions(within(add).getByLabelText('Staff role'), 'CLERK');
  await user.click(within(add).getByRole('button', { name: 'Add staff' }));
  await screen.findByText('Demo Added');
  await user.click(screen.getByRole('button', { name: 'Edit Demo Added' }));
  const edit = screen.getByRole('form', { name: 'Edit staff member' });
  await user.clear(within(edit).getByLabelText('Display name'));
  await user.type(within(edit).getByLabelText('Display name'), 'Demo Updated');
  await user.selectOptions(within(edit).getByLabelText('Staff role'), 'LSR');
  await user.click(within(edit).getByRole('button', { name: 'Save staff changes' }));
  await screen.findByText('Demo Updated');
  await user.click(screen.getByRole('button', { name: 'Deactivate Demo Updated' }));
  await screen.findByRole('button', { name: 'Reactivate Demo Updated' });
  expect(state.staff.find(person => person.id === 4)).toMatchObject({ displayName: 'Demo Updated', role: 'LSR', active: false });
  expect(fetchMock.mock.calls.some(([, options]) => options.method === 'DELETE')).toBe(false);
  expect(fetchMock.mock.calls.every(([path, options]) => path.startsWith('/api/admin/') && options.headers.Authorization === 'Bearer admin-test-token')).toBe(true);
  expect(screen.queryByText(/Career center is closed/)).toBeNull();
});

test('staff account actions create credentials and reset a password', async () => {
  const { fetchMock } = fixture(); render(<App />);
  const user = userEvent.setup();
  await user.click(await screen.findByRole('button', { name: 'Create account for Demo Clerk' }));
  await user.type(screen.getByLabelText('Username'), 'new.clerk');
  await user.type(screen.getByLabelText('Initial password'), 'initial123');
  await user.click(screen.getByRole('button', { name: 'Create login' }));
  await screen.findByText('new.clerk');
  expect(screen.queryByLabelText('Initial password')).toBeNull();
  await user.click(screen.getByRole('button', { name: 'Reset password for Demo Clerk' }));
  await user.type(screen.getByLabelText('New password'), 'replacement123');
  await user.click(screen.getByRole('button', { name: 'Set new password' }));
  await screen.findByText('Password reset.');
  expect(fetchMock).toHaveBeenCalledWith('/api/admin/accounts', expect.objectContaining({ method: 'POST', body: JSON.stringify({ staffId: 2, username: 'new.clerk', password: 'initial123' }) }));
  expect(fetchMock).toHaveBeenCalledWith('/api/admin/accounts/2/reset-password', expect.objectContaining({ method: 'PUT', body: JSON.stringify({ password: 'replacement123' }) }));
  expect(screen.queryByLabelText('New password')).toBeNull();
});

test('weekly grid edits active LSR duties and sends a complete seven-day grid', async () => {
  const { state, fetchMock } = fixture(); render(<App />);
  const user = userEvent.setup();
  await user.click(screen.getByRole('button', { name: 'Rotation', exact: true }));
  const monday = await screen.findByRole('combobox', { name: 'Monday duty for Demo LSR' });
  expect(screen.getAllByRole('combobox')).toHaveLength(7);
  expect(screen.queryByRole('combobox', { name: /Demo Inactive/ })).toBeNull();
  await user.selectOptions(monday, 'RESOURCE_ROOM');
  await user.click(screen.getByRole('button', { name: 'Save rotation' }));
  await screen.findByText('Weekly rotation saved.');
  await waitFor(() => expect(screen.getByRole('button', { name: 'Save rotation' }).disabled).toBe(true));
  expect(state.rotation[0].dutyType).toBe('RESOURCE_ROOM');
  const body = JSON.parse(fetchMock.mock.calls.find(([path, options]) => path === '/api/admin/rotation' && options.method === 'PUT')[1].body);
  expect(body.assignments).toHaveLength(7);
  expect(body.assignments[0]).toEqual({ staffId: 1, dayOfWeek: 1, dutyType: 'RESOURCE_ROOM' });
});

test('holidays list upcoming dates, can show past dates, and support add/remove', async () => {
  const { state } = fixture(); render(<App />);
  const user = userEvent.setup();
  await user.click(screen.getByRole('button', { name: 'Holidays', exact: true }));
  await screen.findByText('Christmas Day');
  expect(screen.queryByText('Labor Day')).toBeNull();
  await user.click(screen.getByLabelText('Include past holidays'));
  expect(screen.getByText('Labor Day')).toBeTruthy();
  fireEvent.change(screen.getByLabelText('Holiday date'), { target: { value: '2026-12-24' } });
  await user.type(screen.getByLabelText('Holiday label'), 'Demo closure');
  await user.click(screen.getByRole('button', { name: 'Add holiday' }));
  await screen.findByText('Demo closure');
  await user.click(screen.getByRole('button', { name: 'Remove Demo closure on 2026-12-24' }));
  await screen.findByText('Holiday removed.');
  await waitFor(() => expect(screen.queryByText('Demo closure')).toBeNull());
  expect(state.holidays).toHaveLength(2);
});

test('failed loads can be retried and rejected writes retain the form input', async () => {
  const { fetchMock } = fixture();
  const original = fetchMock.getMockImplementation();
  fetchMock.mockResolvedValueOnce({ ok: false, status: 500, json: async () => ({ detail: 'Staff unavailable.' }) });
  render(<App />);
  const user = userEvent.setup();
  expect((await screen.findByRole('alert')).textContent).toBe('Staff unavailable.');
  await user.click(screen.getByRole('button', { name: 'Refresh staff' }));
  await screen.findByText('Demo Clerk');
  fetchMock.mockImplementation((path, options) => options.method === 'POST'
    ? Promise.resolve({ ok: false, status: 409, json: async () => ({ detail: 'Conflict: reload and retry.' }) }) : original(path, options));
  await user.type(screen.getByLabelText('Display name'), 'Keep this input');
  await user.click(screen.getByRole('button', { name: 'Add staff' }));
  expect((await screen.findByRole('alert')).textContent).toContain('Conflict');
  expect(screen.getByLabelText('Display name').value).toBe('Keep this input');
});
