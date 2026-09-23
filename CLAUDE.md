# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

A POC that migrates **in-flight** business processes from a (synthetic) Software AG Adabas file
to a Camunda 8 BPMN process. Legacy process state was never recorded explicitly, so the pipeline
**infers** where each case sits from field values alone and places a token there.

- Design and rationale: `docs/designs/adabas-camunda8-inflight-migration-poc.md` — read this
  before changing behaviour. Most non-obvious choices are argued there.
- How to run the demo: `docs/demo-spec.md`.
- Git repository, remote `origin` is https://github.com/krixerx/MigrateAdabasProcessesToCamunda8 (public). Source files are UTF-8.

## Layout

| Path | What |
|---|---|
| `docker-compose.yml`, `.env` | Postgres 17 + Camunda 8.9.12. Non-default ports 18080/36500/19600/15432 so the sibling POC can run alongside. |
| `processes/` | `learner-permit-migration.bpmn` (3 back-office tasks, `migrator` listener on the start event), `migration-classification.dmn` (8 rules, COLLECT, no catch-all). `test/` variants are never deployed to a run. |
| `fixtures/` | 13 synthetic records, freeze manifest, hand-written expected outcomes. See `fixtures/README.md`. |
| `migration-app/` | One Spring Boot CLI app, entry points selected by the first argument: `classify`, `load`, `reconcile`, and `migrate` (the three chained in one process; `--dry-run` stops after classify, `--release` is still opt-in). |

## Build and test

```sh
mvn -f migration-app/pom.xml package   # 43 unit tests (~5s) + 2 engine tests that start their own container
mvn -f migration-app/pom.xml test -Dtest=DecisionTableDomainTest
```

45 tests in 8 classes. The 43 unit tests need nothing running. The 2 engine tests are both in
`DecisionTableDomainTest`; they deploy the DMN from `processes/`, so they assert the behaviour
of the *deployed* table.

## Invariants worth not breaking

- **Every DMN input is total.** A null input falls to zero hits and quarantines, which would make
  the unmatched count measure FEEL semantics instead of rule coverage.
- **`withinCutoff` comes from the manifest's `freezeDate`, never the clock.** Rerunning on a later
  day must not reclassify.
- **Read `evaluatedDecisions[].matchedRules[]`, never the top-level DMN output** — a non-match
  returns the *string* `"null"`. Key on `outputId`, not `getOutputName()` (that returns the column
  label, which analysts rename).
- **Ledger intent is written before the engine call.** A 409 on create is evidence of success, not
  a failure.
- **The ledger is intent, never evidence about the engine.** Bucket counts balance by construction
  and cannot detect an instance cancelled outside the tool. The gate therefore liveness-checks
  every `CREATED` row against the engine (`Reconciler.vanishedHeldCases`); `RELEASED` rows are
  deliberately exempt, since a clerk completing the task ends the instance legitimately.
- **That liveness check polls, it does not sample.** "Cancelled" and "not exported yet" are
  indistinguishable to one query against secondary storage. A fixed sleep was enough only because a
  human took seconds to type `reconcile` after `load`; `migrate` runs them back to back and the
  guess failed closed, refusing a correct release. It now waits up to `migration.visibility-timeout`
  (30s) for each held instance to appear, and reports the rest exactly as before.
- **`/v2/user-tasks/search` and `/v2/jobs/search` with an empty filter are historical searches.**
  Always filter by state or `processInstanceKey`.
- The process id is `learner-permit-migration`, **not** `learner-permit` — that id belongs to
  the sibling POC, and redeploying it there would hang every new citizen application.
