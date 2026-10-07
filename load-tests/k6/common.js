// Shared helpers for the Mercury load tests (k6). Everything is configured by environment variables so the same
// script runs a 100-user smoke test in CI and a 10,000-user stress test locally.
import http from 'k6/http';
import { check } from 'k6';

export const BASE = __ENV.BASE_URL || 'http://localhost:8083';         // order entry point (gateway or service)
export const PRODUCT = __ENV.PRODUCT_URL || 'http://localhost:8081';   // product catalogue
export const INVENTORY = __ENV.INVENTORY_URL || 'http://localhost:8082'; // used only by setup to seed stock
export const JSON_HEADERS = { 'Content-Type': 'application/json' };
export const STOCK = parseInt(__ENV.SEED_STOCK || '1000000');
export const PRODUCTS = parseInt(__ENV.SEED_PRODUCTS || '50');
export const AUTH = __ENV.AUTH_TOKEN ? { Authorization: `Bearer ${__ENV.AUTH_TOKEN}` } : {};

// Stage plan: ramp to each target number of concurrent virtual users and hold. "100,500,1000" -> three steps.
export function stages() {
  const targets = (__ENV.STEPS || '100').split(',').map((s) => parseInt(s.trim()));
  const ramp = __ENV.RAMP || '20s';
  const hold = __ENV.HOLD || '40s';
  const out = [];
  for (const t of targets) {
    out.push({ duration: ramp, target: t });
    out.push({ duration: hold, target: t });
  }
  out.push({ duration: '10s', target: 0 });
  return out;
}

// Creates PRODUCTS products with plenty of stock so the test measures speed, not stock-outs.
export function seed() {
  const ids = [];
  const run = Date.now().toString(36);
  for (let i = 0; i < PRODUCTS; i++) {
    const p = http.post(`${PRODUCT}/api/v1/products`,
      JSON.stringify({ name: `Load item ${i}`, sku: `LOAD-${run}-${i}`, price: 10 + i, quantity: STOCK }),
      { headers: { ...JSON_HEADERS, ...AUTH } });
    if (p.status !== 201) throw new Error(`seed product failed: ${p.status} ${p.body}`);
    const id = p.json('id');
    const inv = http.post(`${INVENTORY}/api/v1/inventory`,
      JSON.stringify({ productId: id, availableQuantity: STOCK }), { headers: { ...JSON_HEADERS, ...AUTH } });
    if (inv.status !== 201) throw new Error(`seed inventory failed: ${inv.status} ${inv.body}`);
    ids.push(id);
  }
  return { ids };
}

export function pick(ids) {
  return ids[Math.floor(Math.random() * ids.length)];
}

export function browse(ids) {
  const r = http.get(`${PRODUCT}/api/v1/products/${pick(ids)}`, { headers: AUTH, tags: { name: 'GET product' } });
  check(r, { 'product 200': (x) => x.status === 200 });
  return r;
}

export function order(ids) {
  const key = `k6-${__VU}-${__ITER}-${Date.now()}-${Math.random()}`;
  const r = http.post(`${BASE}/api/v1/orders`,
    JSON.stringify({ items: [{ productId: pick(ids), quantity: 1 }] }),
    { headers: { ...JSON_HEADERS, ...AUTH, 'Idempotency-Key': key }, tags: { name: 'POST order' } });
  check(r, { 'order accepted (201/202)': (x) => x.status === 201 || x.status === 202 });
  return r;
}

export function summary(name) {
  return (data) => ({
    [`/results/${name}.json`]: JSON.stringify(data, null, 1),
    stdout: textSummary(data),
  });
}

function textSummary(data) {
  const m = data.metrics;
  const t = (k, s) => (m[k] && m[k].values[s] !== undefined ? m[k].values[s].toFixed(1) : 'n/a');
  return `
  requests:  ${m.http_reqs.values.count}  (${m.http_reqs.values.rate.toFixed(1)}/s)
  failed:    ${(m.http_req_failed.values.rate * 100).toFixed(2)}%
  latency:   p50 ${t('http_req_duration', 'med')} ms   p95 ${t('http_req_duration', 'p(95)')} ms   p99 ${t('http_req_duration', 'p(99)')} ms
  vus max:   ${m.vus_max.values.max}
`;
}
