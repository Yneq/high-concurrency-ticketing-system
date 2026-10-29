import http from 'k6/http';
import { check, fail, sleep } from 'k6';
import exec from 'k6/execution';
import { Counter, Trend } from 'k6/metrics';
const base = __ENV.BASE_URL || 'http://localhost:18080';
const event = __ENV.EVENT_ID || '2';
const total = Number(__ENV.REQUESTS || 1000);
const vus = Number(__ENV.VUS || 100);
const stage = __ENV.STAGE || 'final';
const prefix = stage === 'atomic' ? '/api/v2' : stage === 'redis' ? '/api/v3' : '/api';
const accepted = new Counter('accepted_reservations');
const rejected = new Counter('sold_out');
const errors = new Counter('unexpected_responses');
const oversell = new Counter('oversold_units');
const duplicates = new Counter('duplicate_orders');
const discrepancies = new Counter('inventory_discrepancy');
const latency = new Trend('admission_latency_ms', true);
export const options = {
  scenarios: { reservations: { executor: 'shared-iterations', vus, iterations: total, maxDuration: __ENV.MAX_DURATION || '240s' } },
  summaryTrendStats: ['avg', 'min', 'med', 'max', 'p(95)', 'p(99)'],
  thresholds: {
    checks: ['rate==1'], iterations: [`count==${total}`],
    unexpected_responses: ['count==0'], oversold_units: ['count==0'],
    duplicate_orders: ['count==0'], inventory_discrepancy: ['count==0'],
    http_req_failed: ['rate==0'],
    accepted_reservations: ['count==100'],
  },
};
const auth = { headers: { 'X-Admin-Token': __ENV.ADMIN_TOKEN || 'local-demo-admin' } };
function audit() {
  const r = http.get(`${base}/api/admin/events/${event}/audit`, auth);
  if (r.status !== 200 || !Array.isArray(r.json()) || r.json().length !== 1) fail('Audit unavailable');
  return r.json()[0];
}
export function setup() {
  const before = audit();
  if (Number(before.total_stock)!==100 || Number(before.remaining)!==100 || Number(before.held)!==0) fail('Use a fresh isolated final event with 100 stock and no active reservations');
  // Clear stale-low admission projection by projecting the authoritative initial state.
  const repaired = http.post(`${base}/api/admin/events/${event}/reconcile`, null, auth);
  if (repaired.status !== 200) fail('Unable to initialize projection');
  return { run: `${Date.now()}-${Math.random().toString(16).slice(2)}` };
}
export default function(data) {
  const key = `load-${data.run}-${exec.scenario.iterationInTest}`;
  const r = http.post(`${base}${prefix}/tickets/${event}/reserve`, JSON.stringify({ userId: key }), {
    headers: { 'Content-Type': 'application/json', 'Idempotency-Key': key },
    responseCallback: http.expectedStatuses(200, 202, 409), timeout: '60s',
  });
  let ok = false;
  if (r.status === 200 || r.status === 202) {
    try { const body = r.json(); ok = typeof body.id === 'string' && ['QUEUED', 'PENDING_PAYMENT', 'PAID', 'EXPIRED', 'FAILED'].includes(body.status); }
    catch (_) { ok = false; }
  }
  accepted.add(ok ? 1 : 0); rejected.add(r.status === 409 ? 1 : 0);
  errors.add(!ok && r.status !== 409 ? 1 : 0);
  latency.add(r.timings.duration);
  check(r, { 'accepted or sold out': () => ok || r.status === 409 });
}
export function teardown() {
  const after = audit();
  oversell.add(Math.max(0, Number(after.held) - Number(after.total_stock)));
  duplicates.add(Number(after.duplicate_orders));
  discrepancies.add(Math.abs(Number(after.discrepancy)));
  check(after, { 'inventory conserved': a => Number(a.remaining) + Number(a.held) === Number(a.total_stock) });
  console.log(`FINAL AUDIT ${JSON.stringify(after)}`);
  // This admission-only benchmark does not count orders becoming PAID as completed purchases.
}
export function handleSummary(data) {
  return { '/results/summary.json': JSON.stringify(data, null, 2), stdout: JSON.stringify({
    requests: data.metrics.iterations && data.metrics.iterations.values.count,
    admissions: data.metrics.accepted_reservations && data.metrics.accepted_reservations.values.count,
    throughput: data.metrics.http_reqs && data.metrics.http_reqs.values.rate,
    latency: data.metrics.admission_latency_ms && data.metrics.admission_latency_ms.values,
    unexpected_error_rate: data.metrics.iterations && data.metrics.iterations.values.count ? data.metrics.unexpected_responses.values.count / data.metrics.iterations.values.count : null,
    checks: data.root_group.checks,
  }, null, 2) + '\n' };
}
