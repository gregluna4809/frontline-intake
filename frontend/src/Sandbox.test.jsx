import React from 'react';
import { afterEach, expect, test, vi } from 'vitest';
import { act, cleanup, render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import App from './App.jsx';
import ForkPage, { SandboxRoles } from './Sandbox.jsx';
import { displayedNow, ServerTime, useServerNow } from './ServerTime.jsx';
import { getAuth, saveAuth } from './auth.js';

afterEach(() => { cleanup(); sessionStorage.clear(); vi.unstubAllGlobals(); vi.useRealTimers(); window.history.replaceState({}, '', '/'); });
const ok = data => ({ ok: true, json: async () => data });
const status = { isOpen: false, sandbox: false, serverNow: '2026-11-30T15:04:00Z', speed: 1 };

test.each([null, 'https://sandbox.example'])('live fork is visible after hours with sandbox URL %s', async sandboxUrl => {
  vi.stubGlobal('fetch', vi.fn(async path => path === '/api/sandbox/state'
    ? { ok: false, status: 404, json: async () => null } : ok({ ...status, sandboxUrl })));
  render(<App />);
  expect(await screen.findByRole('heading', { name: 'Frontline: Career Center Intake' })).toBeTruthy();
  expect(screen.getByRole('link', { name: 'Kiosk' }).getAttribute('href')).toBe('/kiosk');
  expect(screen.getAllByRole('link', { name: 'Staff login' }).length).toBeGreaterThan(0);
  if (sandboxUrl) expect(screen.getByRole('link', { name: 'Try the sandbox' }).getAttribute('href')).toBe(sandboxUrl);
  else expect(screen.getByText('Sandbox coming soon')).toBeTruthy();
  expect(screen.queryByText(/Sandbox · dummy data/)).toBeNull();
});

test('sandbox start posts selected settings and shows warming progress', async () => {
  let finish;
  const fetchMock = vi.fn(path => path === '/api/sandbox/day'
    ? new Promise(resolve => { finish = resolve; }) : Promise.resolve(ok(null)));
  vi.stubGlobal('fetch', fetchMock);
  render(<ForkPage status={{ ...status, sandbox: true }} state={{ enabled: true }} />);
  const user = userEvent.setup();
  await user.click(screen.getByText('Pick a different day'));
  await user.selectOptions(screen.getByLabelText('Day'), 'DATE');
  await user.type(screen.getByLabelText('Date'), '2026-11-30');
  await user.selectOptions(screen.getByLabelText('Speed'), '10');
  await user.selectOptions(screen.getByLabelText('Scenario'), 'BEHAVIOR_UNCHANGED');
  await user.click(screen.getByRole('button', { name: 'Start' }));
  expect(screen.getByText('Warming up the day...')).toBeTruthy();
  expect(JSON.parse(fetchMock.mock.calls.find(([path]) => path === '/api/sandbox/day')[1].body)).toEqual({ mode: 'DATE', date: '2026-11-30', time: '10:15', speed: 10, scenario: 'BEHAVIOR_UNCHANGED' });
  finish(ok({ arrivalsSoFar: 20, target: 270 }));
  expect(await screen.findByText('Day ready · 20 / 270 arrivals')).toBeTruthy();
});

test.each([['Clerk','CLERK','/front-desk'],['LSR','LSR','/my-queue'],['Supervising LSR','SUPERVISING_LSR','/supervisor'],['Center Manager','CENTER_MANAGER','/supervisor']])(
  'sandbox role %s logs in and routes', async (label, role, path) => {
    const fetchMock = vi.fn(async () => ok({ token: 'sandbox-token', role, staffId: 9, displayName: label }));
    vi.stubGlobal('fetch', fetchMock); render(<SandboxRoles />);
    await userEvent.setup().click(screen.getByRole('button', { name: label }));
    await waitFor(() => expect(window.location.pathname).toBe(path));
    expect(getAuth().role).toBe(role);
    expect(JSON.parse(fetchMock.mock.calls[0][1].body)).toEqual({ role });
  });

test('sandbox login replaces credentials and shows the banner', async () => {
  window.history.replaceState({}, '', '/login');
  vi.stubGlobal('fetch', vi.fn(async path => ok(path === '/api/sandbox/state'
    ? { enabled: true, speed: 5, scenario: 'SHIFT_TO_ONLINE' }
    : { ...status, sandbox: true, speed: 5, liveUrl: 'https://live.example' })));
  render(<App />);
  expect(await screen.findByRole('heading', { name: 'Log in to Frontline Sandbox' })).toBeTruthy();
  expect(screen.queryByLabelText('Password')).toBeNull();
  expect(await screen.findByText(/Sandbox · dummy data/)).toBeTruthy();
  expect(screen.getByRole('link', { name: 'New day' })).toBeTruthy();
});

test('server time advances at five times browser time and live time is unchanged', () => {
  const fetchedAt = Date.parse(status.serverNow);
  expect(displayedNow({ ...status, speed: 5, fetchedAt }, fetchedAt + 60000)).toBe(fetchedAt + 300000);
  expect(displayedNow({ ...status, fetchedAt }, fetchedAt + 60000)).toBe(fetchedAt + 60000);
  expect(displayedNow(null, fetchedAt)).toBe(fetchedAt);
});

test('display clock advances at sandbox speed between status polls', () => {
  vi.useFakeTimers(); vi.setSystemTime(new Date('2026-11-30T15:04:00Z'));
  const fetchedAt = Date.now();
  function ClockDisplay() { return <output>{useServerNow()}</output>; }
  render(<ServerTime.Provider value={{ ...status, speed: 5, fetchedAt }}><ClockDisplay /></ServerTime.Provider>);
  act(() => vi.advanceTimersByTime(2000));
  expect(screen.getByRole('status').textContent).toBe(String(fetchedAt + 10000));
});

test('warming state disables starting another sandbox day', () => {
  render(<ForkPage status={{ ...status, sandbox: true }} state={{ enabled: true, warming: true }} />);
  expect(screen.getByText('Warming up the day...')).toBeTruthy();
  expect(screen.getByText('Start').closest('fieldset').disabled).toBe(true);
});

test.each(['/front-desk', '/supervisor', '/my-queue', '/resource-room', '/kiosk'])('sandbox banner appears on %s and frozen views', async path => {
  window.history.replaceState({}, '', path);
  if (path !== '/kiosk') saveAuth({ token: 'manager-token', role: 'CENTER_MANAGER', staffId: 9, displayName: 'Demo Manager' });
  vi.stubGlobal('fetch', vi.fn(async url => {
    if (url === '/api/business-status') return ok({ ...status, sandbox: true, speed: 5, lastCompletedBusinessDate: '2026-11-25' });
    if (url === '/api/sandbox/state') return ok({ enabled: true, speed: 5, scenario: 'SHIFT_TO_ONLINE' });
    return ok({ businessDate: '2026-11-25', staff: [], coverage: [], callOuts: [], visits: [], resources: [] });
  }));
  render(<App />);
  expect(await screen.findByText(/Sandbox · dummy data/)).toBeTruthy();
  expect(await screen.findByText(/Career center is closed/)).toBeTruthy();
});

test.each([false, true])('kiosk retains check-in behavior with sandbox=%s', async sandbox => {
  window.history.replaceState({}, '', '/kiosk');
  const fetchMock = vi.fn(async (path, options) => {
    if (path === '/api/business-status') return ok({ ...status, isOpen: true, sandbox, currentBusinessDate: '2026-11-30' });
    if (path === '/api/sandbox/state') return ok({ enabled: true, speed: 5, scenario: 'SHIFT_TO_ONLINE' });
    if (path.startsWith('/api/visit-reasons')) return ok([{ code: 'UI_ACCOUNT', label: 'UI account help' }]);
    if (path === '/api/visits' && options.method === 'POST') return ok({ ticket: 'B0001', visitType: 'WALK_IN', reason: 'UI_ACCOUNT' });
    throw new Error(path);
  });
  vi.stubGlobal('fetch', fetchMock); render(<App />);
  const user = userEvent.setup();
  await user.click(await screen.findByRole('radio', { name: 'Walk-In' }));
  await user.click(await screen.findByRole('radio', { name: /UI account help/ }));
  await user.click(screen.getByRole('button', { name: 'Check in' }));
  expect(await screen.findByText('B0001')).toBeTruthy();
  expect(fetchMock).toHaveBeenCalledWith('/api/visits', expect.objectContaining({ body: '{"visitType":"WALK_IN","reason":"UI_ACCOUNT"}' }));
});

test('sandbox landing polls the summary and puts the primary manager button above collapsed day options', async () => {
  vi.useFakeTimers();
  const data = { sandboxNow: '2026-11-30T15:17:00Z', beingServed: 36, waiting: 7, openHelp: 4, arrivalsToday: 90 };
  const fetchMock = vi.fn()
    .mockResolvedValueOnce(ok(data))
    .mockResolvedValueOnce(ok({ ...data, beingServed: 38 }));
  vi.stubGlobal('fetch', fetchMock);
  await act(async () => render(<ForkPage status={{ ...status, sandbox: true }} state={{ enabled: true }} />));
  expect(screen.getByText('Mon, Nov 30, 10:17 AM · 36 being served · 7 waiting · 4 help requests')).toBeTruthy();
  const buttons = screen.getAllByRole('button');
  expect(buttons[0]).toBe(screen.getByRole('button', { name: 'Jump in as Center Manager' }));
  expect(buttons[0].classList.contains('primary')).toBe(true);
  expect(screen.getByText('Pick a different day').closest('details').open).toBe(false);
  await act(async () => vi.advanceTimersByTimeAsync(5000));
  expect(fetchMock.mock.calls.map(([path]) => path)).toEqual(['/api/sandbox/summary', '/api/sandbox/summary']);
  expect(screen.getByText(/38 being served/)).toBeTruthy();
});
