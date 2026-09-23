package com.poc.migration.load;

import com.poc.migration.extract.Manifest;
import com.poc.migration.ledger.Ledger;
import com.poc.migration.ledger.LedgerState;
import com.poc.migration.model.Classification;
import com.poc.migration.pipeline.DryRun;
import io.camunda.client.CamundaClient;
import io.camunda.client.api.command.ProblemException;
import io.camunda.client.api.response.ProcessInstanceEvent;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Creates held process instances. Nothing is released here.
 *
 * <p>Every instance halts at the start event's {@code migrator} execution listener, before
 * traversing any sequence flow. Verified by probe M1: one job in CREATED on
 * {@code apply-for-permit}, and zero user tasks.
 *
 * <h2>The ordering that matters</h2>
 *
 * <pre>
 *   1. write CREATE_PENDING          durable, BEFORE the engine is called
 *   2. createProcessInstance         businessId = caseRef
 *   3. write CREATED + instanceKey
 * </pre>
 *
 * Die between 1 and 3 and the row sits at CREATE_PENDING with no instance key. That is the one
 * question the engine cannot answer - a timed-out create may or may not have landed - and it is
 * why the intent has to be durable first. A rerun must never auto-recreate such a row.
 *
 * <h2>409 is good news, not a failure</h2>
 *
 * <p>With {@code business-id-uniqueness-enabled}, a create for a case that already holds a live
 * instance is rejected with ALREADY_EXISTS. On a retry after a lost response that rejection is
 * <em>evidence the first attempt succeeded</em>. It resolves the uncertain-create case from the
 * safe direction: the engine is telling us the instance exists, rather than us guessing from an
 * eventually-consistent search that can answer "not found" moments after a successful create.
 */
@Component
public class Loader {

    private static final Logger log = LoggerFactory.getLogger(Loader.class);

    private final CamundaClient camunda;
    private final Ledger ledger;
    private final String processId;

    public Loader(
            CamundaClient camunda,
            Ledger ledger,
            @Value("${migration.process-id:learner-permit-migration}") String processId) {
        this.camunda = camunda;
        this.ledger = ledger;
        this.processId = processId;
    }

    public Result load(DryRun.Report report) {
        Manifest manifest = report.manifest();
        String runId = manifest.runId();
        ledger.startRun(manifest);

        if (ledger.isCreationClosed(runId)) {
            throw new IllegalStateException("run " + runId + " has closed creation; "
                    + "creating now would add members after the gate counted them");
        }

        int created = 0;
        int skipped = 0;
        int quarantined = 0;
        int alreadyExisted = 0;
        int alreadyLoaded = 0;
        int failed = 0;

        for (Classification c : report.classifications()) {
            switch (c.outcome()) {
                case SKIP -> {
                    ledger.recordClassification(runId, c, LedgerState.SKIPPED);
                    skipped++;
                }
                case QUARANTINE -> {
                    ledger.recordClassification(runId, c, LedgerState.QUARANTINED);
                    quarantined++;
                }
                case MIGRATE -> {
                    // The state guard must run BEFORE any write. recordClassification upserts
                    // state=CLASSIFIED, which would wipe a CREATED row (and its instance key)
                    // before createHeld ever got to look at it. Found by rerunning: MIGRATED
                    // went 1 -> 0 on a second load that created nothing.
                    Outcome o = createHeld(runId, c);
                    switch (o) {
                        case CREATED -> created++;
                        case ALREADY_LOADED -> alreadyLoaded++;
                        case ALREADY_EXISTED -> alreadyExisted++;
                        case FAILED -> failed++;
                    }
                }
            }
        }

        return new Result(runId, created, skipped, quarantined, alreadyLoaded, alreadyExisted, failed);
    }

    private enum Outcome {
        /** This run created it just now. */
        CREATED,
        /** This run already had it live; left untouched. */
        ALREADY_LOADED,
        /** The engine refused a duplicate: a live instance exists for this businessId. */
        ALREADY_EXISTED,
        FAILED
    }

    private Outcome createHeld(String runId, Classification c) {
        int line = c.record().sourceLine();
        String caseRef = c.record().caseRef();

        // Step 0. A rerun must not destroy what an earlier run established.
        //
        // Found by rerunning: writing intent unconditionally reset rows that were already
        // CREATED (with an instance key) back to CREATE_PENDING, so a second load wiped the
        // record of a successful first one. The ledger is the only place that knows a create
        // landed; overwriting it loses the one fact nothing else can recover.
        var existing = ledger.stateOf(runId, line);
        if (existing.isPresent()) {
            LedgerState state = existing.get();
            if (LedgerState.LIVE_IN_ENGINE.contains(state)) {
                log.info("case {} is already {} in this run; leaving it alone", caseRef, state);
                return Outcome.ALREADY_LOADED;
            }
            if (state == LedgerState.CREATE_PENDING) {
                // The uncertain-create case. The engine cannot say whether the original call
                // landed, so this must NOT auto-recreate. It stays pending, the gate treats
                // pending as blocking, and an operator resolves it.
                log.warn("case {} (line {}) is CREATE_PENDING from an earlier attempt; "
                        + "refusing to auto-recreate", caseRef, line);
                return Outcome.FAILED;
            }
        }

        // Only now is it safe to write. Nothing above this line touches the ledger.
        ledger.recordClassification(runId, c, LedgerState.CLASSIFIED);

        // Step 1. Durable intent, before the engine hears anything about it.
        ledger.writeCreateIntent(runId, line);

        Map<String, Object> variables = new LinkedHashMap<>();
        variables.put("legacyId", caseRef);
        variables.put("runId", runId);
        variables.put("targetElementId", c.targetElementId());
        // Audited business data. On the reference implementation an assignee expression is matched
        // against the IdP username, so a legacy id mapping to no subject would lock its owner out.
        // That mapping is a programme dependency and is NOT exercised by this POC.
        variables.put("legacyStarterId", c.record().applicantUserId());
        variables.putAll(c.targetVariables());

        try {
            // Step 2. businessId = caseRef, derived deterministically from the source record, so
            // the same record always yields the same businessId in every run.
            ProcessInstanceEvent event = camunda.newCreateInstanceCommand()
                    .bpmnProcessId(processId)
                    .latestVersion()
                    .businessId(caseRef)
                    .variables(variables)
                    .send()
                    .join();

            // Step 3.
            ledger.confirmCreated(runId, line, String.valueOf(event.getProcessInstanceKey()));
            return Outcome.CREATED;

        } catch (ProblemException e) {
            if (isAlreadyExists(e)) {
                // The engine is telling us a live instance for this case already exists. On a
                // retry after a lost response that IS the confirmation we could not otherwise get.
                // The instance key is not in the rejection, so the row is parked for an operator
                // rather than guessed at from an eventually-consistent search.
                log.info("case {} already has a live instance; engine refused the duplicate", caseRef);
                ledger.transition(runId, line, LedgerState.PENDING_RESOLVED_EXISTS,
                        "engine rejected duplicate: a live instance already exists for this businessId");
                return Outcome.ALREADY_EXISTED;
            }
            log.error("create rejected for case {} (line {}): {}", caseRef, line, e.getMessage());
            ledger.transition(runId, line, LedgerState.CREATE_FAILED, e.getMessage());
            return Outcome.FAILED;

        } catch (RuntimeException e) {
            // Deliberately NOT marked failed. The call may have landed; only the answer was lost.
            // The row stays at CREATE_PENDING, which the gate treats as pending and therefore
            // blocks release until someone resolves it.
            log.warn("create outcome UNKNOWN for case {} (line {}): {}", caseRef, line, e.toString());
            return Outcome.FAILED;
        }
    }

    /**
     * A 409 is a DEFINITIVE answer, not an uncertain one: the engine is stating that a live
     * instance for this businessId exists. Catching the wrong exception type turned it into
     * "outcome unknown", which parked good rows as pending and blocked the gate for no reason.
     *
     * <p>The REST client raises {@link ProblemException}; {@code ClientStatusException} is the
     * gRPC path. Matching on the RFC 7807 status code rather than message text.
     */
    private static boolean isAlreadyExists(ProblemException e) {
        if (e.code() == 409) {
            return true;
        }
        String message = e.getMessage() == null ? "" : e.getMessage();
        return message.contains("ALREADY_EXISTS");
    }

    public record Result(
            String runId, int created, int skipped, int quarantined,
            int alreadyLoaded, int alreadyExisted, int failed) {}
}
