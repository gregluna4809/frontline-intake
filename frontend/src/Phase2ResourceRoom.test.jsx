import React from 'react';
import { afterEach, beforeEach, expect, test, vi } from 'vitest';
import { cleanup, render, screen, within, waitFor, act } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import ResourceRoom from './ResourceRoom.jsx';
import { saveAuth } from './auth.js';

const businessDate = '2026-11-30';
const fixedNow = Date.parse('2026-11-30T17:00:00Z');
const ok = data => ({ ok: true, json: async () => data });
beforeEach(() => {
  vi.spyOn(Date, 'now').mockReturnValue(fixedNow);
  saveAuth({ token: 'synthetic-token', staffId: 1, role: 'LSR', displayName: 'Demo Staff 001' });
});
afterEach(() => { cleanup(); sessionStorage.clear(); vi.useRealTimers(); vi.restoreAllMocks(); vi.unstubAllGlobals(); });

function mockRoom({ help = false, claimed = false, claimant = 1 } = {}) {
  const request = { id: 20, visitId: 10, sessionId: 30, ticket: 'B0010', version: claimed ? 1 : 0,
    businessDate, resourceLabel: 'Computer 01', roomName: 'Resource Room', raisedAt: '2026-11-30T16:55:00Z',
    claimedByStaffId: claimed ? claimant : null, claimedByName: claimed ? 'Demo Staff ' + claimant : null };
  const state = {
    resources: [{ id: 1, type: 'COMPUTER', label: 'Computer 01', status: 'OCCUPIED',
      roomCode: 'RESOURCE_ROOM', roomName: 'Resource Room', visitId: 10, ticket: 'B0010', visitVersion: 2,
      assignedAt: '2026-11-30T16:50:00Z' }],
    help: help ? [request] : [],
  };
  const fetchMock = vi.fn(async (path, options = {}) => {
    if (path === '/api/resources') return ok(state.resources.map(item => ({ ...item })));
    if (path === '/api/visits?status=WAITING_RESOURCE') return ok([]);
    if (path === '/api/help-requests?businessDate=2026-11-30&open=true') return ok(state.help.map(item => ({ ...item })));
    if (path === '/api/visits/10/help-requests') { state.help = [{ ...request }]; return ok(request); }
    if (path === '/api/help-requests/20/claim') {
      state.help = [{ ...request, version: 1, claimedByStaffId: 1, claimedByName: 'Demo Staff 001' }];
      return ok(state.help[0]);
    }
    if (path === '/api/help-requests/20/close') {
      state.help = [];
      if (JSON.parse(options.body).outcome === 'NEEDS_PHONE') state.resources = [{ ...state.resources[0], status: 'FREE', visitId: null }];
      return ok({ ...request, version: 2 });
    }
    if (path === '/api/visits/10/phone-override') return ok({ visit: { id: 10, version: 3 } });
    throw new Error('Unexpected request: ' + path);
  });
  vi.stubGlobal('fetch', fetchMock);
  return { fetchMock, state };
}

test('request help sends the visit version and hides the button once open', async () => {
  const { fetchMock } = mockRoom();
  render(<ResourceRoom businessDate={businessDate} />);
  await userEvent.setup().click(await screen.findByRole('button', { name: 'Request help' }));
  expect(await screen.findByRole('article', { name: 'Help B0010' })).toBeTruthy();
  expect(screen.queryByRole('button', { name: 'Request help' })).toBeNull();
  expect(fetchMock).toHaveBeenCalledWith('/api/visits/10/help-requests', expect.objectContaining({ method: 'POST', headers: expect.objectContaining({ 'If-Match': '"2"' }) }));
});

test('claim displays station room age and the three close outcomes for the claimant', async () => {
  const { fetchMock } = mockRoom({ help: true });
  render(<ResourceRoom businessDate={businessDate} />);
  const panel = await screen.findByRole('article', { name: 'Help B0010' });
  expect(panel.textContent).toContain('Computer 01');
  expect(panel.textContent).toContain('Resource Room');
  expect(panel.textContent).toContain('5 min');
  await userEvent.setup().click(within(panel).getByRole('button', { name: 'Claim' }));
  expect(await screen.findByRole('button', { name: 'Resolved online' })).toBeTruthy();
  expect(screen.getByRole('button', { name: 'Needs phone' })).toBeTruthy();
  expect(screen.getByRole('button', { name: 'Referred out' })).toBeTruthy();
  expect(screen.getByText('Claimed by: Demo Staff 001')).toBeTruthy();
  expect(fetchMock).toHaveBeenCalledWith('/api/help-requests/20/claim', expect.objectContaining({ method: 'POST', headers: expect.objectContaining({ 'If-Match': '"0"' }) }));
});

test.each([['Resolved online', 'RESOLVED_ONLINE'], ['Needs phone', 'NEEDS_PHONE'], ['Referred out', 'REFERRED_OUT']])(
  '%s closes with the help version and selected outcome', async (label, outcome) => {
    const { fetchMock } = mockRoom({ help: true, claimed: true });
    render(<ResourceRoom businessDate={businessDate} />);
    await userEvent.setup().click(await screen.findByRole('button', { name: label }));
    await screen.findByText('No open help requests.');
    expect(fetchMock).toHaveBeenCalledWith('/api/help-requests/20/close', expect.objectContaining({ method: 'POST',
      headers: expect.objectContaining({ 'If-Match': '"1"' }), body: JSON.stringify({ outcome }) }));
  },
);

test.each(['LSR', 'CENTER_MANAGER'])('phone override visibility follows role %s and manager sends a note', async role => {
  saveAuth({ token: 'synthetic-token', staffId: role === 'LSR' ? 1 : 9, role, displayName: 'Demo Staff' });
  const { fetchMock } = mockRoom();
  vi.spyOn(window, 'prompt').mockReturnValue('Synthetic phone need');
  render(<ResourceRoom businessDate={businessDate} />);
  await screen.findByRole('article', { name: 'Computer 01' });
  if (role === 'LSR') expect(screen.queryByRole('button', { name: 'Phone override' })).toBeNull();
  else {
    await userEvent.setup().click(screen.getByRole('button', { name: 'Phone override' }));
    await waitFor(() => expect(fetchMock).toHaveBeenCalledWith('/api/visits/10/phone-override', expect.objectContaining({
      method: 'POST', headers: expect.objectContaining({ 'If-Match': '"2"' }), body: '{"note":"Synthetic phone need"}' })));
  }
});

test('LSR cannot see close controls on another claimant request', async () => {
  mockRoom({ help: true, claimed: true, claimant: 2 });
  render(<ResourceRoom businessDate={businessDate} />);
  await screen.findByRole('article', { name: 'Help B0010' });
  expect(screen.queryByRole('button', { name: 'Resolved online' })).toBeNull();
  expect(screen.queryByRole('button', { name: 'Needs phone' })).toBeNull();
  expect(screen.queryByRole('button', { name: 'Referred out' })).toBeNull();
});

test('help requests refresh every five seconds with the inventory', async () => {
  vi.useFakeTimers();
  const { fetchMock } = mockRoom();
  render(<ResourceRoom businessDate={businessDate} />);
  await act(async () => { await vi.advanceTimersByTimeAsync(0); });
  expect(fetchMock.mock.calls.filter(([path]) => path.startsWith('/api/help-requests?'))).toHaveLength(1);
  await act(async () => { await vi.advanceTimersByTimeAsync(5000); });
  expect(fetchMock.mock.calls.filter(([path]) => path.startsWith('/api/help-requests?'))).toHaveLength(2);
});
