package com.poc.migration.model;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The six inputs the decision table evaluates. <b>Every one is total: no nulls, ever.</b>
 *
 * <p>This is not tidiness, it is correctness. In FEEL an input entry tested against a null input
 * generally does not match, so a null-carrying record would fall to zero hits and quarantine
 * <em>whether or not that was intended</em>. The unmatched count would then be measuring FEEL null
 * semantics rather than rule coverage, and a fixture could pass for entirely the wrong reason.
 *
 * <p>Anything that cannot be turned into a total value is rejected in pre-validation and never
 * reaches the table.
 *
 * @param visionOutcome result of collapsing the periodic group. PROVISIONAL rule (OQ2): latest
 *                      occurrence by date. Record LP-2026-000002 has FAIL then PASS, so a
 *                      different collapsing rule flips its outcome.
 */
public record ClassificationInput(
        boolean hasDateCompleted,
        boolean hasIssued,
        boolean hasExpired,
        boolean withinCutoff,
        boolean feePaid,
        VisionOutcome visionOutcome) {

    public enum VisionOutcome {
        NONE, PASS, FAIL
    }

    /** Shape the DMN evaluation request expects. */
    public Map<String, Object> toVariables() {
        Map<String, Object> v = new LinkedHashMap<>();
        v.put("hasDateCompleted", hasDateCompleted);
        v.put("hasIssued", hasIssued);
        v.put("hasExpired", hasExpired);
        v.put("withinCutoff", withinCutoff);
        v.put("feePaid", feePaid);
        v.put("visionOutcome", visionOutcome.name());
        return v;
    }

    /**
     * Compact form for reporting, so the dry run can group unmatched records by the exact
     * combination that produced them. That grouping is what turns "268 unmatched" into an
     * investigable list, which is the input to the improvement loop.
     */
    public String combinationKey() {
        return (hasDateCompleted ? "C" : "c")
                + (hasIssued ? "I" : "i")
                + (hasExpired ? "E" : "e")
                + (withinCutoff ? "W" : "w")
                + (feePaid ? "F" : "f")
                + ":" + visionOutcome.name();
    }
}
