package com.poc.migration.ledger;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.EnumSet;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The reconciliation identity is {@code manifest = migrated + skipped + quarantined + pending +
 * failed + aborted}. That arithmetic only means anything if every state lands in exactly one
 * bucket - a state in no bucket is a record that vanishes from the count, and one in two buckets
 * is a record counted twice. Either way the gate would pass on a total that is not true.
 */
class LedgerStateTest {

    @Test
    @DisplayName("every state maps to exactly one bucket: the partition is total")
    void partitionIsTotal() {
        for (LedgerState state : LedgerState.values()) {
            assertThat(state.bucket())
                    .as("state %s must have a bucket", state)
                    .isNotNull();
        }
        // A record enum cannot map to two buckets by construction; this asserts every bucket is
        // reachable, so none is dead weight hiding a missing state.
        assertThat(Arrays.stream(LedgerState.values())
                .map(LedgerState::bucket)
                .collect(Collectors.toSet()))
                .containsExactlyInAnyOrderElementsOf(EnumSet.allOf(LedgerState.Bucket.class));
    }

    @Test
    @DisplayName("RELEASE_PENDING counts as PENDING, never as MIGRATED")
    void releasePendingIsPendingNotMigrated() {
        // An earlier draft bucketed it as migrated. A release whose modification call failed would
        // then be reported as migrated while the instance still sat at the start event: the
        // identity would balance, the gate would pass, and one case would be silently stranded.
        // The gate requires pending = 0, so a stalled release now blocks instead of hiding.
        assertThat(LedgerState.RELEASE_PENDING.bucket()).isEqualTo(LedgerState.Bucket.PENDING);
    }

    @Test
    @DisplayName("CREATE_PENDING counts as PENDING: an unknown outcome must block release")
    void createPendingBlocks() {
        assertThat(LedgerState.CREATE_PENDING.bucket()).isEqualTo(LedgerState.Bucket.PENDING);
    }

    @Test
    @DisplayName("both failure states are FAILED, so neither can hide in another bucket")
    void failuresAreFailed() {
        assertThat(LedgerState.CREATE_FAILED.bucket()).isEqualTo(LedgerState.Bucket.FAILED);
        assertThat(LedgerState.RELEASE_FAILED.bucket()).isEqualTo(LedgerState.Bucket.FAILED);
    }

    @Test
    @DisplayName("ABORTED has its own bucket, distinct from skipped and failed")
    void abortedIsItsOwnBucket() {
        // Folding it into skipped would make an aborted run indistinguishable from one that
        // legitimately left records behind.
        assertThat(LedgerState.ABORTED.bucket()).isEqualTo(LedgerState.Bucket.ABORTED);
    }

    @Test
    @DisplayName("LIVE_IN_ENGINE is exactly the states that hold an instance")
    void liveInEngineIsAccurate() {
        // This set drives two things: which rows abort must cancel, and which rows a rerun must
        // leave alone. Getting it wrong either strands instances or clobbers good state.
        assertThat(LedgerState.LIVE_IN_ENGINE)
                .containsExactlyInAnyOrder(
                        LedgerState.CREATED, LedgerState.RELEASE_PENDING, LedgerState.RELEASED);

        // Nothing in the set may be terminal-without-an-instance.
        assertThat(LedgerState.LIVE_IN_ENGINE)
                .doesNotContain(LedgerState.SKIPPED, LedgerState.QUARANTINED, LedgerState.ABORTED);
    }
}
