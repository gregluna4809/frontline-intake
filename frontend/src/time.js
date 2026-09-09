export function waitingTime(waitingSince, now) {
  if (!waitingSince) return '—';
  const elapsed = Math.max(0, Math.floor((now - new Date(waitingSince).getTime()) / 60000));
  if (!Number.isFinite(elapsed)) return '—';
  if (elapsed < 1) return 'Less than 1 min';
  if (elapsed < 60) return elapsed + ' min';
  return Math.floor(elapsed / 60) + ' hr ' + (elapsed % 60) + ' min';
}
