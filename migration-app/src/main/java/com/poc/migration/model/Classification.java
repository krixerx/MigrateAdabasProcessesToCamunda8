package com.poc.migration.model;

import java.util.List;
import java.util.Map;

/**
 * What the pipeline decided about one source record, and why.
 *
 * <pre>
 *   pre-validate ──fail──► QUARANTINE(PREVALIDATION)        never reaches the table
 *        │
 *        ▼
 *   DMN evaluate ──► matchedRules[]
 *        │
 *        ├─ 0 hits            ──► QUARANTINE(UNMATCHED)
 *        ├─ 1 distinct        ──► MIGRATE | SKIP | QUARANTINE  (as the rule says)
 *        ├─ 2+ IDENTICAL      ──► proceed, count REDUNDANT_RULE
 *        └─ 2+ DISTINCT       ──► QUARANTINE(AMBIGUOUS)
 *        │
 *        ▼
 *   contract check ──fail──► QUARANTINE(CONTRACT_UNSATISFIED)
 * </pre>
 *
 * @param input the six values the table was evaluated on. NULL for records rejected in
 *              pre-validation, which never reached the table. Carried because an unmatched count
 *              on its own is not actionable: the improvement loop needs the exact combination that
 *              matched nothing, grouped and counted, to turn "268 unmatched" into a list someone
 *              can investigate.
 */
public record Classification(
        SourceRecord record,
        ClassificationInput input,
        Outcome outcome,
        String targetElementId,
        String reasonCode,
        List<String> matchedRuleIds,
        boolean redundantRules,
        Map<String, Object> targetVariables) {

    public enum Outcome {
        MIGRATE, SKIP, QUARANTINE
    }

    /**
     * Reason codes. Quarantine reasons are what the exception queue is sorted by; skip reasons
     * explain why a record was legitimately left behind.
     *
     * <p>Counts expected on the 13-record fixture run, asserted by criterion 6:
     * PREVALIDATION 3, UNMATCHED 1, OUT_OF_CUTOFF 1, CONTRACT_UNSATISFIED 1, everything else 0.
     */
    public static final class Reason {
        // Quarantine
        public static final String PREVALIDATION = "PREVALIDATION";
        public static final String UNMATCHED = "UNMATCHED";
        public static final String AMBIGUOUS = "AMBIGUOUS";
        public static final String OUT_OF_CUTOFF = "OUT_OF_CUTOFF";
        public static final String CONTRACT_UNSATISFIED = "CONTRACT_UNSATISFIED";
        public static final String CREATE_FAILED = "CREATE_FAILED";
        /** Guard against rule-set growth. Unreachable today: R5-R7 all target back-office
         *  elements, so its expected count is zero. It exists so a future rule added without
         *  reading the design fails loudly rather than locking a citizen out of their own case. */
        public static final String CITIZEN_TARGET = "CITIZEN_TARGET";

        // Skip
        public static final String COMPLETED = "COMPLETED";
        public static final String LIVE_PERMIT = "LIVE_PERMIT";
        public static final String EXPIRED_HISTORICAL = "EXPIRED_HISTORICAL";
        public static final String TERMINATED_VISION_FAIL = "TERMINATED_VISION_FAIL";

        private Reason() {}
    }

    public static Classification preValidationFailure(SourceRecord r, String detail) {
        return new Classification(r, null, Outcome.QUARANTINE, null, Reason.PREVALIDATION,
                List.of(), false, Map.of("detail", detail));
    }

    public static Classification quarantine(SourceRecord r, ClassificationInput in, String reason) {
        return new Classification(r, in, Outcome.QUARANTINE, null, reason, List.of(), false, Map.of());
    }

    public static Classification quarantine(
            SourceRecord r, ClassificationInput in, String reason, List<String> ruleIds) {
        return new Classification(r, in, Outcome.QUARANTINE, null, reason, ruleIds, false, Map.of());
    }
}
