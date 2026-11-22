import { stubFetch } from './testPhase2Reads.js';
import { getAuth, saveAuth } from './auth.js';
import React from 'react';
import { afterEach, beforeEach, expect, test, vi } from 'vitest';
import { act, cleanup, render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import App from './App.jsx';
import SnapshotView from './SnapshotView.jsx';
import { businessStatus, staff, rotation, ok } from './testQueues.js';

const snapshot = {
  businessDate: '2026-09-08', staff, coverage: rotation(), callOuts: [],
  visits: [
    { id: 1, ticket: 'B0001', visitType: 'WALK_IN', reason: 'PHONE_ACCESS', status: 'AT_RESOURCE', customer: { displayName: 'Demo Customer 001' } },
    { id: 2, ticket: 'A0001', visitType: 'APPOINTMENT', appointmentCategory: 'MANDATORY', status: 'CLOSED', assignedStaffId: 1 },
  ],
  resources: [{ id: 1, label: 'Phone 01', type: 'PHONE', status: 'OCCUPIED', ticket: 'B0001' }],
};
const closed = { isOpen: false, currentBusinessDate: snapshot.businessDate, lastCompletedBusinessDate: snapshot.businessDate };

test('old-shape snapshot resources render with Resource Room fallback', () => {
  render(<SnapshotView snapshot={snapshot} role="resource-room" />);
  expect(screen.getByText('Phone 01')).toBeTruthy();
  expect(screen.getByText('Resource Room')).toBeTruthy();
  expect(screen.getByText('OCCUPIED')).toBeTruthy();
});
beforeEach(() => { saveAuth({ token: 'test-manager-token', staffId: 9, role: 'CENTER_MANAGER', displayName: 'Demo Manager' }); });
afterEach(() => { sessionStorage.clear(); cleanup(); vi.useRealTimers(); vi.unstubAllGlobals(); window.history.replaceState({}, '', '/kiosk'); });

function mockBusiness(initial = closed) {
  const state = { status: initial };
  const fetchMock = vi.fn(async path => {
    if (path === '/api/business-status') return ok(state.status);
    if (path === '/api/snapshot?date=' + snapshot.businessDate) return ok(snapshot);
    if (path === '/api/staff') return ok(staff);
    if (path === '/api/simulator/status') return ok({ running: true, arrivalsPerMinute: 2 });
    if (path.startsWith('/api/visits?')) return ok([]);
    throw new Error('Unexpected request: ' + path);
  });
  stubFetch(fetchMock);
  return { state, fetchMock };
}

test.each(['/kiosk', '/front-desk', '/supervisor', '/my-queue?staff=1', '/resource-room'])(
  '%s renders frozen data without mutation controls or live requests', async path => {
    window.history.replaceState({}, '', path);
    const { fetchMock } = mockBusiness();
    render(<App />);
    await screen.findByRole('table');
    expect(screen.getByText('Demo Manager')).toBeTruthy();
    expect(screen.getByText('Center Manager')).toBeTruthy();
    expect(screen.getByRole('button', { name: 'Logout' })).toBeTruthy();
    expect(screen.queryByRole('link', { name: 'Staff login' })).toBeNull();
    expect(screen.getByRole('status').textContent).toContain("2026-09-08's final activity");
    expect(screen.queryAllByRole('button').filter(button => button.textContent !== 'Logout')).toHaveLength(0);
    expect(screen.queryAllByRole('radio')).toHaveLength(0);
    expect(screen.queryAllByRole('textbox')).toHaveLength(0);
    expect(fetchMock.mock.calls.every(([url]) => url === '/api/business-status' || url.startsWith('/api/snapshot?'))).toBe(true);
    if (path.startsWith('/my-queue')) {
      expect(screen.getByText('A0001')).toBeTruthy();
      expect(screen.queryByText('B0001')).toBeNull();
    }
    if (path === '/supervisor') expect(screen.getByRole('heading', { name: 'Coverage at close' })).toBeTruthy();
    if (path === '/resource-room') expect(screen.getByRole('heading', { name: 'Resources at close' })).toBeTruthy();
  },
);

test('closed mode fetches its snapshot once and returns to interactive kiosk when hours open', async () => {
  vi.useFakeTimers();
  const { state, fetchMock } = mockBusiness();
  render(<App />);
  await act(async () => {});
  expect(screen.getByRole('table')).toBeTruthy();
  await act(async () => { await vi.advanceTimersByTimeAsync(15000); });
  expect(fetchMock.mock.calls.filter(([path]) => path.startsWith('/api/snapshot?'))).toHaveLength(1);
  expect(screen.queryAllByRole('button').filter(button => button.textContent !== 'Logout')).toHaveLength(0);
  state.status = businessStatus;
  await act(async () => { await vi.advanceTimersByTimeAsync(5000); });
  expect(screen.getByRole('button', { name: 'Check in' })).toBeTruthy();
  expect(screen.queryByRole('table')).toBeNull();
  expect(screen.queryByText(/Career center is closed/)).toBeNull();
});

test('closing unmounts live front desk polling and hides every action', async () => {
  vi.useFakeTimers();
  window.history.replaceState({}, '', '/front-desk');
  const { state, fetchMock } = mockBusiness(businessStatus);
  render(<App />);
  await act(async () => {});
  expect(screen.getByRole('button', { name: 'Refresh queue' })).toBeTruthy();
  state.status = closed;
  await act(async () => { await vi.advanceTimersByTimeAsync(5000); });
  expect(screen.getByRole('table')).toBeTruthy();
  expect(screen.queryAllByRole('button').filter(button => button.textContent !== 'Logout')).toHaveLength(0);
  const liveCalls = () => fetchMock.mock.calls.filter(([path]) => path.startsWith('/api/visits?')).length;
  const stoppedAt = liveCalls();
  await act(async () => { await vi.advanceTimersByTimeAsync(15000); });
  expect(liveCalls()).toBe(stoppedAt);
});

test('snapshot load failure never falls back to interactive screens', async () => {
  vi.stubGlobal('fetch', vi.fn(async path => path === '/api/business-status' ? ok(closed)
    : { ok: false, status: 500, json: async () => ({ detail: 'Snapshot unavailable.' }) }));
  render(<App />);
  expect(await screen.findByRole('alert')).toBeTruthy();
  expect(screen.queryAllByRole('button').filter(button => button.textContent !== 'Logout')).toHaveLength(0);
  expect(screen.queryAllByRole('radio')).toHaveLength(0);
  expect(screen.getByRole('status').textContent).toContain('Career center is closed');
});

test.each([businessStatus, closed])('public kiosk offers working Staff login access when isOpen=$isOpen', async status => {
  sessionStorage.clear();
  const { fetchMock } = mockBusiness(status);
  render(<App />);
  // Available immediately, before business-status or snapshot loading completes.
  const link = screen.getByRole('link', { name: 'Staff login' });
  expect(link.getAttribute('href')).toBe('/login');
  if (status.isOpen) await screen.findByRole('button', { name: 'Check in' });
  else await screen.findByRole('table');
  await userEvent.setup().click(link);
  expect(await screen.findByRole('heading', { name: 'Log in to Frontline' })).toBeTruthy();
  expect(window.location.pathname).toBe('/login');
  expect(screen.getByLabelText('Username').disabled).toBe(false);
  expect(screen.queryByText(/Career center is closed/)).toBeNull();
  expect(fetchMock.mock.calls.every(([path]) => path === '/api/business-status' || path.startsWith('/api/snapshot?'))).toBe(true);
});

test.each(['/kiosk', '/front-desk'])('closed-hours identity and Logout work from %s', async path => {
  window.history.replaceState({}, '', path);
  mockBusiness();
  render(<App />);
  await screen.findByRole('table');
  expect(screen.getByText('Demo Manager')).toBeTruthy();
  expect(screen.getByText('Center Manager')).toBeTruthy();
  await userEvent.setup().click(screen.getByRole('button', { name: 'Logout' }));
  expect(await screen.findByRole('heading', { name: 'Log in to Frontline' })).toBeTruthy();
  expect(getAuth()).toBeNull();
  expect(screen.getByRole('link', { name: 'Staff login' })).toBeTruthy();
});

test.each([true, false])('login and admin remain functional without consulting business hours (isOpen=%s)', async isOpen => {
  sessionStorage.clear();
  window.history.replaceState({}, '', '/login');
  const fetchMock = vi.fn(async path => {
    if (path === '/api/sandbox/state') return { ok: false, status: 404, json: async () => null };
    if (path === '/api/business-status') return ok({ ...closed, isOpen });
    if (path === '/api/admin/staff') return ok([]);
    if (path === '/api/auth/login') return ok({ token: 'admin-token', staffId: 10, role: 'ADMINISTRATOR', displayName: 'Demo Administrator' });
    throw new Error('Unexpected request: ' + path);
  });
  vi.stubGlobal('fetch', fetchMock);
  render(<App />);
  const user = userEvent.setup();
  await user.type(screen.getByLabelText('Username'), 'staff10');
  await user.type(screen.getByLabelText('Password'), 'demo1234');
  await user.click(screen.getByRole('button', { name: 'Log in' }));
  expect(await screen.findByRole('heading', { name: 'Administrator console' })).toBeTruthy();
  expect(window.location.pathname).toBe('/admin');
  expect(screen.getByText('Demo Administrator')).toBeTruthy();
  expect(screen.getByRole('button', { name: 'Logout' })).toBeTruthy();
  expect(screen.queryByText(/Career center is closed/)).toBeNull();
  expect(fetchMock.mock.calls.map(([path]) => path)).toEqual(['/api/sandbox/state', '/api/auth/login', '/api/admin/staff']);
});
