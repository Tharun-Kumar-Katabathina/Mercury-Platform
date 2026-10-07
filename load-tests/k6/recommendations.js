// Recommendation latency under concurrent shoppers. The setup builds real data the way production would: it creates
// products in clusters, places confirmed orders that mostly stay inside a cluster (so "bought with the same companions"
// means something), waits for the order events to be learned and the vectors to reach the index, and only then measures.
//
//   STEPS=100,500,1000 k6 run load-tests/k6/recommendations.js
import http from 'k6/http';
import { sleep, check } from 'k6';
import { BASE, PRODUCT, JSON_HEADERS, AUTH, stages, seed, pick, summary } from './common.js';

const RECS = __ENV.RECOMMENDATION_URL || BASE;
const CLUSTERS = 4;
const ORDERS = parseInt(__ENV.SEED_ORDERS || '240');
const SETTLE_SECONDS = parseInt(__ENV.SETTLE_SECONDS || '25');

export const options = {
  scenarios: { shoppers: { executor: 'ramping-vus', startVUs: 0, stages: stages(), gracefulRampDown: '5s' } },
  thresholds: {
    http_req_failed: [`rate<${__ENV.MAX_ERROR_RATE || '0.01'}`],
    'http_req_duration{name:similar}': [`p(95)<${__ENV.P95_SIMILAR_MS || '300'}`],
    'http_req_duration{name:me}': [`p(95)<${__ENV.P95_ME_MS || '500'}`],
  },
  summaryTrendStats: ['avg', 'med', 'p(90)', 'p(95)', 'p(99)', 'max'],
  discardResponseBodies: true,
};

export function setup() {
  const { ids } = seed();
  const perCluster = Math.floor(ids.length / CLUSTERS);
  const cluster = (c) => ids.slice(c * perCluster, (c + 1) * perCluster);
  for (let i = 0; i < ORDERS; i++) {
    const members = cluster(i % CLUSTERS);
    const items = [];
    const size = 2 + (i % 3);
    while (items.length < size) {
      const productId = pick(members);
      if (!items.some((x) => x.productId === productId)) items.push({ productId, quantity: 1 });
    }
    http.post(`${BASE}/api/v1/orders`, JSON.stringify({ items }),
      { headers: { ...JSON_HEADERS, ...AUTH, 'Idempotency-Key': `rec-seed-${Date.now()}-${i}` } });
  }
  sleep(SETTLE_SECONDS);          // events consumed, vectors pushed to the index
  return { ids };
}

export default function (data) {
  const roll = Math.random();
  let r;
  if (roll < 0.5) {
    r = http.get(`${RECS}/api/v1/recommendations/products/${pick(data.ids)}/similar?limit=10`, { headers: AUTH, tags: { name: 'similar' } });
  } else if (roll < 0.8) {
    r = http.get(`${RECS}/api/v1/recommendations/me?limit=10`, { headers: AUTH, tags: { name: 'me' } });
  } else {
    r = http.get(`${RECS}/api/v1/recommendations/popular?limit=10`, { headers: AUTH, tags: { name: 'popular' } });
  }
  check(r, { 'recommendations 200': (x) => x.status === 200 });
  sleep(1 + Math.random() * 2);
}

export const handleSummary = summary(__ENV.RESULT_NAME || 'recommendations');
