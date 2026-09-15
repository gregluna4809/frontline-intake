import { afterEach, expect, test, vi } from 'vitest';
import { request } from './api.js';
import { getAuth, logout, saveAuth, SESSION_EXPIRED } from './auth.js';

const auth = { token: 'opaque-token', staffId: 1, role: 'LSR', displayName: 'Demo Staff 001' };
afterEach(() => { sessionStorage.clear(); vi.unstubAllGlobals(); window.history.replaceState({}, '', '/login'); });

test('authenticated mutations preserve If-Match and use the latest stored token', async () => {
  saveAuth(auth);
  const fetchMock = vi.fn(async () => ({ ok: true, json: async () => ({}) }));
  vi.stubGlobal('fetch', fetchMock);
  await request('/api/visits/1/close', { method: 'POST', headers: { 'If-Match': '"3"' } });
  expect(fetchMock.mock.calls[0][1].headers).toEqual({ 'Content-Type': 'application/json', 'If-Match': '"3"', Authorization: 'Bearer opaque-token' });
  saveAuth({ ...auth, token: 'new-token' });
  await request('/api/staff');
  expect(fetchMock.mock.calls[1][1].headers.Authorization).toBe('Bearer new-token');
});

test.each(['/api/business-status', '/api/snapshot?date=2026-09-11', '/api/visits', '/api/auth/login'])(
  'public call %s omits a stored token', async path => {
    saveAuth(auth);
    const fetchMock = vi.fn(async () => ({ ok: true, json: async () => ({}) }));
    vi.stubGlobal('fetch', fetchMock);
    await request(path, { method: path === '/api/visits' || path === '/api/auth/login' ? 'POST' : 'GET' });
    expect(fetchMock.mock.calls[0][1].headers.Authorization).toBeUndefined();
  },
);

test('a 401 mutation clears auth and redirects even if the error body is not JSON', async () => {
  saveAuth(auth);
  vi.stubGlobal('fetch', vi.fn(async () => ({ ok: false, status: 401, json: async () => { throw new Error('not JSON'); } })));
  await expect(request('/api/visits/1/close', { method: 'POST' })).rejects.toThrow(SESSION_EXPIRED);
  expect(getAuth()).toBeNull();
  expect(window.location.pathname + window.location.search).toBe('/login?expired=1');
});

test.each(['login', 'logout'])('late 401 cannot overwrite a subsequent %s', async action => {
  saveAuth(auth);
  let resolve;
  vi.stubGlobal('fetch', vi.fn(() => new Promise(done => { resolve = done; })));
  const pending = request('/api/staff');
  if (action === 'login') saveAuth({ ...auth, token: 'new-token' });
  else logout();
  resolve({ ok: false, status: 401 });
  await expect(pending).rejects.toThrow(SESSION_EXPIRED);
  expect(getAuth()?.token || null).toBe(action === 'login' ? 'new-token' : null);
  expect(window.location.search).toBe('');
});

test('403 retains the login and displays the server explanation', async () => {
  saveAuth(auth);
  vi.stubGlobal('fetch', vi.fn(async () => ({ ok: false, status: 403, json: async () => ({ detail: 'Your role is not permitted to perform this action.' }) })));
  await expect(request('/api/visits/1/reschedule', { method: 'POST' })).rejects.toThrow('Your role is not permitted');
  expect(getAuth()).toEqual(auth);
});
