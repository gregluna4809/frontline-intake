import React from 'react';
import { afterEach, expect, test, vi } from 'vitest';
import { cleanup, render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import FrontDesk from './FrontDesk.jsx';

afterEach(() => {
  cleanup();
  vi.unstubAllGlobals();
  window.history.replaceState({}, '', '/front-desk');
});

function initialDetail(overrides = {}) {
  return {
    visit: {
      id: 1, ticket: 'B0001', visitType: 'WALK_IN', reason: 'PHONE_ACCESS',
      appointmentCategory: null, status: 'CHECKED_IN', version: 0,
      customerId: null, resourceId: null, createdAt: '2026-09-08T12:00:00Z',
      ...overrides,
    },
    customer: null, resource: null, events: [], requiredResourceType: 'PHONE',
  };
}
const customer = { id: 1, displayName: 'Demo Customer 001' };
const ok = data => ({ ok: true, json: async () => data });

function mockWorkflow({ finalStatus = 'AT_RESOURCE', appointment = false, failRoute = false, missedAppointment = null } = {}) {
  let detail = initialDetail(appointment ? {
    visitType: 'APPOINTMENT', reason: null, appointmentCategory: 'Reemployment Appointment',
  } : {});
  let routeFailed = false;
  const mock = vi.fn(async (path, options = {}) => {
    if (path.startsWith('/api/visits?status=')) {
      return ok(detail.visit.status === path.split('=')[1] ? [detail.visit] : []);
    }
    if (path === '/api/visits/1') return ok(detail);
    if (path.startsWith('/api/customers?q=')) return ok([customer]);
    if (path === '/api/visits/1/identify') {
      detail = { ...detail, customer, missedAppointment, visit: { ...detail.visit, customerId: 1, status: 'IDENTIFIED', version: 1 } };
      return ok(detail);
    }
    if (path === '/api/visits/1/route') {
      if (failRoute && !routeFailed) {
        routeFailed = true;
        detail = { ...detail, visit: { ...detail.visit, version: 2 } };
        return { ok: false, status: 412, json: async () => ({ detail: 'This visit was changed by another action.' }) };
      }
      detail = {
        ...detail,
        visit: { ...detail.visit, status: finalStatus, version: detail.visit.version + 1 },
        resource: finalStatus === 'AT_RESOURCE' ? { id: 1, label: 'Phone 01', type: 'PHONE' } : null,
      };
      return ok(detail);
    }
    throw new Error('Unexpected request: ' + path);
  });
  vi.stubGlobal('fetch', mock);
  return mock;
}

async function identify(user) {
  await user.click(await screen.findByRole('button', { name: 'B0001' }));
  expect((await screen.findByRole('button', { name: 'Route' })).disabled).toBe(true);
  await user.type(screen.getByRole('searchbox'), 'Demo');
  await user.click(await screen.findByRole('button', { name: /Demo Customer 001/ }));
  await waitFor(() => expect(screen.getByRole('button', { name: 'Route' }).disabled).toBe(false));
  const panel = screen.getByRole('complementary', { name: 'Selected visit' });
  expect(within(panel).getByText('Demo Customer 001')).toBeTruthy();
  expect(within(panel).getByText('ID: 1')).toBeTruthy();
}

test.each([
  ['AT_RESOURCE', false, 'Assigned to Phone 01'],
  ['WAITING_RESOURCE', false, 'Waiting — no phone free'],
  ['WAITING_STAFF', true, 'Sent to appointments'],
])('identify and route displays %s outcome', async (finalStatus, appointment, message) => {
  const fetchMock = mockWorkflow({ finalStatus, appointment });
  render(<FrontDesk />);
  const user = userEvent.setup();
  await identify(user);
  if (appointment) expect(within(screen.getByRole('complementary')).getByText('Reemployment Appointment')).toBeTruthy();
  await user.click(screen.getByRole('button', { name: 'Route' }));
  expect(await screen.findByText(message)).toBeTruthy();
  expect(screen.queryByRole('button', { name: 'B0001' })).toBeNull();
  expect(fetchMock).toHaveBeenCalledWith('/api/visits/1/identify', expect.objectContaining({
    method: 'POST', body: '{"customerId":1}', headers: expect.objectContaining({ 'If-Match': '"0"' }),
  }));
  expect(fetchMock).toHaveBeenCalledWith('/api/visits/1/route', expect.objectContaining({
    method: 'POST', headers: expect.objectContaining({ 'If-Match': '"1"' }),
  }));
  expect(screen.getByRole('button', { name: 'Route' }).disabled).toBe(true);
});

test('stale mutation requires reloading and retry uses the new version', async () => {
  const fetchMock = mockWorkflow({ failRoute: true });
  render(<FrontDesk />);
  const user = userEvent.setup();
  await identify(user);
  await user.click(screen.getByRole('button', { name: 'Route' }));
  expect(await screen.findByRole('alert')).toBeTruthy();
  expect(screen.getByRole('button', { name: 'Route' }).disabled).toBe(true);
  await user.click(screen.getByRole('button', { name: 'Reload visit' }));
  await waitFor(() => expect(screen.getByRole('button', { name: 'Route' }).disabled).toBe(false));
  await user.click(screen.getByRole('button', { name: 'Route' }));
  expect(await screen.findByText('Assigned to Phone 01')).toBeTruthy();
  expect(fetchMock).toHaveBeenLastCalledWith('/api/visits?status=IDENTIFIED', expect.any(Object));
  const mutations = fetchMock.mock.calls.filter(([path]) => path === '/api/visits/1/route');
  expect(mutations.map(([, options]) => options.headers['If-Match'])).toEqual(['"1"', '"2"']);
});

test('selected identified visit can be resumed after a page reload', async () => {
  window.history.replaceState({}, '', '/front-desk?visit=1');
  const detail = initialDetail({ status: 'IDENTIFIED', customerId: 1, version: 1 });
  detail.customer = customer;
  vi.stubGlobal('fetch', vi.fn(async path => ok(path === '/api/visits/1' ? detail : [])));
  render(<FrontDesk />);
  expect(await screen.findByText('Demo Customer 001')).toBeTruthy();
  expect(screen.getByRole('button', { name: 'Route' }).disabled).toBe(false);
});

test('late customer search response cannot replace the newer results', async () => {
  let finishOldSearch;
  vi.stubGlobal('fetch', vi.fn(async path => {
    if (path === '/api/visits?status=CHECKED_IN') return ok([initialDetail().visit]);
    if (path === '/api/visits/1') return ok(initialDetail());
    if (path === '/api/customers?q=001') return new Promise(resolve => { finishOldSearch = resolve; });
    if (path === '/api/customers?q=002') return ok([{ id: 2, displayName: 'Demo Customer 002' }]);
    return ok([]);
  }));
  render(<FrontDesk />);
  const user = userEvent.setup();
  await user.click(await screen.findByRole('button', { name: 'B0001' }));
  const input = await screen.findByRole('searchbox');
  await user.type(input, '001');
  await waitFor(() => expect(finishOldSearch).toBeTypeOf('function'));
  await user.clear(input);
  await user.type(input, '002');
  expect(await screen.findByRole('button', { name: /Demo Customer 002/ })).toBeTruthy();
  finishOldSearch(ok([customer]));
  await waitFor(() => expect(screen.queryByRole('button', { name: /Demo Customer 001/ })).toBeNull());
});


test('identified visit remains in creation order and can be resumed after selecting another visit', async () => {
  let first = initialDetail();
  const second = initialDetail({ id: 2, ticket: 'B0002', createdAt: '2026-09-08T12:01:00Z' });
  const fetchMock = vi.fn(async (path, options = {}) => {
    if (path.startsWith('/api/visits?status=')) {
      return ok([first.visit, second.visit].filter(visit => visit.status === path.split('=')[1]));
    }
    if (path === '/api/visits/1') return ok(first);
    if (path === '/api/visits/2') return ok(second);
    if (path.startsWith('/api/customers?q=')) return ok([customer]);
    if (path === '/api/visits/1/identify') {
      first = { ...first, customer, visit: { ...first.visit, customerId: 1, status: 'IDENTIFIED', version: 1 } };
      return ok(first);
    }
    if (path === '/api/visits/1/route') {
      expect(options.headers['If-Match']).toBe('"1"');
      first = { ...first, resource: { id: 1, label: 'Phone 01' },
        visit: { ...first.visit, resourceId: 1, status: 'AT_RESOURCE', version: 2 } };
      return ok(first);
    }
    throw new Error('Unexpected request: ' + path);
  });
  vi.stubGlobal('fetch', fetchMock);
  render(<FrontDesk />);
  const user = userEvent.setup();
  expect(within((await screen.findByRole('button', { name: 'B0001' })).closest('tr')).getByText('Needs ID')).toBeTruthy();
  await identify(user);
  await user.click(screen.getByRole('button', { name: 'B0002' }));
  expect(await screen.findByRole('heading', { name: 'Ticket B0002' })).toBeTruthy();
  await user.click(screen.getByRole('button', { name: 'Refresh queue' }));
  await waitFor(() => expect(fetchMock.mock.calls.filter(([path]) => path.endsWith('status=IDENTIFIED')).length).toBeGreaterThanOrEqual(3));
  expect(within(screen.getByRole('table')).getAllByRole('button').map(button => button.textContent))
    .toEqual(['B0001', 'B0002']);
  expect(within(screen.getByRole('button', { name: 'B0001' }).closest('tr')).getByText('Ready to route')).toBeTruthy();
  expect(within(screen.getByRole('button', { name: 'B0002' }).closest('tr')).getByText('Needs ID')).toBeTruthy();
  await user.click(screen.getByRole('button', { name: 'B0001' }));
  expect(await screen.findByText('Demo Customer 001')).toBeTruthy();
  expect(screen.getByText('ID: 1')).toBeTruthy();
  expect(screen.queryByRole('searchbox')).toBeNull();
  await user.click(screen.getByRole('button', { name: 'Route' }));
  expect(await screen.findByText('Assigned to Phone 01')).toBeTruthy();
  expect(screen.queryByRole('button', { name: 'B0001' })).toBeNull();
  expect(screen.getByRole('button', { name: 'B0002' })).toBeTruthy();
  expect(fetchMock).toHaveBeenCalledWith('/api/visits/1/route', expect.objectContaining({
    method: 'POST', headers: expect.objectContaining({ 'If-Match': '"1"' }),
  }));
});


test('identifying an FTR return shows the missed appointment and preserves normal routing', async () => {
  mockWorkflow({ missedAppointment: { id: 90, ticket: 'A0090', businessDate: '2026-09-04', appointmentStatus: 'NOT_ATTENDED_FTR' } });
  render(<FrontDesk />);
  expect(screen.queryByText('Customer has a missed appointment (FTR).')).toBeNull();
  const user = userEvent.setup();
  await identify(user);
  expect(screen.getByText('Customer has a missed appointment (FTR).')).toBeTruthy();
  expect(screen.getByText('Ticket A0090 from 2026-09-04.')).toBeTruthy();
  await user.click(screen.getByRole('button', { name: 'Route' }));
  expect(await screen.findByText('Assigned to Phone 01')).toBeTruthy();
});

test('identifying a customer without current FTR history shows no missed appointment notice', async () => {
  mockWorkflow();
  render(<FrontDesk />);
  await identify(userEvent.setup());
  expect(screen.queryByText('Customer has a missed appointment (FTR).')).toBeNull();
});

test('resuming an identified visit shows FTR history and reload removes a reported FTR', async () => {
  window.history.replaceState({}, '', '/front-desk?visit=1');
  let detail = { ...initialDetail({ status: 'IDENTIFIED', customerId: 1, version: 1 }), customer,
    missedAppointment: { id: 90, ticket: 'A0090', businessDate: '2026-09-04' } };
  vi.stubGlobal('fetch', vi.fn(async path => ok(path === '/api/visits/1' ? detail : [])));
  render(<FrontDesk />);
  expect(await screen.findByText('Customer has a missed appointment (FTR).')).toBeTruthy();
  detail = { ...detail, missedAppointment: null };
  await userEvent.setup().click(screen.getByRole('button', { name: 'Reload visit' }));
  await waitFor(() => expect(screen.queryByText('Customer has a missed appointment (FTR).')).toBeNull());
  expect(screen.getByRole('button', { name: 'Route' }).disabled).toBe(false);
});
