-- Migration ledger. Idempotent DDL, run on every startup.
--
-- WHY A LEDGER AT ALL, given the engine now guards duplicates (probe M8)?
-- Because Camunda holds nothing for the records that never became instances:
-- skipped, quarantined, pre-validation failures, and every dry run. And because
-- the one question Camunda structurally cannot answer is "did my create land?"
-- after a timed-out response. Only an intent written BEFORE the call can.

CREATE TABLE IF NOT EXISTS migration_run (
    run_id          TEXT PRIMARY KEY,
    freeze_date     DATE        NOT NULL,
    cutoff_months   INT         NOT NULL,
    header_hash     TEXT        NOT NULL,
    record_count    INT         NOT NULL,
    -- Creation must be CLOSED before reconciliation counts. Draining the exporter
    -- does not stop new writes: a loader retry or a quarantine resolution can create
    -- an instance after the gate passed. The gate counts against frozen membership.
    creation_closed BOOLEAN     NOT NULL DEFAULT FALSE,
    started_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS migration_ledger (
    run_id          TEXT NOT NULL REFERENCES migration_run(run_id),

    -- SOURCE IDENTITY. Always present, independent of business identity.
    -- This is what lets a record with no CASE_REF still be counted: it cannot be
    -- keyed by a value it does not have, but it always has a line and a hash.
    source_line     INT  NOT NULL,
    source_hash     TEXT NOT NULL,

    -- BUSINESS IDENTITY. Nullable on purpose. Becomes the engine's businessId.
    -- NEVER the ISN: an ISN is a physical record address that changes across
    -- ADAULD/ADALOD and file reorganisation.
    case_ref        TEXT,

    state           TEXT NOT NULL,
    target_element  TEXT,
    reason_code     TEXT,
    rule_ids        TEXT,
    detail          TEXT,

    -- Filled only after the engine confirms. Its absence next to CREATE_PENDING is
    -- precisely the uncertain-create case.
    instance_key    TEXT,

    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),

    PRIMARY KEY (run_id, source_line)
);

CREATE INDEX IF NOT EXISTS migration_ledger_state_idx ON migration_ledger (run_id, state);
CREATE INDEX IF NOT EXISTS migration_ledger_case_ref_idx ON migration_ledger (case_ref);

-- gstack-shortcut(issue-5): NO cross-run uniqueness constraint on case_ref.
--
-- Deferred by decision to the next iteration, not overlooked. What stands today:
--
--   LIVE duplicates  - prevented by the ENGINE. businessId = case_ref plus
--                      business-id-uniqueness-enabled means a second create for a
--                      case that is still active is rejected with 409. Verified.
--
--   COMPLETED cases  - NOT prevented. Camunda's uniqueness is checked against
--                      ACTIVE root instances only, so a case that was migrated,
--                      released and completed frees its businessId and a later run
--                      could migrate it again.
--
-- Upgrade when a second production-shaped run is planned. The candidate is a
-- partial unique index over live states, which leaves ABORTED rows outside it so
-- reruns stay possible:
--
--   CREATE UNIQUE INDEX migration_ledger_live_case_uniq
--       ON migration_ledger (case_ref)
--       WHERE case_ref IS NOT NULL
--         AND state IN ('CREATE_PENDING','CREATED','RELEASE_PENDING','RELEASED');
