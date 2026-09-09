import React from 'react';
import { afterEach, expect, test, vi } from 'vitest';
import { cleanup, render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import App from './App.jsx';
import { waitingTime } from './Appointments.jsx';

afterEach(() => {
  cleanup();
  vi.restoreAllMocks();
  vi.unstubAllGlobals();
  window.history.replaceState({}, '', '/kiosk');
});

function appointment() {
  return {
    id: 1, ticket: 'A0001', visitType: 'APPOINTMENT', status: 'WAITING_STAFF',
    appointmentCategory: 'CAREER_SERVICES', version: 2,
    customer: { id: 1, displayName: 'Demo Customer 001' },
    createdAt: new Date(Date.now() - 7200000).toISOString(),
    waitingSince: new Date(Date.now() - 300000).toISOString(),
  };
}
const ok = data => ({ ok: true, json: async () => data });

test('appointments route shows customer, category, staff waiting time and closes the visit', async () => {
  vi.spyOn(Date, 'now').mockReturnValue(Date.parse('2026-09-08T12:00:00Z'));
  window.history.replaceState({}, '', '/appointments');
  let closed = false;
  const fetchMock = vi.fn(async path => {
    if (path === '/api/visits?status=WAITING_STAFF') return ok(closed ? [] : [appointment()]);
    if (path === '/api/visits/1/close') { closed = true; return ok({}); }
    throw new Error('Unexpected request');
  });
  vi.stubGlobal('fetch', fetchMock);
  render(<App />);
  expect(await screen.findByText('A0001')).toBeTruthy();
  expect(screen.getByRole('combobox', { name: 'Role' }).value).toBe('appointments');
  expect(screen.getByText('Demo Customer 001')).toBeTruthy();
  expect(screen.getByText('Career Services')).toBeTruthy();
  expect(screen.getByText('5 min')).toBeTruthy();
  expect(screen.getByText('Synthetic demo — no real data')).toBeTruthy();
  await userEvent.setup().click(screen.getByRole('button', { name: 'Close A0001' }));
  expect(await screen.findByText('Ticket A0001 closed.')).toBeTruthy();
  expect(screen.queryByRole('button', { name: 'Close A0001' })).toBeNull();
  expect(fetchMock).toHaveBeenCalledWith('/api/visits/1/close', expect.objectContaining({
    method: 'POST', headers: expect.objectContaining({ 'If-Match': '"2"' }),
  }));
});

test('stale close refreshes the queue and retry uses the current version', async () => {
  window.history.replaceState({}, '', '/appointments');
  let version = 2;
  let closed = false;
  const fetchMock = vi.fn(async path => {
    if (path === '/api/visits?status=WAITING_STAFF') return ok(closed ? [] : [{ ...appointment(), version }]);
    if (version === 2) {
      version = 3;
      return { ok: false, json: async () => ({ detail: 'This visit was changed by another action.' }) };
    }
    closed = true;
    return ok({});
  });
  vi.stubGlobal('fetch', fetchMock);
  render(<App />);
  const user = userEvent.setup();
  await user.click(await screen.findByRole('button', { name: 'Close A0001' }));
  expect(await screen.findByRole('alert')).toBeTruthy();
  await waitFor(() => expect(screen.getByRole('button', { name: 'Close A0001' }).disabled).toBe(false));
  await user.click(screen.getByRole('button', { name: 'Close A0001' }));
  expect(await screen.findByText('Ticket A0001 closed.')).toBeTruthy();
  const calls = fetchMock.mock.calls.filter(([path]) => path === '/api/visits/1/close');
  expect(calls.map(([, options]) => options.headers['If-Match'])).toEqual(['"2"', '"3"']);
});

test('an unavailable appointment queue shows an error instead of an empty success', async () => {
  window.history.replaceState({}, '', '/appointments');
  vi.stubGlobal('fetch', vi.fn().mockRejectedValue(new TypeError('Offline')));
  render(<App />);
  expect(await screen.findByRole('alert')).toBeTruthy();
  expect(screen.getByText('Queue unavailable.')).toBeTruthy();
  expect(screen.queryByText('No appointments waiting for staff.')).toBeNull();
});

test('waiting time handles short waits, hours and missing timestamps', () => {
  const now = Date.parse('2026-09-08T12:00:00Z');
  expect(waitingTime('2026-09-08T11:59:30Z', now)).toBe('Less than 1 min');
  expect(waitingTime('2026-09-08T10:55:00Z', now)).toBe('1 hr 5 min');
  expect(waitingTime(null, now)).toBe('—');
});
