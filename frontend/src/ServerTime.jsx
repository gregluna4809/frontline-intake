import { createContext, useContext, useEffect, useState } from 'react';

export const ServerTime = createContext(null);

export function displayedNow(status, browserNow = Date.now()) {
  return status?.serverNow && status?.fetchedAt != null
    ? Date.parse(status.serverNow) + (browserNow - status.fetchedAt) * (status.speed || 1)
    : browserNow;
}

export function useServerNow() {
  const status = useContext(ServerTime);
  const [browserNow, setBrowserNow] = useState(Date.now());
  useEffect(() => {
    const timer = setInterval(() => setBrowserNow(Date.now()), 1000);
    return () => clearInterval(timer);
  }, []);
  return displayedNow(status, browserNow);
}
