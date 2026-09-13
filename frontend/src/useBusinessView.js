import { useEffect, useState } from 'react';
import { request } from './api.js';

export default function useBusinessView() {
  const [status, setStatus] = useState(null);
  const [snapshot, setSnapshot] = useState(null);
  const [error, setError] = useState('');
  const [snapshotError, setSnapshotError] = useState('');
  useEffect(() => {
    let active = true;
    let timer;
    const controller = new AbortController();
    async function load() {
      try {
        const data = await request('/api/business-status', { signal: controller.signal });
        if (active) { setStatus(data); setError(''); }
      } catch (failure) {
        if (active) { setStatus(null); setError(failure.message); }
      } finally {
        if (active) timer = setTimeout(load, 5000);
      }
    }
    load();
    return () => { active = false; controller.abort(); clearTimeout(timer); };
  }, []);
  const date = status?.lastCompletedBusinessDate;
  const open = status?.isOpen;
  useEffect(() => {
    if (open !== false) return;
    let active = true;
    const controller = new AbortController();
    setSnapshot(null);
    setSnapshotError('');
    request('/api/snapshot?date=' + date, { signal: controller.signal })
      .then(data => { if (active) setSnapshot(data); })
      .catch(failure => { if (active) setSnapshotError(failure.message); });
    return () => { active = false; controller.abort(); };
  }, [open, date]);
  return { status, snapshot: snapshot?.businessDate === date ? snapshot : null, error: error || (open === false ? snapshotError : '') };
}
