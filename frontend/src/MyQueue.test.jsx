import React from 'react';
import { afterEach, beforeEach, expect, test, vi } from 'vitest';
import { cleanup, render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import MyQueue from './MyQueue.jsx';
import { appointments, mockQueues, now, ok, staff } from './testQueues.js';

beforeEach(() => { vi.spyOn(Date, 'now').mockReturnValue(now); });
afterEach(() => { cleanup(); vi.restoreAllMocks(); vi.unstubAllGlobals(); });

test('my queue shows only selected LSR appointments and closes a visit', async () => {
  const { fetchMock } = mockQueues();
  render(<MyQueue staffId="1" />);
  expect(await screen.findByText('A0002')).toBeTruthy();
  expect(screen.queryByText('A0001')).toBeNull();
  expect(screen.queryByText('A0003')).toBeNull();
  expect(screen.getByText('Follow-up')).toBeTruthy();
  expect(screen.getByText('Scheduled')).toBeTruthy();
  expect(screen.getByText('5 min')).toBeTruthy();
  await userEvent.setup().click(screen.getByRole('button', { name: 'Close A0002' }));
  expect(await screen.findByText('No appointments assigned to you.')).toBeTruthy();
  expect(screen.queryByText('A0002')).toBeNull();
  expect(fetchMock).toHaveBeenCalledWith('/api/visits/2/close', expect.objectContaining({
    method: 'POST', headers: expect.objectContaining({ 'If-Match': '"2"' }),
  }));
  expect(fetchMock.mock.calls.some(([path]) => path.includes('/resources') || path.includes('WAITING_RESOURCE'))).toBe(false);
});

test('an LSR with no assigned visits sees an empty queue', async () => {
  mockQueues();
  render(<MyQueue staffId="4" />);
  expect(await screen.findByText('No appointments assigned to you.')).toBeTruthy();
  expect(screen.queryByRole('button', { name: /Close A/ })).toBeNull();
});

test.each(['', '999', '5'])('missing or non-LSR selection %s does not expose other queues', async staffId => {
  mockQueues();
  render(<MyQueue staffId={staffId} />);
  expect(await screen.findByText('Choose an LSR from View as to see their queue.')).toBeTruthy();
  expect(screen.queryByText('A0002')).toBeNull();
});

test('stale close reloads the latest version before retry', async () => {
  const { state, fetchMock } = mockQueues();
  state.visits[1].version = 3;
  let firstRead = true;
  const implementation = fetchMock.getMockImplementation();
  fetchMock.mockImplementation((path, options) => {
    if (path.includes('WAITING_STAFF') && firstRead) {
      firstRead = false;
      return Promise.resolve(ok(state.visits.map(visit => ({ ...visit, version: 2 }))));
    }
    return implementation(path, options);
  });
  render(<MyQueue staffId="1" />);
  const user = userEvent.setup();
  await user.click(await screen.findByRole('button', { name: 'Close A0002' }));
  expect(await screen.findByRole('alert')).toBeTruthy();
  await waitFor(() => expect(screen.getByRole('button', { name: 'Close A0002' }).disabled).toBe(false));
  await user.click(screen.getByRole('button', { name: 'Close A0002' }));
  expect(await screen.findByText('No appointments assigned to you.')).toBeTruthy();
  expect(fetchMock.mock.calls.filter(([path]) => path.endsWith('/close'))
    .map(([, options]) => options.headers['If-Match'])).toEqual(['"2"', '"3"']);
});

test('a failed queue load does not report an empty successful queue', async () => {
  vi.stubGlobal('fetch', vi.fn(async path => {
    if (path === '/api/staff') return ok(staff);
    throw new Error('Offline');
  }));
  render(<MyQueue staffId="1" />);
  expect(await screen.findByText('Queue unavailable.')).toBeTruthy();
  expect(screen.queryByText('No appointments assigned to you.')).toBeNull();
});


test('my queue can reschedule an assigned appointment and reloads after a stale version', async () => {
  const { state, fetchMock } = mockQueues();
  state.visits[1].version = 3;
  let firstRead = true;
  const original = fetchMock.getMockImplementation();
  fetchMock.mockImplementation((path, options) => {
    if (path.includes('WAITING_STAFF') && firstRead) {
      firstRead = false;
      return Promise.resolve(ok(state.visits.map(visit => ({ ...visit, version: 2 }))));
    }
    return original(path, options);
  });
  render(<MyQueue staffId="1" />);
  const user = userEvent.setup();
  await user.click(await screen.findByRole('button', { name: 'Reschedule A0002' }));
  expect(await screen.findByRole('alert')).toBeTruthy();
  await waitFor(() => expect(screen.getByRole('button', { name: 'Reschedule A0002' }).disabled).toBe(false));
  await user.click(screen.getByRole('button', { name: 'Reschedule A0002' }));
  expect(await screen.findByText('Ticket A0002 rescheduled as A0004.')).toBeTruthy();
  expect(await screen.findByText('No appointments assigned to you.')).toBeTruthy();
  expect(fetchMock.mock.calls.filter(([path]) => path.endsWith('/reschedule'))
    .map(([, options]) => options.headers['If-Match'])).toEqual(['"2"', '"3"']);
});
