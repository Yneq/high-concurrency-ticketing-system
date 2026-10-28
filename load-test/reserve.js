import http from 'k6/http';
import { check, fail, sleep } from 'k6';
import { Counter } from 'k6/metrics';
const base = __ENV.BASE_URL || 'http://localhost:8080';
const eventId = __ENV.EVENT_ID || '1';
const url = `${base}/api/tickets/${eventId}`;
const successes = new Counter('reservation_successes');
const soldOut = new Counter('sold_out');
const unexpected = new Counter('unexpected_responses');
const expectedStatus = http.expectedStatuses(200, 409);
export const options = {
  scenarios: { reserve: { executor: 'per-vu-iterations', vus: 1000, iterations: 1, maxDuration: '120s' } },
  thresholds: {
    checks: ['rate==1'],
    http_req_failed: ['rate==0'],
    unexpected_responses: ['count==0'],
    // This is an experiment assertion: PASS means the intentional defect was reproduced.
    reservation_successes: ['count>100'],
    iterations: ['count==1000'],
  },
};
export function setup() {
  const response = http.get(url);
  if (response.status !== 200) fail('API not ready: start app and wait for GET to return 200');
  const initial = response.json();
  if (initial.totalStock !== 100 || initial.remaining !== 100) fail('Reset event to 100/100 before the test');
  return { initial, startAt: Date.now() + 10000 };
}
export default function (data) {
  // A common start time reduces VU startup skew; it is not a server-side barrier.
  sleep(Math.max(0, (data.startAt - Date.now()) / 1000));
  const response = http.post(`${url}/reserve`, null, { timeout: '110s', responseCallback: expectedStatus });
  const ok = response.status === 200 && response.json('status') === 'RESERVED';
  successes.add(ok ? 1 : 0);
  soldOut.add(response.status === 409 ? 1 : 0);
  unexpected.add(!ok && response.status !== 409 ? 1 : 0);
  check(response, { 'reserved or sold out': () => ok || response.status === 409 });
}
export function teardown(data) {
  const response = http.get(url);
  check(response, { 'final inventory readable': r => r.status === 200 });
  if (response.status === 200) {
    const final = response.json();
    console.log(`Initial=${data.initial.remaining}; final=${final.remaining}; DB decrements=${data.initial.remaining - final.remaining}. Compare with reservation_successes.`);
  }
}
