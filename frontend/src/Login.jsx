import { SandboxRoles } from './Sandbox.jsx';
import { useRef, useState } from 'react';
import { request } from './api.js';
import { landingPage, navigate, saveAuth, SESSION_EXPIRED } from './auth.js';

export default function Login({ expired, sandbox }) {
  const [username, setUsername] = useState('');
  const [password, setPassword] = useState('');
  const [pending, setPending] = useState(false);
  const [error, setError] = useState('');
  const submitting = useRef(false);

  async function submit(event) {
    event.preventDefault();
    if (submitting.current) return;
    submitting.current = true; setPending(true); setError('');
    try {
      const auth = await request('/api/auth/login', {
        method: 'POST', body: JSON.stringify({ username: username.trim(), password }),
      });
      saveAuth(auth);
      navigate(landingPage(auth.role), true);
    } catch (failure) {
      setError(failure.message);
    } finally {
      submitting.current = false; setPending(false);
    }
  }

  if (sandbox) return <section className="login-panel"><h1>Log in to Frontline Sandbox</h1><SandboxRoles /></section>;

  return <section className="login-panel">
    <span className="eyebrow">Career Center staff</span>
    <h1>Log in to Frontline</h1>
    <p className="intro">Use your staff account to access your workspace.</p>
    {expired && <p role="status" className="session-message">{SESSION_EXPIRED}</p>}
    <form onSubmit={submit}>
      <label htmlFor="username">Username</label>
      <input id="username" name="username" autoComplete="username" required maxLength={100}
        value={username} onChange={event => setUsername(event.target.value)} disabled={pending} autoFocus />
      <label htmlFor="password">Password</label>
      <input id="password" name="password" type="password" autoComplete="current-password" required maxLength={72}
        value={password} onChange={event => setPassword(event.target.value)} disabled={pending} />
      {error && <p role="alert" className="error">{error}</p>}
      <button className="primary" disabled={pending || !username.trim() || !password}>
        {pending ? 'Logging in…' : 'Log in'}
      </button>
    </form>
    <p className="login-demo">Synthetic demo: staff1–staff10 · Password: demo1234</p>
  </section>;
}
