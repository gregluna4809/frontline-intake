import { useEffect, useRef, useState } from 'react';
import { request } from './api.js';
import { calendarDate } from './time.js';

export default function useAppointmentQueue({ includeCoverage = false } = {}) {
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
  const today = calendarDate(now);

  useEffect(() => {
    let active = true;
    let timer;
    const controller = new AbortController();
    async function load() {
      const currentRevision = revision.current;
      try {
        const businessStatus = includeCoverage
          ? await request('/api/business-status', { signal: controller.signal }) : null;
        const date = businessStatus?.currentBusinessDate || today;
        const [visits, staff, coverage] = await Promise.all([
          request('/api/visits?status=WAITING_STAFF' + (includeCoverage ? '&businessDate=' + date : ''), { signal: controller.signal }),
          request('/api/staff', { signal: controller.signal }),
          includeCoverage ? request('/api/rotation?date=' + date, { signal: controller.signal }) : Promise.resolve([]),
        ]);
        if (active && currentRevision === revision.current && !submitting.current) {
          setSnapshot({ visits, staff, coverage, date, businessStatus });
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
  }, [refresh, includeCoverage, today]);

  useEffect(() => {
    const timer = setInterval(() => setNow(Date.now()), 1000);
    return () => clearInterval(timer);
  }, []);

  async function save(id, path, options, successMessage) {
    if (submitting.current || needsRefresh) return false;
    submitting.current = true;
    revision.current++;
    setPendingId(id);
    setNeedsRefresh(true);
    setActionError('');
    setNotice('');
    try {
      const result = await request(path, options);
      setNotice(successMessage(result));
      return true;
    } catch (failure) {
      setActionError(failure.message + ' Refresh the queue before trying again.');
      return false;
    } finally {
      revision.current++;
      submitting.current = false;
      setPendingId(null);
      setRefresh(value => value + 1);
    }
  }

  function mutate(visit, action, body) {
    return save(visit.id, '/api/visits/' + visit.id + '/' + action, {
      method: 'POST', headers: { 'If-Match': '"' + visit.version + '"' },
      ...(body ? { body: JSON.stringify(body) } : {}),
    }, result => action === 'reschedule'
      ? 'Ticket ' + visit.ticket + ' rescheduled as ' + result.replacementVisit.visit.ticket + '.'
      : 'Ticket ' + visit.ticket + (action === 'close' ? ' closed.' : ' assignment saved.'));
  }

  function markCalledOut(staffId, reason) {
    if (snapshot?.date !== today) return Promise.resolve(false);
    return save('staff:' + staffId, '/api/call-outs', {
      method: 'POST', body: JSON.stringify({ staffId, date: snapshot.date, reason: reason.trim() || null }),
    }, () => 'Call-out saved. Refreshing coverage and assigned appointments.');
  }

  return {
    snapshot, now, today, queueError, actionError, notice, pendingId,
    disabled: needsRefresh || pendingId !== null,
    canCallOut: snapshot?.date === today,
    refresh: () => setRefresh(value => value + 1),
    mutate, markCalledOut,
  };
}
