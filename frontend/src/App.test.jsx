vi.mock('./SimulatorControl.jsx', () => ({ default: () => null }));
import React from 'react';
import { afterEach, expect, test, vi } from 'vitest';
import { cleanup, render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import App from './App.jsx';

afterEach(() => {
  cleanup();
  vi.unstubAllGlobals();
  window.history.replaceState({}, '', '/kiosk');
});

test('kiosk sends walk-in type and selected reason and displays the returned ticket', async () => {
  const fetchMock = vi.fn().mockResolvedValue({
    ok: true,
    json: async () => ({ id: 1, ticket: 'B0001', reason: 'PHONE_ACCESS' }),
  });
  vi.stubGlobal('fetch', fetchMock);
  render(<App />);
  const user = userEvent.setup();
  expect(screen.getByRole('button', { name: 'Check in' }).disabled).toBe(true);
  await user.click(screen.getByRole('radio', { name: 'Walk-In' }));
  await user.click(screen.getByRole('radio', { name: /Phone Access/ }));
  await user.click(screen.getByRole('button', { name: 'Check in' }));
  expect(await screen.findByText('B0001')).toBeTruthy();
  expect(fetchMock).toHaveBeenCalledWith('/api/visits', expect.objectContaining({
    method: 'POST',
    body: '{"visitType":"WALK_IN","reason":"PHONE_ACCESS"}',
  }));
  expect(screen.getByText('Synthetic demo — no real data')).toBeTruthy();
  await user.click(screen.getByRole('button', { name: 'Start a new check-in' }));
  expect(screen.getByRole('button', { name: 'Check in' }).disabled).toBe(true);
});

test('failed check-in shows an error and permits retry without losing the reason', async () => {
  const fetchMock = vi.fn()
    .mockRejectedValueOnce(new TypeError('Failed to fetch'))
    .mockResolvedValueOnce({
      ok: true,
      json: async () => ({ id: 2, ticket: 'B0002', reason: 'JOB_SEARCH' }),
    });
  vi.stubGlobal('fetch', fetchMock);
  render(<App />);
  const user = userEvent.setup();
  await user.click(screen.getByRole('radio', { name: 'Walk-In' }));
  await user.click(screen.getByRole('radio', { name: /Job Search/ }));
  await user.click(screen.getByRole('button', { name: 'Check in' }));
  expect(await screen.findByRole('alert')).toBeTruthy();
  expect(screen.getByRole('radio', { name: /Job Search/ }).checked).toBe(true);
  await user.click(screen.getByRole('button', { name: 'Check in' }));
  expect(await screen.findByText('B0002')).toBeTruthy();
});

test('front desk loads checked-in visits with their original reason', async () => {
  window.history.replaceState({}, '', '/front-desk');
  const fetchMock = vi.fn().mockResolvedValue({
    ok: true,
    json: async () => [{
      id: 1, ticket: 'B0001', reason: 'DOCUMENT_SERVICES',
      status: 'CHECKED_IN', createdAt: '2026-09-08T12:00:00Z',
    }],
  });
  vi.stubGlobal('fetch', fetchMock);
  render(<App />);
  expect(await screen.findByText('B0001')).toBeTruthy();
  expect(screen.getByText('Document Services')).toBeTruthy();
  expect(fetchMock).toHaveBeenCalledWith(
    '/api/visits?status=CHECKED_IN', expect.any(Object),
  );
});

test('front desk reports a failed queue load and can refresh successfully', async () => {
  window.history.replaceState({}, '', '/front-desk');
  const fetchMock = vi.fn()
    .mockResolvedValueOnce({ ok: false, json: async () => ({ detail: 'Queue unavailable.' }) })
    .mockResolvedValueOnce({ ok: true, json: async () => [] });
  vi.stubGlobal('fetch', fetchMock);
  render(<App />);
  expect(await screen.findByRole('alert')).toBeTruthy();
  await userEvent.setup().click(screen.getByRole('button', { name: 'Refresh queue' }));
  expect(await screen.findByText(/No checked-in visits yet/)).toBeTruthy();
  expect(screen.queryByRole('alert')).toBeNull();
});
test.each([
  ['REEMPLOYMENT', 'Reemployment', 'INITIAL', 'Initial'],
  ['CAREER_SERVICES', 'Career Services', 'INITIAL', 'Initial'],
  ['VETERANS', 'Veterans', 'INITIAL', 'Initial'],
  ['REEMPLOYMENT', 'Reemployment', 'FOLLOWUP', 'Follow-up'],
  ['CAREER_SERVICES', 'Career Services', 'FOLLOWUP', 'Follow-up'],
  ['VETERANS', 'Veterans', 'FOLLOWUP', 'Follow-up'],
])('kiosk creates %s appointment and discards the previous walk-in choice', async (category, label, appointmentType, typeLabel) => {
  const fetchMock = vi.fn().mockResolvedValue({
    ok: true,
    json: async () => ({
      id: 1, ticket: 'A0001', visitType: 'APPOINTMENT', appointmentCategory: category, appointmentType,
    }),
  });
  vi.stubGlobal('fetch', fetchMock);
  render(<App />);
  const user = userEvent.setup();
  expect(screen.queryByRole('radio', { name: /Phone Access/ })).toBeNull();
  await user.click(screen.getByRole('radio', { name: 'Walk-In' }));
  await user.click(screen.getByRole('radio', { name: /Phone Access/ }));
  await user.click(screen.getByRole('radio', { name: 'Appointment' }));
  expect(screen.queryByRole('radio', { name: /Phone Access/ })).toBeNull();
  expect(screen.getByRole('button', { name: 'Check in' }).disabled).toBe(true);
  await user.click(screen.getByRole('radio', { name: new RegExp(label) }));
  expect(screen.getByRole('button', { name: 'Check in' }).disabled).toBe(true);
  await user.click(screen.getByRole('radio', { name: typeLabel }));
  await user.click(screen.getByRole('button', { name: 'Check in' }));
  expect(await screen.findByText('A0001')).toBeTruthy();
  expect(screen.getByText(label)).toBeTruthy();
  expect(fetchMock).toHaveBeenCalledWith('/api/visits', expect.objectContaining({
    method: 'POST',
    body: JSON.stringify({ visitType: 'APPOINTMENT', appointmentCategory: category, appointmentType }),
  }));
});

test('switching from appointment to walk-in discards the category', async () => {
  const fetchMock = vi.fn().mockResolvedValue({
    ok: true,
    json: async () => ({ id: 1, ticket: 'B0001', visitType: 'WALK_IN', reason: 'JOB_SEARCH' }),
  });
  vi.stubGlobal('fetch', fetchMock);
  render(<App />);
  const user = userEvent.setup();
  await user.click(screen.getByRole('radio', { name: 'Appointment' }));
  await user.click(screen.getByRole('radio', { name: /Veterans/ }));
  await user.click(screen.getByRole('radio', { name: 'Follow-up' }));
  await user.click(screen.getByRole('radio', { name: 'Walk-In' }));
  expect(screen.getByRole('button', { name: 'Check in' }).disabled).toBe(true);
  await user.click(screen.getByRole('radio', { name: /Job Search/ }));
  await user.click(screen.getByRole('button', { name: 'Check in' }));
  expect(await screen.findByText('B0001')).toBeTruthy();
  expect(fetchMock).toHaveBeenCalledWith('/api/visits', expect.objectContaining({
    body: '{"visitType":"WALK_IN","reason":"JOB_SEARCH"}',
  }));
});
