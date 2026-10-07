// The realistic mix: shoppers browse the catalogue, a few of them buy. Each virtual user is one connected
// shopper with think time between actions, so N virtual users = N concurrent users (not N requests/second).
//
//   STEPS=100,500,1000 HOLD=60s k6 run load-tests/k6/mixed.js
import { sleep } from 'k6';
import { stages, seed, browse, order, summary } from './common.js';

const BUY_RATIO = parseFloat(__ENV.BUY_RATIO || '0.05');
const THINK = parseFloat(__ENV.THINK_SECONDS || '3');

export const options = {
  scenarios: {
    shoppers: { executor: 'ramping-vus', startVUs: 0, stages: stages(), gracefulRampDown: '5s' },
  },
  thresholds: {
    http_req_failed: [`rate<${__ENV.MAX_ERROR_RATE || '0.01'}`],
    'http_req_duration{name:GET product}': [`p(95)<${__ENV.P95_BROWSE_MS || '500'}`],
    'http_req_duration{name:POST order}': [`p(95)<${__ENV.P95_ORDER_MS || '2000'}`],
  },
  summaryTrendStats: ['avg', 'med', 'p(90)', 'p(95)', 'p(99)', 'max'],
  discardResponseBodies: true,
};

export function setup() { return seed(); }

export default function (data) {
  if (Math.random() < BUY_RATIO) order(data.ids); else browse(data.ids);
  sleep(THINK * (0.5 + Math.random()));          // 1.5 s .. 4.5 s between actions
}

export const handleSummary = summary(__ENV.RESULT_NAME || 'mixed');
