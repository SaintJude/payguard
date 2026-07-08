import http from 'k6/http';
import { check } from 'k6';

export const options = {
  scenarios: {
    ramp_load: {
      executor: 'ramping-arrival-rate',
      startRate: 5,
      timeUnit: '1s',
      preAllocatedVUs: 50,
      maxVUs: 200,
      stages: [
        { target: 5,  duration: '30s' },  // warm up
        { target: 40, duration: '2m'  },  // ramp — should cross 50% CPU
        { target: 40, duration: '3m'  },  // hold — watch HPA scale up
        { target: 0,  duration: '1m'  },  // ramp down — watch scale down
      ],
    },
  },
};

export default function () {
  const payload = JSON.stringify({
    amount: (Math.random() * 100).toFixed(2),
    idempotencyKey: `${__VU}-${__ITER}-${Date.now()}`,
  });
  const res = http.post('http://localhost:8080/payments', payload, {
    headers: { 'Content-Type': 'application/json' },
  });
  check(res, { 'status is 202': (r) => r.status === 202 });
}
