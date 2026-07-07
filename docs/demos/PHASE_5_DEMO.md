# Phase 5 Demo — Local Kubernetes

## What's new this phase

The same system from Phase 3 — `payment-api`, `worker`, Postgres, Redis —
now also runs on a local Kubernetes cluster (`kind`), two ways: as plain
manifests (`infra/k8s/`) or as a Helm chart (`infra/helm/payguard/`). Unlike
Docker Compose, Kubernetes actively watches and repairs the system: kill a
pod and it comes back on its own, no `docker compose up` needed.

## See it yourself

### Option A — raw manifests

```bash
kind create cluster --name payguard
docker compose build payment-api worker   # if images aren't already built
kind load docker-image payguard-payment-api:latest --name payguard
kind load docker-image payguard-worker:latest --name payguard
kubectl apply -k infra/k8s/
kubectl get pods -n payguard -w   # watch until all four show 1/1 Running
```

### Option B — Helm chart (does the same thing, packaged differently)

```bash
kind create cluster --name payguard   # skip if already created
docker compose build payment-api worker
kind load docker-image payguard-payment-api:latest --name payguard
kind load docker-image payguard-worker:latest --name payguard
helm install payguard infra/helm/payguard -n payguard --create-namespace
```

### Talk to it

```bash
kubectl port-forward -n payguard svc/payment-api 8080:8080
```

Then, in another terminal, submit a payment the same way as every prior
phase's demo:
```bash
curl -X POST http://localhost:8080/payments \
  -H 'Content-Type: application/json' \
  -d '{"amount": 10.00, "idempotencyKey": "demo-1"}'
```
Poll `GET http://localhost:8080/payments/<id>` until it reaches
`COMPLETED`.

### The part Compose couldn't show you: self-healing

```bash
kubectl get pods -n payguard -l app=worker
kubectl delete pod -n payguard -l app=worker
kubectl get pods -n payguard -l app=worker
```
A brand-new pod (different name) appears within a second or two — nobody
ran `kubectl apply` again. Compose's `restart: no` (Phase 3, deliberate)
never gave you this; a Kubernetes Deployment does it by default.

Try the same with `postgres` instead of `worker`, then check your payment
is still there — the data survives because it lives on a
`PersistentVolumeClaim`, not the pod's own filesystem.

## Stop

```bash
# if you used raw manifests:
kubectl delete -k infra/k8s/

# if you used Helm:
helm uninstall payguard -n payguard
# note: this deletes the Postgres PVC too (the chart owns its own
# namespace, so uninstall is a total teardown, not a pause) — unlike
# `docker compose down`, there's no data left behind to resume from

# either way, to remove the cluster entirely:
kind delete cluster --name payguard
```
