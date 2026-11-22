import { vi } from 'vitest';
import { reasons } from './api.js';

// Additional read fixtures preserve the original workflow mocks and assertions.
export function stubFetch(handler) {
  vi.stubGlobal('fetch', (path, options) => {
    if (path === '/api/sandbox/state') return Promise.resolve({ ok: false, status: 404, json: async () => null });
    if (path.startsWith('/api/visit-reasons')) {
      const date = new URL(path, 'http://localhost').searchParams.get('date');
      return Promise.resolve({ ok: true, json: async () => reasons
        .filter(([code]) => code === 'PHONE_ACCESS' ? date < '2026-11-30'
          : code === 'UI_ACCOUNT' ? date >= '2026-11-30' : true)
        .map(([code, label]) => ({ code, label })) });
    }
    if (path.startsWith('/api/help-requests')) return Promise.resolve({ ok: true, json: async () => [] });
    return handler(path, options);
  });
}
