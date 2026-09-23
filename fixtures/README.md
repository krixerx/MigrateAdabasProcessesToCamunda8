# Synthetic Adabas file model

**This is invented.** It is not a draft of the real learner-permit file and must never be
presented as one. Its only job is to exercise the field shapes that make extraction hard:
groups, multiple-value fields, and periodic groups.

## The file

`LEARNER-PERMIT-APPLICATION`, exported as CSV the way an Adabas export actually looks —
repeating occurrences flattened into numbered columns.

| Adabas construct | Field | Exported as |
|---|---|---|
| — | `CASE_REF` | `CASE_REF` — **durable business key** |
| — | `ISN` | `ISN` — physical record address, audit metadata only |
| **Group** `APPLICANT` | `USER-ID`, `NAME` | `APPLICANT_USER_ID`, `APPLICANT_NAME` |
| — | `PERMIT-CATEGORY` | `PERMIT_CATEGORY` |
| — | `FEE-AMOUNT` | `FEE_AMOUNT` |
| — | `LAST-UPDATED` | `LAST_UPDATED` — carries case age |
| — | `FEE-PAID` | `FEE_PAID` |
| **Periodic group** `VISION-TEST` | `DATE`, `RESULT` | `VT_1_DATE`, `VT_1_RESULT`, `VT_2_…` |
| **Multiple value** `RESTRICTION` | — | `RESTR_1`, `RESTR_2`, `RESTR_3` |
| — | `ISSUED`, `EXPIRED`, `DATE-COMPLETED` | same names |

### Why `CASE_REF` and not `ISN`

The process key is derived deterministically from the source record, so the same record always
yields the same key in every run. An **ISN is a physical record address** that changes across
`ADAULD`/`ADALOD` and file reorganisation — using it would silently re-identify every record
after a reorg. `ISN` is carried for audit only.

Whether the real file has a durable business key is OQ10 and remains open.

### Occurrence columns are discovered, not assumed

`VT_<n>_*` and `RESTR_<n>` are found by scanning the header. The column count in this fixture
is not a schema. A record with more occurrences than there are columns is **data loss** and
quarantines; it never silently truncates.

### Collapsing rule (PROVISIONAL — OQ2)

`visionOutcome` collapses the periodic group to one value using **latest occurrence by date**.
This is an assumption pending business sign-off, and it is the most consequential one in the
POC: record `LP-2026-000002` has `FAIL` then `PASS`, so a different rule flips its outcome.

## Files

| File | What it is |
|---|---|
| `adabas-export.csv` | 13 records. The source. |
| `manifest.csv` | Freeze manifest: header carries `freezeDate`, `cutoffMonths`, `runId`; one row per record with its source hash. |
| `expected-outcomes.csv` | **Hand-written from the business rules before the DMN existed.** |

### Why expected outcomes are keyed by source line

Keyed by `SOURCE_LINE`, not `CASE_REF`. Fixture 12 is a record with **no** `CASE_REF` — it
cannot be keyed by a value it does not have. Source identity (line number + hash) is
independent of business identity, which is what lets a rejected record still be counted.

## Freeze parameters

`freezeDate = 2026-09-15`, `cutoffMonths = 6`, so the cutoff boundary is **2026-03-15**.
`withinCutoff` is computed from `LAST_UPDATED` against the manifest's `freezeDate` — **never
from the clock**, or a rerun on a later day would reclassify records.

## The 13 records

| # | Line | Case | Shape | Expected | Exercises |
|---|---|---|---|---|---|
| 1 | 2 | LP-2026-000001 | No vision test, fee unpaid | MIGRATE `vision-assessment` | R5 |
| 2 | 3 | LP-2026-000002 | PE: FAIL then PASS; MU: one restriction | MIGRATE `cashier-fee-review` | R6 + PE/MU flattening |
| 3 | 4 | LP-2026-000003 | Vision PASS, fee paid | MIGRATE `issuing-review` | R7 |
| 4 | 5 | LP-2026-000004 | `DATE_COMPLETED` set | SKIP `COMPLETED` | R1 |
| 5 | 6 | LP-2026-000005 | `ISSUED` set, `EXPIRED` empty | SKIP `LIVE_PERMIT` | R2 |
| 6 | 7 | LP-2021-000006 | Both set, in the past | SKIP `EXPIRED_HISTORICAL` | R3, OQ9 |
| 7 | 8 | LP-2026-000007 | Vision FAIL, fee unpaid | SKIP `TERMINATED_VISION_FAIL` | R8, OQ2 |
| 8 | 9 | LP-2025-000008 | Active, updated before the cutoff | QUARANTINE `OUT_OF_CUTOFF` | R4 — still live, so quarantine not skip |
| 9 | 10 | LP-2026-000009 | Fee paid, no vision test | QUARANTINE `UNMATCHED` | Deliberate 0-hit cell |
| 10 | 11 | LP-2026-000010 | `EXPIRED` set, `ISSUED` empty | QUARANTINE `PREVALIDATION` | Contradiction set |
| 11 | 12 | LP-2026-000011 | `LAST_UPDATED` missing | QUARANTINE `PREVALIDATION` | Must NOT default to out-of-cutoff |
| 12 | 13 | *(none)* | `CASE_REF` missing | QUARANTINE `PREVALIDATION` | Source identity, not business identity |
| 13 | 14 | LP-2026-000013 | Would be `issuing-review`, but `PERMIT_CATEGORY` empty | QUARANTINE `CONTRACT_UNSATISFIED` | Per-target variable contract |

## Per-target variable contract

Each target declares what its element and everything downstream needs. Checked **after**
classification (the target is a DMN output, so it does not exist at pre-validation time).

| Target | Required |
|---|---|
| `vision-assessment` | `applicantName` |
| `cashier-fee-review` | `applicantName`, `feeAmount` |
| `issuing-review` | `applicantName`, `permitCategory` |

`AMBIGUOUS` and `REDUNDANT_RULE` are **not** in this fixture set. The baseline table is disjoint
by construction, so no record can produce them. They are unit tests against
`processes/test/migration-classification-overlap.dmn`.
