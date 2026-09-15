import { useSyncExternalStore } from 'react';

export const AUTH_KEY = 'frontline.auth';
export const SESSION_EXPIRED = 'Session expired, please log in again.';
export const roleLabels = {
  CLERK: 'Clerk', LSR: 'LSR', SUPERVISING_LSR: 'Supervising LSR',
  CENTER_MANAGER: 'Center Manager', ADMINISTRATOR: 'Administrator',
};
export const rolePaths = {
  CLERK: ['/front-desk'],
  LSR: ['/my-queue', '/resource-room'],
  SUPERVISING_LSR: ['/supervisor'],
  CENTER_MANAGER: ['/supervisor', '/front-desk', '/my-queue', '/resource-room'],
  ADMINISTRATOR: ['/admin'],
};
export const landingPage = role => rolePaths[role]?.[0] || '/login';

function parseAuth(value) {
  try {
    const auth = JSON.parse(value);
    return auth && typeof auth.token === 'string' && auth.token.length > 0 &&
      Number.isSafeInteger(auth.staffId) && auth.staffId > 0 &&
      Object.hasOwn(rolePaths, auth.role) && typeof auth.displayName === 'string' && auth.displayName.length > 0
      ? auth : null;
  } catch { return null; }
}

export function getAuth() { return parseAuth(sessionStorage.getItem(AUTH_KEY)); }

export function saveAuth(data) {
  const auth = { token: data.token, staffId: data.staffId, role: data.role, displayName: data.displayName };
  if (!parseAuth(JSON.stringify(auth))) throw new Error('The login response was incomplete. Please try again.');
  sessionStorage.setItem(AUTH_KEY, JSON.stringify(auth));
  window.dispatchEvent(new Event('authchange'));
}

export function clearAuth() {
  sessionStorage.removeItem(AUTH_KEY);
  window.dispatchEvent(new Event('authchange'));
}

function subscribe(listener) {
  window.addEventListener('authchange', listener);
  window.addEventListener('storage', listener);
  return () => {
    window.removeEventListener('authchange', listener);
    window.removeEventListener('storage', listener);
  };
}

export function useAuth() {
  const stored = useSyncExternalStore(subscribe, () => sessionStorage.getItem(AUTH_KEY));
  return parseAuth(stored);
}

export function navigate(path, replace = false) {
  window.history[replace ? 'replaceState' : 'pushState']({}, '', path);
  window.dispatchEvent(new PopStateEvent('popstate'));
}

export function logout() { clearAuth(); navigate('/login', true); }

export function expireSession(requestToken) {
  // A delayed response from an old session must not sign out a newer login.
  if ((getAuth()?.token || null) !== requestToken) return;
  clearAuth();
  navigate('/login?expired=1', true);
}
