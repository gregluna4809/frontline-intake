import React from 'react';
import { afterEach, beforeEach, expect, test, vi } from 'vitest';
import { act, cleanup, render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import Supervisor from './Supervisor.jsx';
import { appointments, mockQueues, now, ok, staff } from './testQueues.js';

beforeEach(() => { vi.spyOn(Date, 'now').mockReturnValue(now); });
afterEach(() => { cleanup(); vi.useRealTimers(); vi.restoreAllMocks(); vi.unstubAllGlobals(); });

test('supervisor groups unassigned and assigned appointments and excludes supervising targets', async () => {
  mockQueues();
  render(<Supervisor />);
  const unassigned = await screen.findByRole('region', { name: 'Unassigned' });
  expect(within(unassigned).getByText('A0001')).toBeTruthy();
  expect(within(unassigned).getByText('Initial')).toBeTruthy();
  expect(within(unassigned).getByText('Scheduled')).toBeTruthy();
  expect(within(unassigned).getByText('5 min')).toBeTruthy();
  expect(within(screen.getByRole('region', { name: 'Demo Staff 001' })).getByText('A0002')).toBeTruthy();
  expect(within(screen.getByRole('region', { name: 'Demo Staff 002' })).getByText('A0003')).toBeTruthy();
  expect(screen.getAllByRole('region').map(region => region.getAttribute('aria-label')))
    .toEqual(['Unassigned', ...staff.filter(person => person.role === 'LSR').map(person => person.displayName)]);
  expect(screen.queryByText('Demo Supervisor 001')).toBeNull();
  expect(screen.getByRole('button', { name: 'Assign A0001' }).disabled).toBe(true);
});

test('assignment and reassignment move the visit between groups with current versions', async () => {
  const { fetchMock } = mockQueues();
  render(<Supervisor />);
  const user = userEvent.setup();
  await user.selectOptions(await screen.findByRole('combobox', { name: 'Staff for A0001' }), '1');
  await user.click(screen.getByRole('button', { name: 'Assign A0001' }));
  await waitFor(() => expect(within(screen.getByRole('region', { name: 'Demo Staff 001' })).getByText('A0001')).toBeTruthy());
  await waitFor(() => expect(screen.getByRole('button', { name: 'Reassign A0001' }).disabled).toBe(false));
  expect(within(screen.getByRole('region', { name: 'Unassigned' })).queryByText('A0001')).toBeNull();
  await user.selectOptions(screen.getByRole('combobox', { name: 'Staff for A0001' }), '2');
  await user.click(screen.getByRole('button', { name: 'Reassign A0001' }));
  await waitFor(() => expect(within(screen.getByRole('region', { name: 'Demo Staff 002' })).getByText('A0001')).toBeTruthy());
  expect(within(screen.getByRole('region', { name: 'Demo Staff 001' })).queryByText('A0001')).toBeNull();
  const mutations = fetchMock.mock.calls.filter(([path]) => path === '/api/visits/1/assign-staff');
  expect(mutations.map(([, options]) => options.headers['If-Match'])).toEqual(['"2"', '"3"']);
  expect(mutations.map(([, options]) => JSON.parse(options.body))).toEqual([{ staffId: 1 }, { staffId: 2 }]);
});

test('stale assignment blocks retry until refreshed and retry uses the new version', async () => {
  const visits = appointments();
  let finishRefresh;
  let failed = false;
  const fetchMock = vi.fn(async (path, options = {}) => {
    if (path === '/api/staff') return ok(staff);
    if (path.includes('WAITING_STAFF')) {
      if (failed && !finishRefresh) return new Promise(resolve => { finishRefresh = resolve; });
      return ok(visits.map(visit => ({ ...visit })));
    }
    if (!failed) {
      failed = true;
      visits[0].version++;
      return { ok: false, status: 412, json: async () => ({ detail: 'This visit was changed by another action.' }) };
    }
    visits[0].assignedStaffId = JSON.parse(options.body).staffId;
    visits[0].version++;
    return ok({});
  });
  vi.stubGlobal('fetch', fetchMock);
  render(<Supervisor />);
  const user = userEvent.setup();
  await user.selectOptions(await screen.findByRole('combobox', { name: 'Staff for A0001' }), '1');
  await user.click(screen.getByRole('button', { name: 'Assign A0001' }));
  expect(await screen.findByRole('alert')).toBeTruthy();
  expect(screen.getByRole('button', { name: 'Assign A0001' }).disabled).toBe(true);
  await waitFor(() => expect(finishRefresh).toBeTypeOf('function'));
  await act(async () => { finishRefresh(ok(visits.map(visit => ({ ...visit })))); });
  await user.selectOptions(screen.getByRole('combobox', { name: 'Staff for A0001' }), '2');
  await user.click(screen.getByRole('button', { name: 'Assign A0001' }));
  await waitFor(() => expect(within(screen.getByRole('region', { name: 'Demo Staff 002' })).getByText('A0001')).toBeTruthy());
  expect(fetchMock.mock.calls.filter(([path]) => path.endsWith('/assign-staff'))
    .map(([, options]) => options.headers['If-Match'])).toEqual(['"2"', '"3"']);
});

test('polling and manual refresh reload visits and staff', async () => {
  vi.useFakeTimers();
  const { fetchMock } = mockQueues();
  render(<Supervisor />);
  await act(async () => { await vi.advanceTimersByTimeAsync(0); });
  expect(fetchMock.mock.calls.filter(([path]) => path === '/api/staff')).toHaveLength(1);
  await act(async () => { await vi.advanceTimersByTimeAsync(5000); });
  expect(fetchMock.mock.calls.filter(([path]) => path === '/api/staff')).toHaveLength(2);
  expect(fetchMock.mock.calls.filter(([path]) => path.includes('WAITING_STAFF'))).toHaveLength(2);
  vi.useRealTimers();
  await userEvent.setup().click(screen.getByRole('button', { name: 'Refresh queue' }));
  await waitFor(() => expect(fetchMock.mock.calls.filter(([path]) => path === '/api/staff')).toHaveLength(3));
});

test('a failed load reports an unavailable board and manual refresh recovers', async () => {
  let offline = true;
  vi.stubGlobal('fetch', vi.fn(async path => {
    if (offline) throw new Error('Offline');
    return ok(path === '/api/staff' ? staff : appointments());
  }));
  render(<Supervisor />);
  expect(await screen.findByText('Board unavailable.')).toBeTruthy();
  expect(screen.queryByRole('region', { name: 'Unassigned' })).toBeNull();
  offline = false;
  await userEvent.setup().click(screen.getByRole('button', { name: 'Refresh queue' }));
  expect(await screen.findByRole('region', { name: 'Unassigned' })).toBeTruthy();
});

test('appointments assigned outside the LSR list remain available for reassignment', async () => {
  mockQueues([{ ...appointments()[0], assignedStaffId: 5 }]);
  render(<Supervisor />);
  expect(within(await screen.findByRole('region', { name: 'Other assignments' })).getByText('A0001')).toBeTruthy();
  expect(screen.queryByRole('option', { name: 'Demo Supervisor 001' })).toBeNull();
});
