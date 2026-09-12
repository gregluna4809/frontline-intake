import { expect, test } from 'vitest';
import { waitingTime } from './time.js';
test('waiting time handles short waits, hours and missing timestamps', () => {
  const now = Date.parse('2026-09-08T12:00:00Z');
  expect(waitingTime('2026-09-08T11:59:30Z', now)).toBe('Less than 1 min');
  expect(waitingTime('2026-09-08T10:55:00Z', now)).toBe('1 hr 5 min');
  expect(waitingTime(null, now)).toBe('—');
});
