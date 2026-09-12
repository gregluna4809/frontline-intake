vi.mock('./SimulatorControl.jsx', () => ({ default: () => null }));
import React from 'react';
import { afterEach, expect, test, vi } from 'vitest';
import { cleanup, render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import App from './App.jsx';
import { mockQueues, staff } from './testQueues.js';

afterEach(() => { cleanup(); vi.unstubAllGlobals(); window.history.replaceState({}, '', '/kiosk'); });

test('role selector includes Supervisor and one entry per LSR and navigates to both screens', async () => {
  window.history.replaceState({}, '', '/kiosk');
  mockQueues();
  render(<App />);
  const user = userEvent.setup();
  await screen.findByRole('option', { name: 'My Queue — Demo Staff 001' });
  for (const person of staff.filter(item => item.role === 'LSR')) {
    expect(screen.getByRole('option', { name: 'My Queue — ' + person.displayName })).toBeTruthy();
  }
  expect(screen.queryByRole('option', { name: 'Appointments' })).toBeNull();
  expect(screen.queryByRole('option', { name: /My Queue.*Demo Supervisor/ })).toBeNull();
  await user.selectOptions(screen.getByRole('combobox', { name: 'Role' }), 'supervisor');
  expect(await screen.findByRole('heading', { name: 'Supervisor board' })).toBeTruthy();
  expect(window.location.pathname).toBe('/supervisor');
  await user.selectOptions(screen.getByRole('combobox', { name: 'Role' }), 'my-queue:1');
  expect(await screen.findByText('A0002')).toBeTruthy();
  expect(window.location.pathname + window.location.search).toBe('/my-queue?staff=1');
  expect(screen.getByRole('combobox', { name: 'Role' }).value).toBe('my-queue:1');
  expect(screen.queryByRole('option', { name: 'Resource room' })).toBeNull();
  await user.selectOptions(screen.getByRole('combobox', { name: 'Role' }), 'my-queue:2');
  expect(await screen.findByText('A0003')).toBeTruthy();
  expect(screen.queryByText('A0002')).toBeNull();
  expect(window.location.search).toBe('?staff=2');
});

test('a direct My Queue URL restores selected LSR without resource requests', async () => {
  window.history.replaceState({}, '', '/my-queue?staff=2');
  const { fetchMock } = mockQueues();
  render(<App />);
  expect(await screen.findByText('A0003')).toBeTruthy();
  await waitFor(() => expect(screen.getByRole('combobox', { name: 'Role' }).value).toBe('my-queue:2'));
  expect(screen.queryByText('A0002')).toBeNull();
  expect(fetchMock.mock.calls.some(([path]) => path === '/api/resources')).toBe(false);
});

test('the superseded appointments URL redirects to supervisor', async () => {
  window.history.replaceState({}, '', '/appointments');
  mockQueues();
  render(<App />);
  expect(await screen.findByRole('heading', { name: 'Supervisor board' })).toBeTruthy();
  await waitFor(() => expect(window.location.pathname).toBe('/supervisor'));
  expect(screen.queryByRole('option', { name: 'Appointments' })).toBeNull();
});
