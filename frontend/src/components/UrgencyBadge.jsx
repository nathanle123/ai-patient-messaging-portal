import { URGENCY_LABEL } from '../labels.js';

// Urgency is a demonstration-only review flag; the title attribute says so on every badge.
export default function UrgencyBadge({ label, failed }) {
  if (failed) return <span className="badge badge-failed" title="PHI redaction failed; AI processing skipped">Redaction failed</span>;
  if (!label) return <span className="badge badge-pending" title="Waiting for the urgency service">Pending</span>;
  return (
    <span className={`badge badge-${label}`} title="Demonstration-only review flag, not validated clinical triage">
      {URGENCY_LABEL[label]}
    </span>
  );
}
