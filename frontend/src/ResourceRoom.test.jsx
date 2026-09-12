vi.mock('./SimulatorControl.jsx', () => ({ default: () => null }));
import React from 'react';
import { afterEach, beforeEach, expect, test, vi } from 'vitest';
import { act, cleanup, render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import App from './App.jsx';
import { RESOURCE_ALERT_MINUTES } from './ResourceRoom.jsx';

const now = Date.parse('2026-09-08T12:00:00Z');
beforeEach(() => {
  window.history.replaceState({}, '', '/resource-room');
  vi.spyOn(Date, 'now').mockReturnValue(now);
});
afterEach(() => {
  cleanup();
  vi.useRealTimers();
  vi.restoreAllMocks();
  vi.unstubAllGlobals();
  window.history.replaceState({}, '', '/kiosk');
});
const ago = minutes => new Date(now - minutes * 60000).toISOString();
const ok = data => ({ ok: true, json: async () => data });

function fixture() {
  return {
    resources: [
      { id: 1, type: 'PHONE', label: 'Phone 01', status: 'OCCUPIED', visitId: 1, ticket: 'B0001',
        visitVersion: 2, customer: { displayName: 'Demo Customer 001' }, assignedAt: ago(46) },
      { id: 2, type: 'PHONE', label: 'Phone 02', status: 'OCCUPIED', visitId: 2, ticket: 'B0002',
        visitVersion: 2, customer: { displayName: 'Demo Customer 002' }, assignedAt: ago(45) },
      { id: 3, type: 'COMPUTER', label: 'Computer 01', status: 'FREE', visitId: null },
      { id: 4, type: 'STATION', label: 'Station 01', status: 'FREE', visitId: null },
    ],
    waiting: [
      { id: 3, ticket: 'B0003', version: 2, requiredResourceType: 'PHONE',
        customer: { displayName: 'Demo Customer 003' }, waitingSince: ago(7) },
      { id: 4, ticket: 'B0004', version: 2, requiredResourceType: 'COMPUTER',
        customer: { displayName: 'Demo Customer 004' }, waitingSince: ago(3) },
    ],
  };
}

function mockRoom({ conflict = false } = {}) {
  const state = fixture();
  const fetchMock = vi.fn(async (path, options = {}) => {
    if (path === '/api/resources') return ok([...state.resources]);
    if (path === '/api/visits?status=WAITING_RESOURCE') return ok([...state.waiting]);
    if (options.method !== 'POST') throw new Error('Unexpected request');
    const [, id, action] = path.match(/\/api\/visits\/(\d+)\/(close|left|assign)/);
    const visitId = Number(id);
    if (conflict) {
      state.resources = state.resources.map(resource => resource.type === 'COMPUTER'
        ? { ...resource, status: 'OCCUPIED', visitId: 99, ticket: 'B0099', visitVersion: 2,
            customer: { displayName: 'Demo Customer 009' }, assignedAt: ago(1) }
        : resource);
      return { ok: false, json: async () => ({ detail: 'No matching resource is free.' }) };
    }
    if (action === 'assign') {
      state.waiting = state.waiting.filter(visit => visit.id !== visitId);
      state.resources = state.resources.map(resource => resource.id === 3
        ? { ...resource, status: 'OCCUPIED', visitId, ticket: 'B0004', visitVersion: 3,
            customer: { displayName: 'Demo Customer 004' }, assignedAt: ago(0) }
        : resource);
      return ok({ resource: { label: 'Computer 01' } });
    }
    state.waiting = state.waiting.filter(visit => visit.id !== visitId);
    state.resources = state.resources.map(resource => resource.visitId === visitId
      ? { ...resource, status: 'FREE', visitId: null, ticket: null, customer: null, assignedAt: null }
      : resource);
    return ok({});
  });
  vi.stubGlobal('fetch', (path, options) => path === '/api/staff'
    ? Promise.resolve({ ok: true, json: async () => [] }) : fetchMock(path, options));
  return { state, fetchMock };
}

test('resource room groups inventory, shows customers, and flags only visits over 45 minutes', async () => {
  mockRoom();
  render(<App />);
  await screen.findByRole('article', { name: 'Phone 01' });
  expect(screen.getByRole('combobox', { name: 'Role' }).value).toBe('resource-room');
  for (const label of ['Phones', 'Computers', 'Stations']) {
    expect(screen.getByRole('region', { name: label })).toBeTruthy();
  }
  const longVisit = screen.getByRole('article', { name: 'Phone 01' });
  expect(within(longVisit).getByText('B0001')).toBeTruthy();
  expect(within(longVisit).getByText('Demo Customer 001')).toBeTruthy();
  expect(within(longVisit).getByText('OCCUPIED')).toBeTruthy();
  expect(within(longVisit).getByText('Over 45 min')).toBeTruthy();
  expect(longVisit.className).toContain('overdue');
  const exactThreshold = screen.getByRole('article', { name: 'Phone 02' });
  expect(exactThreshold.className).not.toContain('overdue');
  expect(RESOURCE_ALERT_MINUTES).toBe(45);
  expect(screen.getByText('Synthetic demo — no real data')).toBeTruthy();
});

test('waiting entries show needed type and wait time, with Assign disabled only for unavailable types', async () => {
  mockRoom();
  render(<App />);
  const waiting = await screen.findByRole('article', { name: 'Waiting B0003' });
  expect(within(waiting).getByText('Demo Customer 003')).toBeTruthy();
  expect(within(waiting).getByText('Needs: Phone')).toBeTruthy();
  expect(within(waiting).getByText('Waiting: 7 min')).toBeTruthy();
  expect(screen.getByRole('button', { name: 'Assign B0003' }).disabled).toBe(true);
  expect(screen.getByRole('button', { name: 'Assign B0004' }).disabled).toBe(false);
  expect(screen.getByRole('button', { name: 'Left B0003' }).disabled).toBe(false);
});

test.each(['close', 'left'])('%s on an occupied tile frees inventory and enables a waiting assignment', async action => {
  const { fetchMock } = mockRoom();
  render(<App />);
  const name = (action === 'close' ? 'Close' : 'Left') + ' B0001';
  await userEvent.setup().click(await screen.findByRole('button', { name }));
  await waitFor(() => expect(screen.getByRole('button', { name: 'Assign B0003' }).disabled).toBe(false));
  expect(within(screen.getByRole('article', { name: 'Phone 01' })).getByText('FREE')).toBeTruthy();
  expect(screen.queryByRole('button', { name: 'Close B0001' })).toBeNull();
  expect(fetchMock).toHaveBeenCalledWith('/api/visits/1/' + action, expect.objectContaining({
    method: 'POST', headers: expect.objectContaining({ 'If-Match': '"2"' }),
  }));
});

test('assign removes the waiting entry and shows the new resource occupant', async () => {
  const { fetchMock } = mockRoom();
  render(<App />);
  await userEvent.setup().click(await screen.findByRole('button', { name: 'Assign B0004' }));
  expect(await screen.findByRole('button', { name: 'Close B0004' })).toBeTruthy();
  expect(screen.queryByRole('article', { name: 'Waiting B0004' })).toBeNull();
  expect(fetchMock).toHaveBeenCalledWith('/api/visits/4/assign', expect.objectContaining({
    method: 'POST', headers: expect.objectContaining({ 'If-Match': '"2"' }),
  }));
});

test('left on a waiting entry removes it without claiming a resource', async () => {
  const { fetchMock } = mockRoom();
  render(<App />);
  await userEvent.setup().click(await screen.findByRole('button', { name: 'Left B0003' }));
  await waitFor(() => expect(screen.queryByRole('article', { name: 'Waiting B0003' })).toBeNull());
  expect(within(screen.getByRole('article', { name: 'Computer 01' })).getByText('FREE')).toBeTruthy();
  expect(fetchMock).toHaveBeenCalledWith('/api/visits/3/left', expect.objectContaining({
    method: 'POST', headers: expect.objectContaining({ 'If-Match': '"2"' }),
  }));
});

test('failed assignment keeps the visit waiting and refreshes changed resource availability', async () => {
  const { fetchMock } = mockRoom({ conflict: true });
  render(<App />);
  await userEvent.setup().click(await screen.findByRole('button', { name: 'Assign B0004' }));
  expect(await screen.findByRole('alert')).toBeTruthy();
  await screen.findByRole('button', { name: 'Close B0099' });
  expect(screen.getByRole('article', { name: 'Waiting B0004' })).toBeTruthy();
  expect(screen.getByRole('button', { name: 'Assign B0004' }).disabled).toBe(true);
  expect(fetchMock.mock.calls.filter(([path]) => path === '/api/visits/4/assign')).toHaveLength(1);
});

test('polling reloads both inventory and waiting visits after five seconds', async () => {
  vi.useFakeTimers();
  const { fetchMock } = mockRoom();
  render(<App />);
  await act(async () => { await vi.advanceTimersByTimeAsync(0); });
  expect(fetchMock.mock.calls.filter(([path]) => path === '/api/resources')).toHaveLength(1);
  await act(async () => { await vi.advanceTimersByTimeAsync(5000); });
  expect(fetchMock.mock.calls.filter(([path]) => path === '/api/resources')).toHaveLength(2);
  expect(fetchMock.mock.calls.filter(([path]) => path.includes('WAITING_RESOURCE'))).toHaveLength(2);
});

test('failed inventory load does not display a misleading empty queue', async () => {
  vi.stubGlobal('fetch', vi.fn().mockRejectedValue(new Error('Offline')));
  render(<App />);
  expect(await screen.findByRole('alert')).toBeTruthy();
  expect(screen.getByText('Inventory unavailable.')).toBeTruthy();
  expect(screen.queryByText('No visits waiting for a resource.')).toBeNull();
});
