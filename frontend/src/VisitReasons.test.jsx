import React from 'react';
import { afterEach, expect, test, vi } from 'vitest';
import { cleanup, render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import App from './App.jsx';
import { saveAuth } from './auth.js';

vi.mock('./SimulatorControl.jsx', () => ({ default: () => null }));
afterEach(() => { cleanup(); sessionStorage.clear(); vi.restoreAllMocks(); vi.unstubAllGlobals(); window.history.replaceState({}, '', '/kiosk'); });

test.each([['2026-11-25', 'PHONE_ACCESS', 'Phone Access', 'UI account help'],
  ['2026-11-30', 'UI_ACCOUNT', 'UI account help', 'Phone Access']])(
  'kiosk loads only the reasons active on %s', async (date, code, label, absent) => {
    vi.spyOn(Date, 'now').mockReturnValue(Date.parse(date + 'T17:00:00Z'));
    window.history.replaceState({}, '', '/kiosk');
    const fetchMock = vi.fn(async path => {
      if (path === '/api/business-status') return { ok: true, json: async () => ({ isOpen: true, currentBusinessDate: date }) };
      if (path === '/api/visit-reasons?date=' + date) return { ok: true, json: async () => [{ code, label }] };
      if (path === '/api/visits') return { ok: true, json: async () => ({ id: 1, ticket: 'B0001', visitType: 'WALK_IN', reason: code }) };
      throw new Error('Unexpected request: ' + path);
    });
    vi.stubGlobal('fetch', fetchMock);
    render(<App />);
    const user = userEvent.setup();
    await user.click(await screen.findByRole('radio', { name: 'Walk-In' }));
    await user.click(await screen.findByRole('radio', { name: new RegExp(label) }));
    expect(screen.queryByRole('radio', { name: new RegExp(absent) })).toBeNull();
    await user.click(screen.getByRole('button', { name: 'Check in' }));
    expect(await screen.findByText('B0001')).toBeTruthy();
    expect(fetchMock).toHaveBeenCalledWith('/api/visit-reasons?date=' + date, expect.objectContaining({ headers: expect.not.objectContaining({ Authorization: expect.anything() }) }));
    expect(fetchMock).toHaveBeenCalledWith('/api/visits', expect.objectContaining({ body: JSON.stringify({ visitType: 'WALK_IN', reason: code }) }));
  },
);

test('front desk uses dated reason labels from the endpoint', async () => {
  const date = '2026-11-30';
  vi.spyOn(Date, 'now').mockReturnValue(Date.parse(date + 'T17:00:00Z'));
  saveAuth({ token: 'synthetic-token', staffId: 6, role: 'CLERK', displayName: 'Demo Clerk' });
  window.history.replaceState({}, '', '/front-desk');
  vi.stubGlobal('fetch', async path => ({ ok: true, json: async () => {
    if (path === '/api/business-status') return { isOpen: true, currentBusinessDate: date };
    if (path === '/api/visit-reasons?date=' + date) return [{ code: 'UI_ACCOUNT', label: 'UI account help' }];
    if (path === '/api/visits?status=CHECKED_IN') return [{ id: 1, ticket: 'B0001', visitType: 'WALK_IN', reason: 'UI_ACCOUNT', status: 'CHECKED_IN', createdAt: date + 'T16:50:00Z' }];
    if (path === '/api/visits?status=IDENTIFIED') return [];
    throw new Error('Unexpected request: ' + path);
  } }));
  render(<App />);
  expect(await screen.findByText('UI account help')).toBeTruthy();
  expect(screen.queryByText('Phone Access')).toBeNull();
});
