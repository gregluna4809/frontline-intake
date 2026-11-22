import React from 'react';
import { afterEach, beforeEach, expect, test, vi } from 'vitest';
import { cleanup, render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import SimulatorControl from './SimulatorControl.jsx';
import { saveAuth } from './auth.js';
beforeEach(() => { saveAuth({ token: 'synthetic', staffId: 1, role: 'LSR', displayName: 'Demo Staff' }); });
afterEach(() => { cleanup(); sessionStorage.clear(); vi.unstubAllGlobals(); });
test('simulator loads target and arrivals and starts and stops through the backend', async () => {
  let running = false;
  const fetchMock = vi.fn(async (path) => {
    if (path === '/api/business-status') return { ok: true, json: async () => ({
      isOpen: true, currentBusinessDate: '2026-09-08', lastCompletedBusinessDate: '2026-09-04',
    }) };
    if (path.endsWith('/start')) running = true;
    if (path.endsWith('/stop')) running = false;
    return { ok: true, json: async () => ({ running, targetArrivals: 275, arrivalsSoFar: 42, openTaskCount: 3,
      scenario: 'SHIFT_TO_ONLINE', openHelpRequests: 2, appointmentsWaiting: 4, appointmentsBeingServed: 1 }) };
  });
  vi.stubGlobal('fetch', fetchMock);
  render(<SimulatorControl />);
  expect(await screen.findByText('42 / 275 arrivals')).toBeTruthy();
  expect(screen.getByText('Scenario SHIFT_TO_ONLINE')).toBeTruthy();
  expect(screen.getByText('Help 2 · Appointments waiting 4 · Serving 1')).toBeTruthy();
  expect(screen.getByText('Stopped')).toBeTruthy();
  expect(screen.getByText('Open')).toBeTruthy();
  expect(screen.getByText('Business day 2026-09-08')).toBeTruthy();
  const user = userEvent.setup();
  await user.click(screen.getByRole('button', { name: 'Start' }));
  expect(await screen.findByText('Running')).toBeTruthy();
  await user.click(screen.getByRole('button', { name: 'Stop' }));
  expect(await screen.findByText('Stopped')).toBeTruthy();
  expect(fetchMock).toHaveBeenCalledWith('/api/simulator/start', expect.objectContaining({ method: 'POST' }));
  expect(fetchMock).toHaveBeenCalledWith('/api/simulator/stop', expect.objectContaining({ method: 'POST' }));
});

test.each(['CENTER_MANAGER', 'ADMINISTRATOR', 'SUPERVISING_LSR', 'LSR'])(
  'switch toggles are writable only for managers and administrators (%s)', async role => {
    saveAuth({ token: 'synthetic', staffId: role === 'LSR' ? 1 : role === 'CENTER_MANAGER' ? 9 : role === 'ADMINISTRATOR' ? 10 : 5,
      role, displayName: 'Demo Staff' });
    const switches = ['ARRIVALS', 'CLERKS', 'RR_LSRS', 'APPOINTMENT_LSRS'].map(role => ({ role, enabled: true }));
    const fetchMock = vi.fn(async (path, options = {}) => {
      if (path === '/api/business-status') return { ok: true, json: async () => ({ isOpen: true, currentBusinessDate: '2026-11-30' }) };
      if (path === '/api/simulator/switches') return { ok: true, json: async () => switches };
      if (path === '/api/simulator/switches/ARRIVALS') return { ok: true, json: async () => ({ role: 'ARRIVALS', enabled: JSON.parse(options.body).enabled }) };
      return { ok: true, json: async () => ({ running: true, targetArrivals: 275, arrivalsSoFar: 42 }) };
    });
    vi.stubGlobal('fetch', fetchMock);
    render(<SimulatorControl />);
    await screen.findByText('42 / 275 arrivals');
    if (role === 'CENTER_MANAGER' || role === 'ADMINISTRATOR') {
      expect(screen.getAllByRole('checkbox')).toHaveLength(4);
      await userEvent.setup().click(screen.getByRole('checkbox', { name: 'ARRIVALS' }));
      await waitFor(() => expect(screen.getByRole('checkbox', { name: 'ARRIVALS' }).checked).toBe(false));
      expect(fetchMock).toHaveBeenCalledWith('/api/simulator/switches/ARRIVALS', expect.objectContaining({ method: 'PUT', body: '{"enabled":false}' }));
    } else expect(screen.queryAllByRole('checkbox')).toHaveLength(0);
  },
);
test('unavailable status disables start and reports an error', async () => {
  vi.stubGlobal('fetch', vi.fn().mockRejectedValue(new Error('Offline')));
  render(<SimulatorControl />);
  expect(await screen.findByRole('alert')).toBeTruthy();
  await waitFor(() => expect(screen.getByRole('button', { name: 'Start' }).disabled).toBe(true));
});


test('closed business status displays the most recently completed business date', async () => {
  vi.stubGlobal('fetch', vi.fn(async path => ({ ok: true, json: async () => path === '/api/business-status'
    ? { isOpen: false, currentBusinessDate: '2026-09-04', lastCompletedBusinessDate: '2026-09-04' }
    : { running: false, arrivalsPerMinute: 4, createdSinceStart: 0 } })));
  render(<SimulatorControl />);
  expect(await screen.findByText('Closed')).toBeTruthy();
  expect(screen.getByText('Business day 2026-09-04')).toBeTruthy();
});
