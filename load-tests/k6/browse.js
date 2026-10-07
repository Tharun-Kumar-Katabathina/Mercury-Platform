// Catalogue reads only: the read-heavy path that caching and indexing are meant to speed up.
import { sleep } from 'k6';
import { stages, seed, browse, summary } from './common.js';

const THINK = parseFloat(__ENV.THINK_SECONDS || '1');

export const options = {
  scenarios: {
    readers: { executor: 'ramping-vus', startVUs: 0, stages: stages(), gracefulRampDown: '5s' },
  },
  thresholds: {
    http_req_failed: [`rate<${__ENV.MAX_ERROR_RATE || '0.01'}`],
    'http_req_duration{name:GET product}': [`p(95)<${__ENV.P95_BROWSE_MS || '500'}`],
  },
  summaryTrendStats: ['avg', 'med', 'p(90)', 'p(95)', 'p(99)', 'max'],
  discardResponseBodies: true,
};

export function setup() { return seed(); }
export default function (data) { browse(data.ids); if (THINK > 0) sleep(THINK * (0.5 + Math.random())); }
export const handleSummary = summary(__ENV.RESULT_NAME || 'browse');
