import { useEffect, useState } from 'react';
import { request } from './api.js';

export default function useAdminData(path) {
  const [data, setData] = useState(null);
  const [loading, setLoading] = useState(true);
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState('');
  const [notice, setNotice] = useState('');
  const [revision, setRevision] = useState(0);
  useEffect(() => {
    let active = true;
    const controller = new AbortController();
    request(path, { signal: controller.signal })
      .then(value => { if (active) { setData(value); setError(''); } })
      .catch(failure => { if (active) { setData(null); setError(failure.message); } })
      .finally(() => { if (active) setLoading(false); });
    return () => { active = false; controller.abort(); };
  }, [path, revision]);

  function refresh() { setLoading(true); setRevision(value => value + 1); }

  async function save(url, method, body, message) {
    if (loading || saving) return false;
    setSaving(true); setError(''); setNotice('');
    try {
      await request(url, { method, ...(body === undefined ? {} : { body: JSON.stringify(body) }) });
      setNotice(message); refresh();
      return true;
    } catch (failure) { setError(failure.message); return false; }
    finally { setSaving(false); }
  }
  return { data, loading, saving, error, notice, refresh, save, disabled: loading || saving || data === null };
}

export function AdminFeedback({ state }) {
  return <>
    {state.error && <p role="alert" className="error">{state.error}</p>}
    {state.notice && <p role="status" className="routing-result">{state.notice}</p>}
    {state.loading && <p role="status">Loading…</p>}
  </>;
}
