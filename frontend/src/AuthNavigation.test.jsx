import React from 'react';
import { afterEach, beforeEach, expect, test, vi } from 'vitest';
import { act, cleanup, render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import App from './App.jsx';
import { AUTH_KEY, getAuth, navigate, roleLabels, saveAuth, SESSION_EXPIRED } from './auth.js';
import { mockQueues, ok } from './testQueues.js';

const cases = [
  ['CLERK', 6, '/front-desk', ['Front desk']],
  ['LSR', 1, '/my-queue', ['My Queue', 'Resource room']],
  ['SUPERVISING_LSR', 5, '/supervisor', ['Supervisor']],
  ['CENTER_MANAGER', 9, '/supervisor', ['Supervisor', 'Front desk', 'My Queue', 'Resource room']],
  ['ADMINISTRATOR', 10, '/admin', []],
];
const account = (role = 'LSR', staffId = 1) => ({ token: 'opaque-demo-token-' + staffId, role, staffId, displayName: 'Signed-in person ' + staffId });

beforeEach(() => { sessionStorage.clear(); window.history.replaceState({}, '', '/login'); });
afterEach(() => { cleanup(); sessionStorage.clear(); vi.unstubAllGlobals(); vi.restoreAllMocks(); });

function mockApi(auth = account()) {
  const { fetchMock, state } = mockQueues();
  const original = fetchMock.getMockImplementation();
  fetchMock.mockImplementation((path, options = {}) => {
    if (path === '/api/auth/login') return Promise.resolve(ok(auth));
    if (path === '/api/simulator/status') return Promise.resolve(ok({ running: false, arrivalsPerMinute: 4 }));
    if (path === '/api/resources' || path.includes('WAITING_RESOURCE') || path.includes('AT_RESOURCE') ||
      path.includes('CHECKED_IN') || path.includes('IDENTIFIED')) return Promise.resolve(ok([]));
    return original(path, options);
  });
  return { fetchMock, state };
}

async function submitLogin() {
  const user = userEvent.setup();
  await user.type(screen.getByLabelText('Username'), 'staff1');
  await user.type(screen.getByLabelText('Password'), 'demo1234');
  await user.click(screen.getByRole('button', { name: 'Log in' }));
}

test.each(cases)('%s logs in, stores direct response fields, and sees only permitted navigation', async (role, id, landing, links) => {
  const auth = account(role, id);
  const { fetchMock } = mockApi(auth);
  render(<App />);
  await submitLogin();
  await waitFor(() => expect(window.location.pathname).toBe(landing));
  expect(getAuth()).toEqual(auth);
  expect(screen.getByText(auth.displayName)).toBeTruthy();
  expect(screen.getByText(roleLabels[role])).toBeTruthy();
  const nav = screen.queryByRole('navigation', { name: 'Staff navigation' });
  expect(nav ? within(nav).getAllByRole('link').map(link => link.textContent) : []).toEqual(links);
  expect(screen.queryByText('View as')).toBeNull();
  expect(screen.queryByRole('combobox', { name: 'Role' })).toBeNull();
  expect(screen.queryByRole('link', { name: 'Kiosk' })).toBeNull();
  expect(fetchMock).toHaveBeenCalledWith('/api/auth/login', expect.objectContaining({
    method: 'POST', body: JSON.stringify({ username: 'staff1', password: 'demo1234' }),
  }));
  if (role === 'ADMINISTRATOR') {
    expect(screen.getByRole('heading', { name: 'Admin console — coming soon.' })).toBeTruthy();
    expect(fetchMock.mock.calls.map(([path]) => path)).toEqual(['/api/auth/login']);
  }
});

test('invalid credentials show an error without leaving login or storing authentication', async () => {
  vi.stubGlobal('fetch', vi.fn(async () => ({ ok: false, status: 401, json: async () => ({ detail: 'Invalid username or password.' }) })));
  render(<App />);
  await submitLogin();
  expect((await screen.findByRole('alert')).textContent).toBe('Invalid username or password.');
  expect(window.location.pathname + window.location.search).toBe('/login');
  expect(getAuth()).toBeNull();
  expect(screen.queryByText(SESSION_EXPIRED)).toBeNull();
});

test('an incomplete login response is rejected instead of decoding its token', async () => {
  mockApi({ token: 'token-with-no-direct-identity-fields' });
  render(<App />);
  await submitLogin();
  expect((await screen.findByRole('alert')).textContent).toContain('incomplete');
  expect(getAuth()).toBeNull();
  expect(window.location.pathname).toBe('/login');
});

test('logout clears stored auth and back navigation cannot restore a staff screen', async () => {
  saveAuth(account()); mockApi(); window.history.replaceState({}, '', '/my-queue');
  render(<App />);
  await screen.findByText('A0002');
  await userEvent.setup().click(screen.getByRole('button', { name: 'Logout' }));
  expect(await screen.findByRole('heading', { name: 'Log in to Frontline' })).toBeTruthy();
  expect(sessionStorage.getItem(AUTH_KEY)).toBeNull();
  await act(async () => { navigate('/my-queue'); });
  await waitFor(() => expect(window.location.pathname).toBe('/login'));
  expect(screen.queryByText('A0002')).toBeNull();
});

test.each(cases)('%s redirects every disallowed staff URL to its landing', async (role, id, landing, links) => {
  saveAuth(account(role, id)); mockApi();
  const allowed = { 'Front desk': '/front-desk', Supervisor: '/supervisor', 'My Queue': '/my-queue', 'Resource room': '/resource-room' };
  const permitted = role === 'ADMINISTRATOR' ? ['/admin'] : links.map(link => allowed[link]);
  const forbidden = ['/front-desk', '/supervisor', '/my-queue', '/resource-room', '/admin', '/appointments', '/unknown']
    .filter(path => !permitted.includes(path));
  window.history.replaceState({}, '', forbidden[0]);
  render(<App />);
  await waitFor(() => expect(window.location.pathname).toBe(landing));
  for (const path of forbidden.slice(1)) {
    await act(async () => { navigate(path); });
    await waitFor(() => expect(window.location.pathname).toBe(landing));
  }
});

test('missing or malformed stored auth redirects protected routes before fetching their data', async () => {
  sessionStorage.setItem(AUTH_KEY, '{bad json');
  window.history.replaceState({}, '', '/supervisor');
  const { fetchMock } = mockApi();
  render(<App />);
  expect(await screen.findByRole('heading', { name: 'Log in to Frontline' })).toBeTruthy();
  expect(window.location.pathname).toBe('/login');
  expect(fetchMock).not.toHaveBeenCalled();
});

test('LSR automatically loads their own queue, ignores a forged staff selection, and can visit Resource room', async () => {
  saveAuth(account()); const { fetchMock } = mockApi();
  window.history.replaceState({}, '', '/my-queue?staff=2');
  render(<App />);
  expect(await screen.findByText('A0002')).toBeTruthy();
  expect(screen.queryByText('A0003')).toBeNull();
  expect(screen.queryByRole('combobox')).toBeNull();
  expect(screen.queryByRole('button', { name: /Reschedule/ })).toBeNull();
  expect(window.location.search).toBe('');
  expect(fetchMock).toHaveBeenCalledWith('/api/visits?status=WAITING_STAFF', expect.objectContaining({
    headers: expect.objectContaining({ Authorization: 'Bearer ' + account().token }),
  }));
  await userEvent.setup().click(screen.getByRole('link', { name: 'Resource room' }));
  await waitFor(() => expect(window.location.pathname).toBe('/resource-room'));
});

test('manager can select another LSR and restore that selection on a page reload', async () => {
  saveAuth(account('CENTER_MANAGER', 9)); mockApi();
  window.history.replaceState({}, '', '/my-queue?staff=1');
  const view = render(<App />);
  await screen.findByText('A0002');
  await userEvent.setup().selectOptions(screen.getByRole('combobox', { name: 'Staff member' }), '2');
  expect(await screen.findByText('A0003')).toBeTruthy();
  expect(screen.queryByText('A0002')).toBeNull();
  expect(window.location.search).toBe('?staff=2');
  expect(screen.getByRole('button', { name: 'Reschedule A0003' })).toBeTruthy();
  view.unmount(); render(<App />);
  expect(await screen.findByText('A0003')).toBeTruthy();
  expect(screen.getByRole('combobox', { name: 'Staff member' }).value).toBe('2');
});

test.each(['/api/staff', '/api/visits?status=WAITING_STAFF', '/api/simulator/status', '/api/business-status'])(
  '401 from %s clears the session and redirects with an expiry message', async failedPath => {
    saveAuth(account()); const { fetchMock } = mockApi();
    const original = fetchMock.getMockImplementation();
    fetchMock.mockImplementation((path, options) => path === failedPath
      ? Promise.resolve({ ok: false, status: 401, json: async () => ({}) }) : original(path, options));
    window.history.replaceState({}, '', '/my-queue');
    render(<App />);
    expect(await screen.findByText(SESSION_EXPIRED)).toBeTruthy();
    expect(window.location.pathname).toBe('/login');
    expect(getAuth()).toBeNull();
    expect(screen.queryByText('A0002')).toBeNull();
  },
);

test.each([false, true])('kiosk stays public without staff navigation (stored session: %s)', async stored => {
  if (stored) saveAuth(account());
  const { fetchMock } = mockApi(); window.history.replaceState({}, '', '/kiosk');
  render(<App />);
  expect(await screen.findByRole('button', { name: 'Check in' })).toBeTruthy();
  expect(screen.queryByRole('navigation')).toBeNull();
  expect(screen.queryByRole('button', { name: 'Logout' })).toBeNull();
  expect(screen.queryByLabelText('Traffic simulator')).toBeNull();
  expect(fetchMock.mock.calls.every(([path, options]) => path === '/api/business-status' && !options.headers.Authorization)).toBe(true);
});
