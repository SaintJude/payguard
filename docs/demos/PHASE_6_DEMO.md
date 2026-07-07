# Phase 6 Demo — GitOps CD with Argo CD

## What's new this phase

The `payguard` kind cluster is no longer updated by a human running
`kubectl apply`/`helm upgrade`. Argo CD now watches this repo's `main`
branch and keeps the live cluster in sync automatically — push a change,
Argo CD applies it; edit the live cluster by hand, Argo CD reverts it back
to whatever Git says.

## See it yourself

### Prerequisite

`colima` needs at least 4 CPUs / 6GB memory for Argo CD's ~6 components to
run alongside the four app workloads without resource starvation (Argo CD
alone is heavier than the whole app). If you set colima up before this
phase with the default 2 CPU / 2GB profile:
```bash
colima stop
colima start --cpu 4 --memory 6
```

### Look at the Argo CD UI

```bash
kubectl port-forward svc/argocd-server -n argocd 8080:443
```
Open **https://localhost:8080** (accept the self-signed-cert warning).
Username `admin`, password:
```bash
kubectl -n argocd get secret argocd-initial-admin-secret -o jsonpath="{.data.password}" | base64 -d; echo
```
You'll see the `payguard` Application, its sync/health status, and a live
graph of every resource it manages.

### Or check from the command line

```bash
kubectl get application payguard -n argocd
# NAME       SYNC STATUS   HEALTH STATUS
# payguard   Synced        Healthy
```

### The actual GitOps loop: push a change, watch it apply itself

1. Edit a tracked manifest, e.g. `infra/k8s/40-worker-deployment.yaml`,
   change `replicas: 2` to `replicas: 3`.
2. Commit, push to a branch, open a PR, get it merged to `main` (this repo
   requires PRs — no direct push, even for the repo owner, per Phase 2's
   branch protection).
3. Watch the cluster, without running anything else:
   ```bash
   kubectl get pods -n payguard -l app=worker -w
   ```
   A third `worker` pod appears on its own, usually within seconds to a
   couple minutes of the merge — nobody ran `kubectl apply`.

### The other half: drift correction

```bash
kubectl scale deployment/worker -n payguard --replicas=5
kubectl get pods -n payguard -l app=worker -w
```
Within several seconds, Argo CD scales it back down to whatever `main`
currently declares (3, if you did the step above). This is
`syncPolicy.automated.selfHeal` — editing the live cluster directly no
longer sticks. Git is genuinely the source of truth now, not just in
spirit.

## Stop

```bash
kubectl delete -f infra/argocd/application.yaml   # stop Argo CD managing payguard (leaves the workloads running)
kubectl delete namespace argocd                    # remove Argo CD itself
kind delete cluster --name payguard                # remove everything, including the app
```
