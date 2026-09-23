package com.poc.migration.reconcile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.poc.migration.extract.Manifest;
import com.poc.migration.ledger.Ledger;
import com.poc.migration.ledger.LedgerState;
import java.time.Duration;
import java.time.LocalDate;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The ledger records what the migration tool intended; it is not evidence about what the engine
 * still holds. Someone cancelling a held instance in Operate mid-cutover leaves the row CREATED,
 * so every count still balances - and before this check existed the gate passed, release fired a
 * modification at a terminated instance, and the breakage surfaced only after the surviving cases
 * were already in front of clerks.
 *
 * <p>Observed on the running stack: instance 2251799813685365 TERMINATED, its ledger row still
 * CREATED, {@code GATE: PASS}.
 */
class GateLivenessTest {

    private static final String RUN = "00000000-0000-0000-0000-000000000001";

    @Test
    @DisplayName("a held instance cancelled behind the tool's back fails the gate")
    void cancelledHeldInstanceFailsTheGate() {
        Ledger ledger = ledgerWith(
                held(2, "LP-2026-000001", "111"),
                held(3, "LP-2026-000002", "222"),
                held(4, "LP-2026-000003", "333"));

        // 333 was cancelled in Operate. The ledger has not heard about it and never will.
        Reconciler.GateResult gate = reconcilerWhereActive(ledger, Set.of(111L, 222L)).gate(manifest());

        assertThat(gate.passed()).isFalse();
        assertThat(gate.failures())
                .singleElement(org.assertj.core.api.InstanceOfAssertFactories.STRING)
                .contains("LP-2026-000003")
                .contains("333")
                .doesNotContain("LP-2026-000001");
    }

    @Test
    @DisplayName("all three instances still live: the gate passes")
    void allLiveStillPasses() {
        Ledger ledger = ledgerWith(
                held(2, "LP-2026-000001", "111"),
                held(3, "LP-2026-000002", "222"),
                held(4, "LP-2026-000003", "333"));

        Reconciler.GateResult gate =
                reconcilerWhereActive(ledger, Set.of(111L, 222L, 333L)).gate(manifest());

        assertThat(gate.passed()).isTrue();
    }

    @Test
    @DisplayName("CREATED with no instance key fails: a confirmed create must know its instance")
    void createdWithoutInstanceKeyFails() {
        Ledger ledger = ledgerWith(held(2, "LP-2026-000001", null));

        Reconciler.GateResult gate = reconcilerWhereActive(ledger, Set.of()).gate(manifest());

        assertThat(gate.passed()).isFalse();
        assertThat(gate.failures().getFirst()).contains("no instance key");
    }

    /**
     * RELEASED rows are deliberately not liveness-checked: a clerk completing the user task ends
     * the instance, which is the process working. Checking them would turn ordinary progress into
     * a gate failure on the next reconcile.
     */
    @Test
    @DisplayName("a completed RELEASED instance does not fail the gate")
    void releasedInstancesAreNotChecked() {
        Ledger ledger = mock(Ledger.class);
        when(ledger.bucketCounts(anyString())).thenReturn(buckets());
        when(ledger.liveDuplicatesByCaseRef(anyString())).thenReturn(Map.of());
        when(ledger.rowsInState(anyString(), any())).thenReturn(List.of());
        // Nothing is CREATED; the three migrated cases are all RELEASED and one has since
        // completed. No engine lookup should happen at all.
        Reconciler reconciler = new Reconciler(
                null, ledger, Duration.ZERO, Duration.ZERO, "learner-permit-migration") {
            @Override
            boolean isActive(long processInstanceKey) {
                throw new AssertionError("released instances must not be liveness-checked");
            }
        };

        assertThat(reconciler.gate(manifest()).passed()).isTrue();
    }

    /**
     * The bug {@code migrate --release} exposed: chained, the gate runs milliseconds after the
     * create loop, and on a cold stack none of the three instances had been exported yet. A
     * point-in-time check called all three vanished and refused a release that was correct - the
     * ledger rows sat at CREATED while three healthy instances waited at the start event.
     */
    @Test
    @DisplayName("instances not yet exported are waited for, not called vanished")
    void lagIsWaitedOutRatherThanFailed() {
        Ledger ledger = ledgerWith(
                held(2, "LP-2026-000001", "111"),
                held(3, "LP-2026-000002", "222"),
                held(4, "LP-2026-000003", "333"));

        // Secondary storage catches up mid-gate: nothing is visible until the third question.
        Set<Long> visible = new HashSet<>();
        AtomicInteger asked = new AtomicInteger();
        Reconciler reconciler = new Reconciler(
                null, ledger, Duration.ZERO, Duration.ofSeconds(5), "learner-permit-migration") {
            @Override
            boolean isActive(long processInstanceKey) {
                if (asked.incrementAndGet() > 2) {
                    visible.addAll(Set.of(111L, 222L, 333L));
                }
                return visible.contains(processInstanceKey);
            }
        };

        assertThat(reconciler.gate(manifest()).passed()).isTrue();
        assertThat(asked.get()).isGreaterThan(3); // it really did re-ask
    }

    /** A cancelled instance never appears, so the poll has to give up and still fail the gate. */
    @Test
    @DisplayName("waiting does not rescue a genuinely cancelled instance")
    void pollingStillFailsOnCancellation() {
        Ledger ledger = ledgerWith(
                held(2, "LP-2026-000001", "111"),
                held(3, "LP-2026-000002", "222"),
                held(4, "LP-2026-000003", "333"));

        Reconciler reconciler = new Reconciler(
                null, ledger, Duration.ZERO, Duration.ofMillis(500), "learner-permit-migration") {
            @Override
            boolean isActive(long processInstanceKey) {
                return processInstanceKey != 333L;
            }
        };

        Reconciler.GateResult gate = reconciler.gate(manifest());

        assertThat(gate.passed()).isFalse();
        assertThat(gate.failures())
                .singleElement(org.assertj.core.api.InstanceOfAssertFactories.STRING)
                .contains("LP-2026-000003")
                .doesNotContain("LP-2026-000001");
    }

    // -- fixtures ---------------------------------------------------------------------------

    /** Zero visibility timeout: one pass, no waiting. What a point-in-time check used to do. */
    private static Reconciler reconcilerWhereActive(Ledger ledger, Set<Long> activeKeys) {
        Set<Long> active = new HashSet<>(activeKeys);
        return new Reconciler(null, ledger, Duration.ZERO, Duration.ZERO, "learner-permit-migration") {
            @Override
            boolean isActive(long processInstanceKey) {
                return active.contains(processInstanceKey);
            }
        };
    }

    private static Ledger ledgerWith(Ledger.Row... createdRows) {
        Ledger ledger = mock(Ledger.class);
        when(ledger.bucketCounts(anyString())).thenReturn(buckets());
        when(ledger.liveDuplicatesByCaseRef(anyString())).thenReturn(Map.of());
        when(ledger.rowsInState(anyString(), any())).thenReturn(List.of());
        when(ledger.rowsInState(RUN, LedgerState.CREATED)).thenReturn(List.of(createdRows));
        return ledger;
    }

    /** The counts a clean run produces: 3 + 4 + 6 = 13, nothing pending or failed. */
    private static Map<LedgerState.Bucket, Integer> buckets() {
        Map<LedgerState.Bucket, Integer> counts = new EnumMap<>(LedgerState.Bucket.class);
        for (LedgerState.Bucket b : LedgerState.Bucket.values()) {
            counts.put(b, 0);
        }
        counts.put(LedgerState.Bucket.MIGRATED, 3);
        counts.put(LedgerState.Bucket.SKIPPED, 4);
        counts.put(LedgerState.Bucket.QUARANTINED, 6);
        return counts;
    }

    private static Ledger.Row held(int sourceLine, String caseRef, String instanceKey) {
        return new Ledger.Row(sourceLine, "sha256:irrelevant", caseRef, LedgerState.CREATED,
                "vision-assessment", null, instanceKey, null);
    }

    private static Manifest manifest() {
        return new Manifest(LocalDate.of(2026, 9, 15), 6, RUN, 13, "sha256:irrelevant", Map.of());
    }
}
