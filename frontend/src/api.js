import { clearAuth, expireSession, getAuth, SESSION_EXPIRED } from './auth.js';

export async function request(path, options = {}) {
  const method = (options.method || 'GET').toUpperCase();
  const pathname = new URL(path, window.location.origin).pathname;
  const login = ['/api/auth/login', '/api/sandbox/login'].includes(pathname) && method === 'POST';
  const publicRequest = login || (method === 'POST' && pathname === '/api/visits') ||
    (method === 'GET' && ['/api/sandbox/state', '/api/sandbox/summary', '/api/business-status', '/api/snapshot', '/api/visit-reasons'].includes(pathname));
  const requestToken = getAuth()?.token || null;
  const headers = { 'Content-Type': 'application/json', ...options.headers };
  if (!publicRequest && requestToken) headers.Authorization = 'Bearer ' + requestToken;
  let response;
  try {
    response = await fetch(path, {
      ...options,
      headers,
    });
  } catch {
    throw new Error('Cannot reach the Career Center. Please try again.');
  }
  if (!response.ok) {
    if (response.status === 401 && !options.signal?.aborted) {
      if (login) clearAuth();
      else { expireSession(requestToken); throw new Error(SESSION_EXPIRED); }
    }
    const problem = await response.json().catch(() => null);
    throw new Error(problem?.detail || 'Unable to complete the request. Please try again.');
  }
  return response.status === 204 ? null : response.json();
}

export const reasons = [
  ['UI_ACCOUNT', 'UI account help'],
  ['PHONE_ACCESS', 'Phone Access'],
  ['COMPUTER_ACCESS', 'Computer Access'],
  ['DOCUMENT_SERVICES', 'Document Services'],
  ['BENEFITS_ASSISTANCE', 'Benefits Assistance'],
  ['JOB_SEARCH', 'Job Search'],
  ['OTHER', 'Other'],
];

export function reasonLabel(value) {
  return reasons.find(([code]) => code === value)?.[1] || value;
}

export const appointmentCategories = [
  ['MANDATORY', 'Mandatory'],
  ['JOB_SEARCH_ASSISTANCE', 'Job Search Assistance'],
];

export function appointmentCategoryLabel(value) {
  return appointmentCategories.find(([code]) => code === value)?.[1] || value;
}

export function appointmentTypeLabel(value) {
  return { INITIAL: 'Initial', FOLLOWUP: 'Follow-up' }[value] || value || '—';
}

export function appointmentStatusLabel(value) {
  return {
    SCHEDULED: 'Scheduled', RESCHEDULED: 'Rescheduled',
    NOT_ATTENDED_FTR: 'Not attended / FTR', FTR_REPORTED: 'FTR reported',
  }[value] || value || '—';
}
