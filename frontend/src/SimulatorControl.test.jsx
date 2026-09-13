import React from 'react';
import { afterEach, expect, test, vi } from 'vitest';
import { cleanup, render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import SimulatorControl from './SimulatorControl.jsx';
afterEach(() => { cleanup(); vi.unstubAllGlobals(); });
test('simulator loads rate and starts and stops through the backend', async () => {
  let running = false;
  const fetchMock = vi.fn(async (path) => {
    if (path === '/api/business-status') return { ok: true, json: async () => ({
      isOpen: true, currentBusinessDate: '2026-09-08', lastCompletedBusinessDate: '2026-09-04',
    }) };
    if (path.endsWith('/start')) running = true;
    if (path.endsWith('/stop')) running = false;
    return { ok: true, json: async () => ({ running, arrivalsPerMinute: 4, createdSinceStart: 0 }) };
  });
  vi.stubGlobal('fetch', fetchMock);
  render(<SimulatorControl />);
  expect(await screen.findByText('4/min')).toBeTruthy();
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
