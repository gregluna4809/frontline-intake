import { useEffect, useRef, useState } from 'react';
import { request } from './api.js';

export default function useAppointmentQueue() {
  const [snapshot, setSnapshot] = useState(null);
  const [queueError, setQueueError] = useState('');
  const [actionError, setActionError] = useState('');
  const [notice, setNotice] = useState('');
  const [refresh, setRefresh] = useState(0);
  const [pendingId, setPendingId] = useState(null);
  const [needsRefresh, setNeedsRefresh] = useState(true);
  const [now, setNow] = useState(Date.now());
  const submitting = useRef(false);
  const revision = useRef(0);

  useEffect(() => {
    let active = true;
    let timer;
    const controller = new AbortController();
    async function load() {
      const currentRevision = revision.current;
      try {
        const [visits, staff] = await Promise.all([
          request('/api/visits?status=WAITING_STAFF', { signal: controller.signal }),
          request('/api/staff', { signal: controller.signal }),
        ]);
        if (active && currentRevision === revision.current && !submitting.current) {
          setSnapshot({ visits, staff });
          setQueueError('');
          setNeedsRefresh(false);
        }
      } catch (failure) {
        if (active && currentRevision === revision.current) {
          setQueueError(failure.message);
          setNeedsRefresh(true);
        }
      } finally {
        if (active) timer = setTimeout(load, 5000);
      }
    }
    load();
    return () => { active = false; controller.abort(); clearTimeout(timer); };
  }, [refresh]);

  useEffect(() => {
    const timer = setInterval(() => setNow(Date.now()), 1000);
    return () => clearInterval(timer);
  }, []);

  async function mutate(visit, action, body) {
    if (submitting.current || needsRefresh) return;
    submitting.current = true;
    revision.current++;
    setPendingId(visit.id);
    setNeedsRefresh(true);
    setActionError('');
    setNotice('');
    try {
      await request('/api/visits/' + visit.id + '/' + action, {
        method: 'POST', headers: { 'If-Match': '"' + visit.version + '"' },
        ...(body ? { body: JSON.stringify(body) } : {}),
      });
      setNotice('Ticket ' + visit.ticket + (action === 'close' ? ' closed.' : ' assignment saved.'));
    } catch (failure) {
      setActionError(failure.message + ' Refresh the queue before trying again.');
    } finally {
      revision.current++;
      submitting.current = false;
      setPendingId(null);
      setRefresh(value => value + 1);
    }
  }

  return {
    snapshot, now, queueError, actionError, notice, pendingId,
    disabled: needsRefresh || pendingId !== null,
    refresh: () => setRefresh(value => value + 1),
    mutate,
  };
}
