// Order creation only, as fast as the system allows with a fixed number of concurrent buyers (no think time).
// This is the write path: validation, Product read, Inventory reservation, outbox, saga.
import { stages, seed, order, summary } from './common.js';

export const options = {
  scenarios: {
    buyers: { executor: 'ramping-vus', startVUs: 0, stages: stages(), gracefulRampDown: '5s' },
  },
  thresholds: {
    http_req_failed: [`rate<${__ENV.MAX_ERROR_RATE || '0.01'}`],
    'http_req_duration{name:POST order}': [`p(95)<${__ENV.P95_ORDER_MS || '2000'}`],
  },
  summaryTrendStats: ['avg', 'med', 'p(90)', 'p(95)', 'p(99)', 'max'],
  discardResponseBodies: true,
};

export function setup() { return seed(); }
export default function (data) { order(data.ids); }
export const handleSummary = summary(__ENV.RESULT_NAME || 'orders');
