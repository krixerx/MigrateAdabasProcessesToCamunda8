package com.poc.migration.ledger;

import java.util.Set;

/**
 * States a record moves through, and the bucket each one reconciles into.
 *
 * <pre>
 *                    ┌──────────────► SKIPPED
 *                    │
 *   EXTRACTED ──► CLASSIFIED ──────► QUARANTINED ◄── (pre-validation failure,
 *       │            │                    │            0 hits, ambiguous,
 *       │            │                    │            contract unsatisfied)
 *       │            ▼                    │
 *       │       CREATE_PENDING ◄──────────┘ (operator resolves quarantine to a target)
 *       │            │
 *       │            ├── confirmed ──► CREATED ──► RELEASE_PENDING ──► RELEASED
 *       │            │                    │              │
 *       │            │                    └──────────────┴──► ABORTED
 *       │            │
 *       │            ├── rejected ───► CREATE_FAILED ──► QUARANTINED
 *       │            │
 *       │            └── UNCERTAIN ──► PENDING_RESOLVED_EXISTS ──► CREATED
 *       │                              PENDING_RESOLVED_ABSENT ──► CREATE_PENDING
 *       │
 *       └── (dry run stops here; nothing is created)
 *
 *   RELEASE_PENDING ──► RELEASE_FAILED ──► RELEASE_PENDING   (retry after review)
 * </pre>
 *
 * <h2>Two placements that are load-bearing</h2>
 *
 * <p><b>{@code RELEASE_PENDING} counts as PENDING, not MIGRATED.</b> An earlier draft bucketed it
 * as migrated. A release whose {@code modification} call failed would then be reported as migrated
 * while the instance sat at the start event - the identity would balance, the gate would pass, and
 * one case would be silently stranded. The gate requires pending = 0, so a stalled release now
 * blocks rather than hides.
 *
 * <p><b>{@code ABORTED} has its own bucket.</b> Folding it into skipped or failed would make an
 * aborted run indistinguishable from one that legitimately left records behind.
 */
public enum LedgerState {

    EXTRACTED(Bucket.PENDING),
    CLASSIFIED(Bucket.PENDING),
    CREATE_PENDING(Bucket.PENDING),
    PENDING_RESOLVED_EXISTS(Bucket.PENDING),
    PENDING_RESOLVED_ABSENT(Bucket.PENDING),
    RELEASE_PENDING(Bucket.PENDING),

    SKIPPED(Bucket.SKIPPED),
    QUARANTINED(Bucket.QUARANTINED),

    CREATED(Bucket.MIGRATED),
    RELEASED(Bucket.MIGRATED),

    CREATE_FAILED(Bucket.FAILED),
    RELEASE_FAILED(Bucket.FAILED),

    ABORTED(Bucket.ABORTED);

    /** Reconciliation buckets. The identity is manifest = sum of all six. */
    public enum Bucket {
        MIGRATED, SKIPPED, QUARANTINED, PENDING, FAILED, ABORTED
    }

    private final Bucket bucket;

    LedgerState(Bucket bucket) {
        this.bucket = bucket;
    }

    public Bucket bucket() {
        return bucket;
    }

    /**
     * States in which a case holds a live instance in the engine. Only these would collide with
     * Camunda's businessId uniqueness, and only these would be cancelled by an abort.
     */
    public static final Set<LedgerState> LIVE_IN_ENGINE =
            Set.of(CREATED, RELEASE_PENDING, RELEASED);
}
