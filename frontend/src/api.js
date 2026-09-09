export async function request(path, options = {}) {
  let response;
  try {
    response = await fetch(path, {
      ...options,
      headers: { 'Content-Type': 'application/json', ...options.headers },
    });
  } catch {
    throw new Error('Cannot reach the Career Center. Please try again.');
  }
  if (!response.ok) {
    const problem = await response.json().catch(() => null);
    throw new Error(problem?.detail || 'Unable to complete the request. Please try again.');
  }
  return response.json();
}

export const reasons = [
  ['PHONE_ACCESS', 'Phone Access'],
  ['COMPUTER_ACCESS', 'Computer Access'],
  ['DOCUMENT_SERVICES', 'Document Services'],
  ['BENEFITS_ASSISTANCE', 'Benefits Assistance'],
  ['JOB_SEARCH', 'Job Search'],
  ['OTHER', 'Other'],
];

export function reasonLabel(value) {
  return reasons.find(([code]) => code === value)?.[1] || value;
}

export const appointmentCategories = [
  ['REEMPLOYMENT', 'Reemployment'],
  ['CAREER_SERVICES', 'Career Services'],
  ['VETERANS', 'Veterans'],
];

export function appointmentCategoryLabel(value) {
  return appointmentCategories.find(([code]) => code === value)?.[1] || value;
}
