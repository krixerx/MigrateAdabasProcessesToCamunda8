package com.poc.migration.reconcile;

import com.poc.migration.extract.Manifest;
import com.poc.migration.ledger.Ledger;
import com.poc.migration.ledger.LedgerState;
import io.camunda.client.CamundaClient;
import io.camunda.client.api.search.enums.ProcessInstanceState;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * The gate, the release, and the abort.
 *
 * <pre>
 *   close creation ──► drain exporter ──► COUNT ──► gate
 *                                                    │
 *                                        pass ───────┼─────── fail
 *                                          │                    │
 *                                          ▼                    ▼
 *                                       RELEASE               ABORT
 *                                (modification per case)  (cancel every live
 *                                                          instance of the run)
 * </pre>
 *
 * <h2>Why creation is closed before counting</h2>
 *
 * <p>Draining the exporter makes existing writes visible. It does not stop new ones. A loader
 * retry or a quarantine resolution can create an instance after the gate has counted, and the gate
 * would then have authorised a release for a membership that has since grown. Closing creation
 * freezes the membership; draining then makes that frozen membership visible.
 *
 * <h2>Why the drain wait exists at all</h2>
 *
 * <p>The liveness check queries Camunda's secondary storage, which lags the engine by
 * {@code flushInterval} (0.5s in this stack). Count immediately after a create loop and the query
 * sees fewer instances than exist. Every other gate check reads the ledger over SQL and is
 * unaffected; this one decides whether a correct release is refused, so it waits for the instances
 * it expects rather than sleeping a guess and believing the first answer.
 *
 * <h2>Release is ONE operation</h2>
 *
 * <p>Probe M7, run against this stack: {@code modification} activated the target, terminated the
 * start-event token, and the held {@code migrator} job went to {@code CANCELED} by itself. The job
 * does not survive termination of its own token, so there is no second step to perform and nothing
 * to complete afterwards. Success is defined as the modification being accepted.
 */
@Component
public class Reconciler {

    private static final Logger log = LoggerFactory.getLogger(Reconciler.class);

    /** The element every migrated instance is held at, and which release terminates. */
    private static final String HOLD_ELEMENT = "apply-for-permit";

    /** How often the visibility poll re-asks the engine. Short next to the flush interval. */
    private static final Duration POLL_INTERVAL = Duration.ofMillis(250);

    private final CamundaClient camunda;
    private final Ledger ledger;
    private final Duration drainWait;
    private final Duration visibilityTimeout;
    private final String processId;

    public Reconciler(
            CamundaClient camunda,
            Ledger ledger,
            @Value("${migration.drain-wait:PT3S}") Duration drainWait,
            @Value("${migration.visibility-timeout:PT30S}") Duration visibilityTimeout,
            @Value("${migration.process-id:learner-permit-migration}") String processId) {
        this.camunda = camunda;
        this.ledger = ledger;
        this.processId = processId;
        // The floor: several times the configured flushInterval, so writes made before the gate
        // have had a chance to land before anything is counted.
        this.drainWait = drainWait;
        // The proof. A fixed sleep alone is a guess, and `migrate` made the guess wrong: run by
        // hand, reconcile happens whole seconds after load because a human types it, and 3s was
        // always enough. Chained, the gate starts milliseconds after the last create, and on a
        // cold stack the export had not landed - so every held instance looked cancelled and the
        // gate refused a release it should have allowed.
        this.visibilityTimeout = visibilityTimeout;
    }

    public GateResult gate(Manifest manifest) {
        String runId = manifest.runId();

        ledger.closeCreation(runId);
        log.info("creation closed for run {}", runId);

        try {
            Thread.sleep(drainWait.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while waiting for the exporter to drain", e);
        }

        Map<LedgerState.Bucket, Integer> counts = ledger.bucketCounts(runId);
        int total = counts.values().stream().mapToInt(Integer::intValue).sum();

        List<String> failures = new ArrayList<>();

        if (total != manifest.recordCount()) {
            failures.add("identity does not balance: buckets total " + total
                    + " but the manifest says " + manifest.recordCount());
        }
        int pending = counts.getOrDefault(LedgerState.Bucket.PENDING, 0);
        if (pending != 0) {
            failures.add(pending + " record(s) PENDING; a record whose outcome is unknown must "
                    + "block release, not be released around");
        }
        int failed = counts.getOrDefault(LedgerState.Bucket.FAILED, 0);
        if (failed != 0) {
            failures.add(failed + " record(s) FAILED");
        }
        Map<String, Integer> duplicates = ledger.liveDuplicatesByCaseRef(runId);
        if (!duplicates.isEmpty()) {
            failures.add("live duplicates: " + duplicates);
        }

        List<String> vanished = vanishedHeldCases(runId);
        if (!vanished.isEmpty()) {
            failures.add(vanished.size() + " held case(s) no longer ACTIVE in the engine"
                    + " (still not visible after " + visibilityTimeout.toSeconds() + "s): "
                    + String.join(", ", vanished)
                    + ". The ledger records what this tool intended, not what the engine still has.");
        }

        return new GateResult(counts, total, manifest.recordCount(), List.copyOf(failures), duplicates);
    }

    /**
     * Held cases whose instance the engine no longer has ACTIVE.
     *
     * <p><b>Why the ledger is not enough.</b> Every other gate check reads the ledger, and the
     * ledger records intent: a row stays CREATED whatever happens to the instance afterwards.
     * Cancel a held instance in Operate and the counts still balance, the gate still passes, and
     * release then fires a modification at an instance that is gone - caught one layer later by
     * the release loop's catch, after the other cases are already in front of clerks. {@link
     * #abort} already refuses to trust the ledger for discovery. The gate guards the more
     * dangerous direction and must not trust it either.
     *
     * <p><b>Why only CREATED.</b> RELEASE_PENDING already blocks via the PENDING bucket. RELEASED
     * is deliberately excluded: those instances are live work, and a clerk completing their task
     * ends the instance legitimately - checking them would turn ordinary progress into a gate
     * failure on the next run.
     *
     * <p><b>Why this polls.</b> "Cancelled" and "not exported yet" look identical to a
     * point-in-time query against secondary storage, and the difference decides whether a correct
     * release is refused. Waiting resolves it in the only direction that is safe: an instance that
     * becomes visible was lag, and one that never does is reported exactly as before. A genuinely
     * cancelled instance therefore costs the full timeout before the gate fails - slower, and still
     * failing closed.
     */
    private List<String> vanishedHeldCases(String runId) {
        List<String> vanished = new ArrayList<>();
        List<Ledger.Row> unconfirmed = new ArrayList<>();

        for (Ledger.Row row : ledger.rowsInState(runId, LedgerState.CREATED)) {
            if (row.instanceKey() == null) {
                // Nothing to wait for: a create that never recorded its instance is not lag.
                vanished.add(row.caseRef() + " (no instance key recorded)");
            } else {
                unconfirmed.add(row);
            }
        }

        long deadline = System.nanoTime() + visibilityTimeout.toNanos();
        while (true) {
            unconfirmed.removeIf(row -> isActive(Long.parseLong(row.instanceKey())));
            if (unconfirmed.isEmpty() || System.nanoTime() >= deadline) {
                break;
            }
            log.info("{} held instance(s) not yet visible in secondary storage; waiting",
                    unconfirmed.size());
            try {
                Thread.sleep(POLL_INTERVAL.toMillis());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("interrupted while waiting for held instances", e);
            }
        }

        for (Ledger.Row row : unconfirmed) {
            vanished.add(row.caseRef() + " (instance " + row.instanceKey() + ")");
        }
        return vanished;
    }

    /**
     * One query per held case. Honest at POC scale and wrong at volume - 10,000 held cases is
     * 10,000 round trips. A single paged search over the run's instances is the production shape.
     * Package-private so a test can substitute an answer without booting an engine.
     */
    boolean isActive(long processInstanceKey) {
        return !camunda.newProcessInstanceSearchRequest()
                .filter(f -> f.processInstanceKey(processInstanceKey)
                        .state(ProcessInstanceState.ACTIVE))
                .send().join().items().isEmpty();
    }

    /**
     * Releases every held instance of the run.
     *
     * <p>Intent first, exactly as with creation: {@code CREATED -> RELEASE_PENDING -> RELEASED}. A
     * modification whose answer is lost leaves the row at RELEASE_PENDING, which the gate counts as
     * pending and therefore blocks on, rather than reporting it as migrated.
     */
    public ReleaseResult release(String runId) {
        List<Ledger.Row> held = ledger.rowsInState(runId, LedgerState.CREATED);
        int released = 0;
        int failed = 0;

        for (Ledger.Row row : held) {
            if (row.instanceKey() == null) {
                log.warn("case {} is CREATED with no instance key; skipping", row.caseRef());
                failed++;
                continue;
            }
            ledger.transition(runId, row.sourceLine(), LedgerState.RELEASE_PENDING, null);
            try {
                camunda.newModifyProcessInstanceCommand(Long.parseLong(row.instanceKey()))
                        .activateElement(row.targetElement())
                        .and()
                        .terminateElements(HOLD_ELEMENT)
                        .send()
                        .join();
                ledger.transition(runId, row.sourceLine(), LedgerState.RELEASED, null);
                released++;
            } catch (RuntimeException e) {
                log.error("release failed for case {} (instance {}): {}",
                        row.caseRef(), row.instanceKey(), e.getMessage());
                ledger.transition(runId, row.sourceLine(), LedgerState.RELEASE_FAILED, e.getMessage());
                failed++;
            }
        }
        return new ReleaseResult(released, failed);
    }

    /**
     * Cancels every live instance of the run.
     *
     * <p><b>Abort is forbidden once anything has been RELEASED.</b> A released case has a live user
     * task a clerk can already see; cancelling it after the fact is not a rollback, it is deleting
     * work someone may have started. Past that point recovery means finishing the release, not
     * undoing it.
     *
     * <p><b>Discovery is not trusted to the ledger alone.</b> A create whose response was lost has
     * no instance key recorded, so cancelling only what the ledger knows about would leave exactly
     * the instances most likely to be orphaned. Active instances carrying this run's business ids
     * are cancelled whatever the ledger says about them.
     */
    public AbortResult abort(String runId) {
        List<Ledger.Row> released = ledger.rowsInState(runId, LedgerState.RELEASED);
        if (!released.isEmpty()) {
            throw new IllegalStateException("abort refused: " + released.size()
                    + " case(s) already RELEASED. Those are live for clerks; recovery must finish "
                    + "releasing the rest, not cancel work that has started.");
        }

        // 1. What the ledger knows about.
        Map<Long, Integer> keyToLine = new LinkedHashMap<>();
        for (LedgerState state : List.of(LedgerState.CREATED, LedgerState.RELEASE_PENDING)) {
            for (Ledger.Row row : ledger.rowsInState(runId, state)) {
                if (row.instanceKey() != null) {
                    keyToLine.put(Long.parseLong(row.instanceKey()), row.sourceLine());
                }
            }
        }

        // 2. What the ledger LOST. A create whose response never arrived has no instance key
        //    recorded, so cancelling only what the ledger knows about would miss precisely the
        //    instances most likely to be orphaned. Ask the engine instead.
        int orphans = 0;
        for (String caseRef : ledger.caseRefsForRun(runId)) {
            for (var pi : camunda.newProcessInstanceSearchRequest()
                    .filter(f -> f.processDefinitionId(processId)
                            .businessId(caseRef)
                            .state(ProcessInstanceState.ACTIVE))
                    .send().join().items()) {
                if (keyToLine.putIfAbsent(pi.getProcessInstanceKey(), null) == null) {
                    orphans++;
                    log.warn("orphan instance {} for case {} was not in the ledger", 
                            pi.getProcessInstanceKey(), caseRef);
                }
            }
        }

        // 3. Cancel, then VERIFY. Two instances once became permanently unresponsive to
        //    cancellation in testing - no HTTP response at all, instance still ACTIVE, no
        //    incident - and the failure was never reproduced. An unexplained failure in the
        //    mechanism that makes a no-rollback cutover survivable is not something to trust a
        //    single 204 about. Abort reports what it CONFIRMED gone, not what it asked to go.
        int cancelled = 0;
        int unconfirmed = 0;
        List<Long> stillActive = new ArrayList<>();

        for (Map.Entry<Long, Integer> e : keyToLine.entrySet()) {
            long key = e.getKey();
            try {
                camunda.newCancelInstanceCommand(key).send().join();
            } catch (RuntimeException ex) {
                log.error("cancel call failed for instance {}: {}", key, ex.getMessage());
            }
            if (confirmGone(key)) {
                cancelled++;
                if (e.getValue() != null) {
                    ledger.transition(runId, e.getValue(), LedgerState.ABORTED, null);
                }
            } else {
                unconfirmed++;
                stillActive.add(key);
                log.error("instance {} is STILL ACTIVE after cancellation; not marking it aborted", key);
            }
        }

        return new AbortResult(cancelled, unconfirmed, orphans, List.copyOf(stillActive));
    }

    /** Polls until the instance is gone from the active set, or the budget runs out. */
    private boolean confirmGone(long processInstanceKey) {
        for (int attempt = 0; attempt < 10; attempt++) {
            try {
                Thread.sleep(300);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
            boolean active = !camunda.newProcessInstanceSearchRequest()
                    .filter(f -> f.processInstanceKey(processInstanceKey)
                            .state(ProcessInstanceState.ACTIVE))
                    .send().join().items().isEmpty();
            if (!active) {
                return true;
            }
        }
        return false;
    }

    public record GateResult(
            Map<LedgerState.Bucket, Integer> counts,
            int total,
            int manifestCount,
            List<String> failures,
            Map<String, Integer> duplicates) {
        public boolean passed() {
            return failures.isEmpty();
        }
    }

    public record ReleaseResult(int released, int failed) {}

    /**
     * @param unconfirmed instances still ACTIVE after a cancellation attempt. NOT counted as
     *                    aborted, and NOT transitioned in the ledger: an abort that reports
     *                    success on an instance that is still running is worse than one that
     *                    reports failure.
     */
    public record AbortResult(int cancelled, int unconfirmed, int orphansFound, List<Long> stillActive) {
        public boolean clean() {
            return unconfirmed == 0;
        }
    }
}
