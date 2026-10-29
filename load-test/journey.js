import http from 'k6/http';
import { check, fail, sleep } from 'k6';
import exec from 'k6/execution';
import { Counter, Trend } from 'k6/metrics';

const base = __ENV.BASE_URL || 'http://localhost:18080';
const event = __ENV.EVENT_ID || '2';
const requests = Number(__ENV.REQUESTS || 1000);
const vus = Number(__ENV.VUS || 100);
const pollSeconds = Number(__ENV.POLL_TIMEOUT_SECONDS || 60);
const paid = new Counter('orders_paid');
const accepted = new Counter('accepted_reservations');
const errors = new Counter('journey_errors');
const integrity = new Counter('integrity_errors');
const completedLatency = new Trend('paid_journey_ms', true);
const auth = { headers: { 'X-Admin-Token': __ENV.ADMIN_TOKEN || 'local-demo-admin' } };
export const options = {
  scenarios: { journey: { executor: 'shared-iterations', vus, iterations: requests, maxDuration: __ENV.MAX_DURATION || '240s' } },
  summaryTrendStats: ['avg', 'min', 'med', 'max', 'p(95)', 'p(99)'],
  thresholds: {
    checks: ['rate==1'], iterations: [`count==${requests}`], http_req_failed: ['rate==0'],
    orders_paid: ['count==100'], accepted_reservations: ['count==100'],
    journey_errors: ['count==0'], integrity_errors: ['count==0'],
  },
};
function audit() {
  const r = http.get(`${base}/api/admin/events/${event}/audit`, auth);
  if (r.status !== 200) fail('Audit API must be available');
  const rows = r.json();
  if (!Array.isArray(rows) || rows.length !== 1) fail('Unknown audit event');
  return rows[0];
}
function readBody(r, status) {
  if (r.status !== status) return null;
  try { return r.json(); } catch (_) { return null; }
}
export function setup() {
  const before = audit();
  if (Number(before.total_stock) !== 100 || Number(before.remaining) !== 100 || Number(before.held) !== 0) fail('Use a fresh independent event with 100 unused tickets');
  const reset = http.post(`${base}/api/admin/events/${event}/reconcile`, null, auth);
  if (reset.status !== 200) fail('Unable to initialize inventory projection');
  return { run: `${Date.now()}-${Math.random().toString(16).slice(2)}` };
}
export default function(data) {
  paid.add(0); accepted.add(0); errors.add(0); integrity.add(0);
  const started = Date.now();
  const key = `journey-${data.run}-${exec.scenario.iterationInTest}`;
  const headers = { 'Content-Type': 'application/json', 'Idempotency-Key': key };
  const body = JSON.stringify({ userId: key });
  const response = http.post(`${base}/api/tickets/${event}/reserve`, body, {
    headers, timeout: '60s', responseCallback: http.expectedStatuses(202, 409),
  });
  if (response.status === 409) return; // Expected business rejection, not a completed purchase.
  const reservation = readBody(response, 202);
  if (!check(reservation, { 'durable admission returns an id': r => r && typeof r.id === 'string' })) { errors.add(1); return; }
  accepted.add(1);
  // Same payload/key must return the same durable identity, even if consumer advanced its state.
  const replay = readBody(http.post(`${base}/api/tickets/${event}/reserve`, body, { headers, timeout: '60s' }), 202);
  if (!check(replay, { 'reservation replay keeps id': r => r && r.id === reservation.id })) { errors.add(1); return; }
  const deadline = Date.now() + pollSeconds * 1000;
  let state = reservation;
  while (state && state.status === 'QUEUED' && Date.now() < deadline) {
    sleep(0.2);
    state = readBody(http.get(`${base}/api/reservations/${reservation.id}`, { timeout: '10s' }), 200);
  }
  if (!check(state, { 'Kafka consumer creates pending order': r => r && r.status === 'PENDING_PAYMENT' })) { errors.add(1); return; }
  const paymentUrl = `${base}/api/orders/${reservation.id}/payment`;
  const paymentOptions = { headers: { 'Content-Type': 'application/json' }, timeout: '30s' };
  const result = readBody(http.post(paymentUrl, JSON.stringify({ success: true }), paymentOptions), 200);
  if (!check(result, { 'payment commits PAID': r => r && r.status === 'PAID' && r.id === reservation.id })) { errors.add(1); return; }
  paid.add(1); completedLatency.add(Date.now() - started);
  // Repeated success and a later failure cannot revive/release a terminal paid order.
  for (const success of [true, false]) {
    const repeat = readBody(http.post(paymentUrl, JSON.stringify({ success }), paymentOptions), 200);
    if (!check(repeat, { 'paid order stays terminal': r => r && r.status === 'PAID' && r.id === reservation.id })) errors.add(1);
  }
}
export function teardown() {
  const after = audit();
  const valid = Number(after.paid) === 100 && Number(after.held) === 100 && Number(after.remaining) === 0
    && Number(after.discrepancy) === 0 && Number(after.duplicate_orders) === 0;
  integrity.add(valid ? 0 : 1);
  check(after, { '100 paid orders and inventory conserved': () => valid });
  console.log(`JOURNEY AUDIT ${JSON.stringify(after)}`);
}
export function handleSummary(data) {
  return { '/results/journey-summary.json': JSON.stringify(data, null, 2), stdout: JSON.stringify({
    paid_orders: data.metrics.orders_paid && data.metrics.orders_paid.values.count,
    completed_journey_latency: data.metrics.paid_journey_ms && data.metrics.paid_journey_ms.values,
    journey_errors: data.metrics.journey_errors && data.metrics.journey_errors.values.count,
    checks: data.root_group.checks,
  }, null, 2) + '\n' };
}
