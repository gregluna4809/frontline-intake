import { vi } from 'vitest';

export const staff = [
  { id: 1, displayName: 'Demo Staff 001', role: 'LSR' },
  { id: 2, displayName: 'Demo Staff 002', role: 'LSR' },
  { id: 3, displayName: 'Demo Staff 003', role: 'LSR' },
  { id: 4, displayName: 'Demo Staff 004', role: 'LSR' },
  { id: 5, displayName: 'Demo Supervisor 001', role: 'SUPERVISING_LSR' },
];
export const now = Date.parse('2026-09-08T12:00:00Z');
export function appointments() {
  return [null, 1, 2].map((assignedStaffId, index) => ({
    id: index + 1, ticket: 'A000' + (index + 1), version: 2, assignedStaffId,
    visitType: 'APPOINTMENT', status: 'WAITING_STAFF',
    appointmentType: index === 0 ? 'INITIAL' : 'FOLLOWUP',
    appointmentStatus: index === 2 ? 'RESCHEDULED' : 'SCHEDULED',
    waitingSince: new Date(now - 300000).toISOString(),
  }));
}
export const businessStatus = { isOpen: true, currentBusinessDate: '2026-09-08', lastCompletedBusinessDate: '2026-09-04' };
export function rotation() {
  return staff.filter(person => person.role === 'LSR').map(person => ({
    staffId: person.id, displayName: person.displayName, dutyType: 'APPOINTMENTS',
  }));
}
export const ok = data => ({ ok: true, json: async () => data });
export function mockQueues(initial = appointments()) {
  const state = { visits: initial, coverage: rotation() };
  const fetchMock = vi.fn(async (path, options = {}) => {
    if (path === '/api/business-status') return ok(businessStatus);
    if (path === '/api/staff') return ok(staff);
    if (path.startsWith('/api/rotation?date=')) return ok(state.coverage.map(person => ({ ...person })));
    if (path === '/api/call-outs' && options.method === 'POST') {
      const body = JSON.parse(options.body);
      state.coverage = state.coverage.map(person => person.staffId === body.staffId
        ? { ...person, dutyType: 'RESOURCE_ROOM' } : person);
      return ok({ id: 1, ...body });
    }
    if (path.startsWith('/api/visits?status=WAITING_STAFF')) return ok(state.visits.map(visit => ({ ...visit })));
    const match = path.match(/^\/api\/visits\/(\d+)\/(assign-staff|close|reschedule)$/);
    if (!match || options.method !== 'POST') throw new Error('Unexpected request: ' + path);
    const id = Number(match[1]);
    const visit = state.visits.find(item => item.id === id);
    if (options.headers['If-Match'] !== '"' + visit.version + '"') {
      return { ok: false, status: 412, json: async () => ({ detail: 'This visit was changed by another action.' }) };
    }
    if (match[2] === 'reschedule') {
      const replacementId = Math.max(...state.visits.map(item => item.id)) + 1;
      const replacement = { ...visit, id: replacementId, ticket: 'A' + String(replacementId).padStart(4, '0'),
        version: 0, assignedStaffId: null, previousVisitId: visit.id, appointmentStatus: 'RESCHEDULED' };
      state.visits = [...state.visits.filter(item => item.id !== id), replacement];
      return ok({ closedVisit: { visit: { ...visit, status: 'CLOSED' } }, replacementVisit: { visit: replacement } });
    }
    if (match[2] === 'close') state.visits = state.visits.filter(item => item.id !== id);
    else state.visits = state.visits.map(item => item.id === id ?
      { ...item, assignedStaffId: JSON.parse(options.body).staffId, version: item.version + 1 } : item);
    return ok({});
  });
  vi.stubGlobal('fetch', fetchMock);
  return { state, fetchMock };
}
