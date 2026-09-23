# Demo spec: showing in-flight flow migration

How to drive this POC end to end so an audience can see what "migrating an in-flight
process" actually means: 13 legacy records with no explicit process state, inferred into
positions in a running BPMN process, held, gated, and only then released.

Design: `docs/designs/adabas-camunda8-inflight-migration-poc.md`. This file is the runbook;
the design is the argument.

## What the demo claims

Every record in the frozen export lands in exactly one of three places — a **held token at a
BPMN element**, a **skip with a reason code**, or a **quarantine with a reason code** — and the
three add up to the manifest count. Nothing reaches a human until a gate says the arithmetic
balances.

It does **not** claim anything about real Adabas data. The file model in `fixtures/` is
invented and the expected outcomes are hand-written (`fixtures/README.md`).

## Prerequisites

- Docker Desktop
- Java 25, Maven
- Ports 18080 / 36500 / 19600 / 15432 free. They are deliberately non-default so the
  sibling stack can run at the same time on 8080 / 26500 / 9600 / 5432.

## 0. Bring up the stack and deploy the models

```sh
docker compose up -d                 # postgres + Camunda 8.9.12, healthy in ~15-25s
mvn -f migration-app/pom.xml package # 45 tests; 2 of them start their own throwaway engine

curl -s -F "resources=@processes/learner-permit-migration.bpmn" \
        -F "resources=@processes/migration-classification.dmn" \
        http://localhost:18080/v2/deployments
```

Deployment is not scripted yet — it is a manual step, and it is the first thing to verify.
Operate is at <http://localhost:18080/operate>.

Shorthand used below:

```sh
APP="java -jar migration-app/target/migration-app-0.1.0-SNAPSHOT.jar"
```

## Windows / PowerShell

Every step below runs on Windows PowerShell 5.1, with four substitutions. Run them all from
the repository root: the jar path and every `fixtures/` argument is relative to it.

**The shorthand.** PowerShell does not split a string into a command line the way sh does, so
both `$APP classify` and `& $APP classify` fail — the latter looks for an executable named
`java -jar migration-app/…jar`. Declare a function instead; it lasts for the life of the window:

```powershell
function app { java -jar migration-app/target/migration-app-0.1.0-SNAPSHOT.jar @args }
```

`@args` splats the arguments through, so each step reads the same minus the `$`:
`app classify fixtures/adabas-export.csv …`.

**`curl` is an alias for `Invoke-WebRequest`** in 5.1 and does not understand `-s -F`. Spell the
binary out as `curl.exe` for the deploy in step 0.

**The search body in step 4** additionally loses its inner double quotes before `curl.exe` sees
them — 5.1 does not escape quotes when passing arguments to native executables, so the filter
arrives as `{filter:{state:CREATED}}` and is silently an empty filter, which is the *historical*
search. Use PowerShell's own client rather than fighting the quoting:

```powershell
Invoke-RestMethod -Method Post http://localhost:18080/v2/user-tasks/search `
  -ContentType application/json -Body '{"filter":{"state":"CREATED"}}' | ConvertTo-Json -Depth 8
```

**`&&` is not valid in 5.1.** The reset becomes `docker compose down -v; docker compose up -d`,
which runs the second command even when the first one fails — read the output, do not assume it.

`mvn` and `docker compose` need no changes.

## One command, when you are not narrating

Steps 1-4 are the demo: four invocations, paused between for the audience. When nobody is
watching — a rehearsal, a reset-and-reload, a smoke test after a rule change — `migrate` runs
the same phases in one JVM:

```sh
$APP migrate fixtures/adabas-export.csv fixtures/manifest.csv fixtures/expected-outcomes.csv --dry-run
$APP migrate fixtures/adabas-export.csv fixtures/manifest.csv fixtures/expected-outcomes.csv
$APP migrate fixtures/adabas-export.csv fixtures/manifest.csv fixtures/expected-outcomes.csv --release
```

- **`--dry-run`** stops after classification, having created nothing: step 1 exactly.
- **No flag** runs classify → load → gate and stops at the verdict — the point where a human
  decides today. Steps 1-3 in one go.
- **`--release`** adds step 4, and only on a `GATE: PASS`. A FAIL still prints `RELEASE REFUSED`
  and exits 1; the chain gets no route around the gate.
- A **failed expectation check halts before the load**. Run by hand you would simply not type
  `load` next; chained, that has to be a decision in code, or it would create instances from a
  population it has just reported as wrong.
- **`--abort` is not accepted here** — it undoes a step rather than being one. It stays on
  `reconcile`.

The output blocks are the same ones the phase commands print, from the same code, so a chained
run and a hand-driven run are comparable line by line. It classifies **once** and hands that one
report to the loader, where `classify` then `load` evaluates the DMN twice and writes two sets of
decision-evaluation records for the same population.

**It resumes rather than restarting.** Run it again and the loader's idempotence applies; once
creation is closed the load phase is skipped entirely (`LOAD - already done`) and the chain goes
straight to the gate. So `migrate … --release` after a run that already loaded does what you
meant, and a cutover that stopped at the gate is finished by rerunning the same line.

Deployment is **not** part of it: `migrate` assumes the BPMN and DMN are already deployed (step 0,
or whichever script does that in your setup).

## 1. Dry run — classification with nothing created

```sh
$APP classify fixtures/adabas-export.csv fixtures/manifest.csv fixtures/expected-outcomes.csv
```

Reads the freeze parameters from the manifest (`freezeDate=2026-09-15`, `cutoffMonths=6`,
so the cutoff boundary is `2026-03-15` — never the wall clock), discovers the `VT_<n>_*` and
`RESTR_<n>` occurrence columns from the header, pre-validates, then evaluates the DMN.

Expect **3 MIGRATE / 4 SKIP / 6 QUARANTINE**, and `EXPECTATIONS: 13/13 match`, exit 0. The
fourth argument turns the report into a gate; drop it and it is just a report.

**Show the audience:** the *unmatched combinations* block. A single unmatched total says how big
the problem is; the combination breakdown (`C=completed I=issued E=expired W=withinCutoff
F=feePaid`, uppercase true) says what it *is* — the only form a business analyst can act on.

**Show in Operate:** zero process instances, zero user tasks. Decision evaluations are there,
and are the dry run's audit trail.

## 2. Load — create held instances

```sh
$APP load fixtures/adabas-export.csv fixtures/manifest.csv
```

3 instances created, each halted at the `apply-for-permit` start event by its `migrator`
execution listener — Camunda's own documented runtime-migration mechanism — before any sequence
flow is traversed. The ledger row is written **before** the engine call, so a crash mid-create
leaves a durable `CREATE_PENDING` rather than an unanswerable question.

**Show in Operate:** 3 active instances, all at the start event. Still zero user tasks.

**Rerun the same command.** Created 0, "already loaded 3, left untouched", still 3 active. Idempotence
is the property that makes an interrupted cutover resumable.

## 3. Reconcile — the gate

```sh
$APP reconcile fixtures/manifest.csv            # read-only
```

Checks the identity `MIGRATED + SKIPPED + QUARANTINED + PENDING + FAILED + ABORTED = manifest
count`, that PENDING and FAILED are zero, and that there is exactly one non-`ABORTED` instance
per `(runId, legacyId)`. Expect `GATE: PASS`.

## 4. Release

```sh
$APP reconcile fixtures/manifest.csv --release
```

One modification per instance: activate the target element with its variables, terminate the
start-event token. The held `migrator` job then cancels itself.

Verify placement — note the state filter, an empty filter is a *historical* search and returns
terminal rows too:

```sh
curl -s -X POST http://localhost:18080/v2/user-tasks/search \
  -H 'Content-Type: application/json' \
  -d '{"filter":{"state":"CREATED"}}'
```

Expect three tasks: `vision-assessment`/`civil-servant`, `cashier-fee-review`/`cashier`,
`issuing-review`/`issuing-officer`. **This is the payoff** — three cases that had no recorded
process state are now sitting at the correct step of a real process, with the right group
able to pick them up.

## 5. Show the gate refusing

Two variants worth demoing, in this order:

- `$APP reconcile fixtures/manifest.csv --abort` after a release → `ABORT REFUSED: 3 case(s)
  already RELEASED`, exit 1, user tasks untouched.
- Reset (below), load, then cancel one held instance by hand in Operate before reconciling →
  `GATE: FAIL`, exit 1, `1 held case(s) no longer ACTIVE in the engine: LP-2026-0000NN
  (instance …)`, and `--release` refuses.

A gate that only ever passes has not been demonstrated.

**Note what does *not* catch the second one.** The identity still balances — the ledger says
MIGRATED 3 whatever the engine did, because a ledger row records what this tool intended. The
counts are self-consistent and always will be. What fails is the liveness check the gate makes
against the engine for every CREATED row. Until 2026-09-17 that check did not exist and this
variant of the demo silently passed; the release then fired a modification at a terminated
instance and the breakage surfaced only in the release loop's error handling, after the other
two cases were already in front of clerks.

## Reset between runs

```sh
docker compose down -v && docker compose up -d   # wipes ledger and engine; re-run the deploy in step 0
```

The Postgres init scripts only run on an empty data directory, so `-v` is required, not optional.

## What this demo does not cover

- `quarantine-case.bpmn` is **not written**. The six quarantined records are counted and
  reason-coded, but there is no triage process to resolve them in.
- **Cleanup** (remove the `migrator` listener, revert, redeploy, start a fresh citizen
  application) is on the real cutover's critical path and is not yet rehearsed here.
- **Volume** (10,000 records) is out of POC scope; it measures, it does not certify.
- **Known anomaly:** instance cancellation has hung permanently twice, cleared only by a volume
  reset. The abort path depends on cancellation, so treat step 5's abort as unproven until that
  is reproduced. See "Open anomaly" in the design.
