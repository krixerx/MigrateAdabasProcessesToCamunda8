# Office Hours session notes — 2026-09-14

Companion to `adabas-camunda8-inflight-migration-poc.md`. Not implementation material.

## What I noticed about how you think

- You wrote *"this will cause a real mass"* about dual-running two systems, then chose the
  harder engineering path rather than the convenient one. Most people take drain-the-queue
  because it is cheaper and defend it afterwards. You ruled out the cheap option for a
  correctness reason and accepted the cost.
- When I put Apache HOP in an option, you said *"not pushing Apache HOP, this was just
  question, if we might need it."* You separated your own question from your own conclusion,
  out loud, before I could build a design around a preference you did not hold.
- You asked *"Why do you propose Postgres ledger for it?"* rather than accepting the
  component. That question found a real weakness: the honest justification (persisting
  creation intent across an uncertain API call) only surfaced because you asked.
- You overrode my recommended approach with a better one. *"In first iteration, it can be CSV
  file (which is like exported from Adabas), later we can integrate Adabas Docker."* That keeps
  the PE/MU flattening problem, which is the real difficulty, and drops the Adabas CE setup
  cost, which is not. I had those bundled; you separated them.

## Review history

Three rounds of adversarial review by an independent agent with fresh context, which verified
claims against the real sibling-project files and the running Camunda rather than trusting the document.

| Rev | Score | Headline findings |
|---|---|---|
| 1 | 6/10, 25 issues | `pay-permit-fee` is citizen-assigned, so "3 back-office roles" collided with the real model; no field carried case age, making the cutoff rule unimplementable; the DMN precedent was cited with inverted semantics; the reconciliation identity was broken by its own demo step; idempotency rested on an eventually-consistent read; The Assignment was written as SQL against a non-SQL database |
| 2 | 7/10, 20 issues | no `runId`, so abort-and-rerun collided with *both* idempotency guards; message-dedup TTL unspecified and the chosen mechanism never probed (the rejected one was probed three times); criterion 9 made iteration 1 undeliverable |
| 3 | 8/10, 13 issues | ledger had no `CLASSIFIED → QUARANTINED` edge, so R4 records sat in `pending` forever and the release gate could never pass; `runId` missing from the correlation key and the quarantine variable set; the per-target variable contract was enforced before its own input existed; criterion 7 unsatisfiable (jobs search is unbounded history, and `notify-issued` shares the job type); the three target user tasks do not exist upstream |
| 4 | not re-reviewed | Round-3 items 1-6 closed by direct edit. The review loop is capped at 3 rounds, so these fixes are unreviewed. |
| 5 | not re-reviewed | **Loader swapped to Camunda's documented runtime-migration mechanism**, at the user's prompting. `migrator` execution listener on the None Start Event + `/v2/process-instances/{key}/modification` replaces the message-start / hold / router path invented in revision 3. Deletes the TTL-tuning and correlation-collision findings; reintroduces ledger-based idempotency, mitigated by a one-instance-per-`(runId, legacyId)` gate check. |

## Course corrections the user made

Three times in one session the user was right and the design moved:

1. **CSV-with-real-PE/MU-shape before the Adabas container**, splitting two things I had bundled — the flattening problem (real difficulty, keep) from the container setup (cost, defer).
2. **"Why Postgres?"** — forced the honest justification (persisting creation intent across an uncertain API call) to surface instead of a reflex.
3. **"Maybe Camunda 7 to 8 migration can be useful for us as well."** The decisive one. I had searched, found a blog summary of start-instructions, and stopped — then invented a hold mechanism the vendor already documents. The user read the actual runtime migration procedure and brought it back. Search Before Building failure on my side, caught by the user.

**Verified by round 3, not asserted:** the DMN coverage claim (92 covered / 4 uncovered / 0
multi-hit over the 96-cell domain) is correct by enumeration; the state-to-bucket partition is
total; the iteration-1 criteria set is the exact complement of what it excludes.

**Standing caveat:** revision 4's edits have not themselves been through a review round.
