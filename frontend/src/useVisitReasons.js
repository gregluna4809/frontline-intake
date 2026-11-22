import { useEffect, useState } from 'react';
import { request } from './api.js';

export default function useVisitReasons(businessDate) {
  const [state, setState] = useState({ date: null, reasons: [], error: '' });
  useEffect(() => {
    if (!businessDate) return;
    let active = true;
    const controller = new AbortController();
    request('/api/visit-reasons?date=' + businessDate, { signal: controller.signal })
      .then(reasons => { if (active) setState({ date: businessDate, reasons, error: '' }); })
      .catch(failure => { if (active) setState({ date: businessDate, reasons: [], error: failure.message }); });
    return () => { active = false; controller.abort(); };
  }, [businessDate]);
  return state.date === businessDate ? state : { reasons: [], error: '' };
}
