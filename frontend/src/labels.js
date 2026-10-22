export const CATEGORY_LABEL = {
  medication_refill: 'Medication refill',
  scheduling: 'Scheduling',
  test_results: 'Test results',
  billing: 'Billing',
  symptoms: 'Symptoms',
  other: 'Other',
};

export const URGENCY_LABEL = { urgent: 'Urgent', soon: 'Soon', routine: 'Routine' };

export const PATIENT_STATUS_LABEL = {
  SENT: 'Sent',
  WITH_CARE_TEAM: 'With your care team',
  REVIEWED: 'Reviewed',
};

export function timeAgo(iso, now = Date.now()) {
  const s = Math.max(0, Math.round((now - Date.parse(iso)) / 1000));
  if (s < 60) return `${s}s ago`;
  const m = Math.round(s / 60);
  if (m < 60) return `${m}m ago`;
  const h = Math.round(m / 60);
  if (h < 48) return `${h}h ago`;
  return `${Math.round(h / 24)}d ago`;
}
