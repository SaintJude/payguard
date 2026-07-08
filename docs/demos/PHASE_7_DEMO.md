# Phase 7 Demo — Chaos, Observability, and Autoscaling

## What's new this phase

Four things, building on everything so far:

1. **Real chaos injection** — a dedicated `chaos-injector` service sits
   between `worker` and the simulated payment processor, and you can flip
   it into five different fault modes live, without redeploying anything.
2. **Full observability** — distributed traces (Tempo), metrics dashboards
   (Grafana), and one real alert (Alertmanager), covering every service.
3. **Autoscaling** — `payment-api` and `worker` both scale up automatically
   under load and back down once it stops, via real `HorizontalPodAutoscaler`s
   backed by real CPU metrics.
4. **A load-generation script** so you can actually trigger and *watch* the
   scaling happen, not just take it on faith.

## Before you start

This phase adds a lot of new pods on top of everything else — the full
stack now runs on a **tight but real** resource budget at colima's current
4 CPU / 6GiB sizing (deliberately not resized this phase — see
`docs/phase-notes/PHASE_7_NOTES.md`). Two things worth knowing going in:

- Bringing everything up (Argo CD + `payguard` app + `kube-prometheus-stack` +
  Tempo + `metrics-server`) takes a few minutes and pulls a lot of images
  the first time.
- **Running the full `k6` load test to its peak will, honestly, briefly
  destabilize the cluster.** Both HPAs hitting their max replicas
  simultaneously pushes the node hard enough that the Kubernetes API server
  itself can become unresponsive for a bit, causing a wave of pods to fail
  their liveness probe and restart. This is expected, not a bug — everything
  self-heals within about a minute. If you'd rather not see that, use a
  gentler load profile (see the "Optional: a calmer load test" section
  below).

## See it yourself

### 1. Chaos injection

```bash
kubectl port-forward -n payguard svc/chaos-injector 8080:8080
```
```bash
# see current mode
curl http://localhost:8080/chaos/config

# force every downstream call to fail with a 503
curl -X PUT http://localhost:8080/chaos/config \
  -H 'Content-Type: application/json' \
  -d '{"mode":"ERROR_5XX","latencyMs":0,"probabilityPct":100}'
```
Now submit a payment (`kubectl port-forward -n payguard svc/payment-api
8080:8080` in another terminal, then the usual `POST /payments`) — it
resolves to `FAILED` after worker's three retries (~0.7s). Try
`ERROR_4XX` instead — same result, but near-instant (~0.25s), since
that's a permanent failure Spring Retry doesn't even attempt to retry.

Set it back when you're done:
```bash
curl -X PUT http://localhost:8080/chaos/config \
  -H 'Content-Type: application/json' \
  -d '{"mode":"NONE","latencyMs":0,"probabilityPct":0}'
```

### 2. Observability — Grafana, Tempo, Alertmanager

```bash
kubectl port-forward -n monitoring svc/kube-prometheus-stack-grafana 3000:80
kubectl get secret -n monitoring kube-prometheus-stack-grafana \
  -o jsonpath="{.data.admin-password}" | base64 -d; echo
```
Open **http://localhost:3000**, log in (`admin` / the password above), open
the **"PayGuard Overview"** dashboard. While chaos is active (step 1 above),
watch the **Chaos** row react in real time, and the **Traffic** row's
"Payment failure rate %" gauge cross its 5% threshold line.

Find a trace for a specific payment: Grafana → **Explore** → Tempo
datasource → search by tag `paymentId=<uuid>` — the waterfall shows every
hop (`worker` → `chaos-injector` → `mock-downstream`) and exactly where
time went.

### 3. The alert firing for real

With chaos still forcing failures (step 1), wait about 2 minutes, then:
```bash
kubectl port-forward -n monitoring svc/alertmanager-operated 9093:9093
curl http://localhost:9093/api/v2/alerts
```
You'll see `PaymentFailureRateHigh` active, with a link to
[`docs/runbooks/payment-failure-rate.md`](../runbooks/payment-failure-rate.md).
Turn chaos back to `NONE` and it clears on its own within a couple minutes.

### 4. Autoscaling — the full load test

```bash
kubectl port-forward -n payguard svc/payment-api 8080:8080
```
In another terminal:
```bash
k6 run load/payment-load.js
```
This takes about 6.5 minutes (30s warmup, 2min ramp, 3min hold, 1min
ramp-down). Watch it happen:
```bash
kubectl get hpa -n payguard -w
```
You'll see `payment-api` climb from 2 → 4 replicas and `worker` climb from
2 → 3, both holding at their ceiling through the load, then settle back to
2 each about a minute after the load stops (`stabilizationWindowSeconds:
60` avoids flapping). The same replica counts are visible on the
dashboard's **Scaling** row, live, alongside the CPU utilization that
triggered it — not just `kubectl get pods`.

**Remember the "before you start" note**: at peak (both HPAs maxed at the
same time), expect to see some pods restart from a liveness-probe timeout
as the node comes under real pressure. Give it a minute; it recovers on
its own.

#### Optional: a calmer load test

To see scaling happen without the peak-load instability, edit
`load/payment-load.js` and lower the `target: 80` stages to something like
`target: 40` — still comfortably crosses both HPAs' CPU thresholds (50%/60%)
without pushing the node to its limit.

## Stop

```bash
k6 run --duration 0s ...  # or just Ctrl-C k6 if it's still running
kubectl scale deployment/payment-api -n payguard --replicas=2  # optional, HPA will do this anyway
```
Everything else keeps running for the next phase.
