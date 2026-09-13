import { expect, test } from 'vitest';
import { waitingTime, calendarDate } from './time.js';
test('waiting time handles short waits, hours and missing timestamps', () => {
  const now = Date.parse('2026-09-08T12:00:00Z');
  expect(waitingTime('2026-09-08T11:59:30Z', now)).toBe('Less than 1 min');
  expect(waitingTime('2026-09-08T10:55:00Z', now)).toBe('1 hr 5 min');
  expect(waitingTime(null, now)).toBe('—');
});

test('coverage dates use the Eastern calendar day independently of browser timezone', () => {
  expect(calendarDate(Date.parse('2026-09-09T03:59:00Z'))).toBe('2026-09-08');
  expect(calendarDate(Date.parse('2026-09-09T04:01:00Z'))).toBe('2026-09-09');
  expect(calendarDate(Date.parse('2026-12-09T04:59:00Z'))).toBe('2026-12-08');
  expect(calendarDate(Date.parse('2026-12-09T05:01:00Z'))).toBe('2026-12-09');
});
