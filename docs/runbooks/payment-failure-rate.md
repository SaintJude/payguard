# Runbook: `PaymentFailureRateHigh`

**Alert**: `PaymentFailureRateHigh`
**Summary**: Payment failure rate above 5% for 2 minutes
**Severity**: `warning`
**Defined in**: `infra/k8s/90-payguard-alerts-prometheusrule.yaml` (mirrored in
`infra/helm/payguard/templates/alerts-prometheusrule.yaml`)

## What this means

More than 5% of payments processed in the last minute ended in `FAILED`
status — meaning `worker`'s Spring Retry logic already exhausted its 3
attempts against `chaos-injector` (or hit a permanent `400`) before giving
up — and that elevated rate has held continuously for 2 minutes. This is a
business-meaningful signal, not a raw HTTP error rate: `payment-api` itself
almost never returns a 5xx under chaos, because `POST /payments` returns
`202` immediately and the actual processing (and any failure) happens
asynchronously in `worker`. If this alert is firing, real payments are
ending up `FAILED` after retries were already tried and didn't help.

The underlying query:

```promql
sum(rate(payments_processed_total{status="FAILED"}[1m]))
  /
sum(rate(payments_processed_total[1m]))
> 0.05
```

## Where to look first

Open the **PayGuard Overview** Grafana dashboard, **Traffic** row, **Payment
failure rate %** gauge panel — this panel plots the exact same expression the
alert evaluates, with a red threshold line at 5%, so it's the fastest way to
confirm what Prometheus is seeing and watch the trend in real time.

```bash
kubectl port-forward -n monitoring svc/kube-prometheus-stack-grafana 3000:80
# open http://localhost:3000, dashboard: "PayGuard Overview"
```

## Diagnostic steps

1. **Check whether chaos is intentionally active.** This is almost always
   the cause on this project — check `chaos-injector`'s live config:

   ```bash
   kubectl port-forward -n payguard svc/chaos-injector 8080:8080
   curl http://localhost:8080/chaos/config
   ```

   A `mode` other than `NONE` (especially `ERROR_5XX` or `ERROR_4XX` at a
   nontrivial `probabilityPct`, or `DROP`) fully explains an elevated
   failure rate.

2. **Correlate with the dashboard's Chaos row.** On the same "PayGuard
   Overview" dashboard, compare the **chaos-injector request rate by
   outcome** panel against the **mock-downstream request rate** panel. If
   `chaos-injector` is showing a burst of non-`200` outcomes while
   `mock-downstream`'s own traffic stays flat/healthy, that confirms
   `chaos-injector` is short-circuiting requests before they ever reach the
   real processor (the `ERROR_5XX`/`ERROR_4XX` fault modes never call
   `mock-downstream` at all) — a config/chaos issue, not a downstream
   service issue.

3. **Pull a concrete failing trace.** Find a `FAILED` payment's ID (from
   application logs, or `GET http://payment-api:8080/payments/{id}` for a
   specific ID you already suspect), then look it up in Tempo by trace ID
   or by searching on the `paymentId` attribute:

   ```bash
   kubectl port-forward -n monitoring svc/tempo 3200:3200
   # in Grafana (already port-forwarded above): Explore -> Tempo datasource
   # -> search by tag paymentId=<uuid>, or trace ID if you have one from logs
   ```

   The trace waterfall shows exactly which hop failed — `worker` ->
   `chaos-injector` -> `mock-downstream` — and how many retry attempts were
   made before the payment was marked `FAILED`.

## Remediation

- **If chaos was intentionally left on** (a demo or manual test that wasn't
  reset): turn it off.

  ```bash
  kubectl port-forward -n payguard svc/chaos-injector 8080:8080
  curl -X PUT http://localhost:8080/chaos/config \
    -H 'Content-Type: application/json' \
    -d '{"mode":"NONE","latencyMs":0,"probabilityPct":0}'
  ```

- **If chaos mode is already `NONE` and failures are still elevated**, this
  is a genuine problem, not an induced one. Since `chaos-injector` forwards
  correctly under `NONE`, an elevated failure rate here means the real
  processor is unhealthy — check `mock-downstream`'s pods:

  ```bash
  kubectl get pods -n payguard -l app=mock-downstream
  kubectl logs -n payguard -l app=mock-downstream --tail=200
  kubectl get --raw /api/v1/namespaces/payguard/pods/<mock-downstream-pod>/proxy/actuator/health
  ```

  Also check `chaos-injector`'s own pod logs/health in case the proxy hop
  itself (not the chaos logic) is the one misbehaving — e.g. connection
  errors reaching `mock-downstream`, resource pressure, or a crash loop.

## Resolution

No manual close-out is required. The alert is self-resolving: once the
failure-rate ratio drops back under 5% and stays there for the same 2-minute
evaluation window (`for: 2m`), Prometheus transitions it back out of
`firing` on its own and Alertmanager clears it. Confirm via:

```bash
kubectl port-forward -n monitoring svc/alertmanager-operated 9093:9093
curl http://localhost:9093/api/v2/alerts
```

If you deliberately triggered this alert for testing (chaos toggle), also
double-check chaos mode is back to `NONE` before walking away — a "resolved"
alert with chaos still silently active just means the failure rate happened
to dip below 5% momentarily, not that the underlying fault injection stopped.
