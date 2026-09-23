package com.poc.migration.classify;

import com.poc.migration.model.Classification;
import com.poc.migration.model.SourceRecord;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Checks that a case classified to a target can actually carry on from there.
 *
 * <p><b>Runs AFTER classification, not in pre-validation.</b> The contract is per target, and
 * {@code targetElementId} is a decision-table output - it does not exist when extract runs. An
 * earlier draft put this check in pre-validation, which was simply impossible.
 *
 * <p>A case that lands on the right desk and then cannot proceed is not migrated correctly. It is
 * an incident waiting for a clerk to open it.
 *
 * <pre>
 *   vision-assessment   requires  applicantName
 *   cashier-fee-review  requires  applicantName, feeAmount
 *   issuing-review      requires  applicantName, permitCategory
 * </pre>
 *
 * <p>These are declared here rather than derived from the BPMN. With no service tasks in the POC
 * model there is nothing to derive them from, so they are a deliberate modelling choice - which is
 * what makes fixture 13 testable at all.
 */
@Component
public class ContractChecker {

    private static final Map<String, List<String>> REQUIRED = Map.of(
            "vision-assessment", List.of("applicantName"),
            "cashier-fee-review", List.of("applicantName", "feeAmount"),
            "issuing-review", List.of("applicantName", "permitCategory"));

    /**
     * @return the classification unchanged with its target variables attached, or a quarantined
     *         one if the contract cannot be met.
     */
    public Classification check(Classification classification) {
        if (classification.outcome() != Classification.Outcome.MIGRATE) {
            return classification;
        }

        SourceRecord r = classification.record();
        Map<String, Object> available = availableVariables(r);
        List<String> required = REQUIRED.getOrDefault(classification.targetElementId(), List.of());

        for (String name : required) {
            Object value = available.get(name);
            if (value == null || (value instanceof String s && s.isBlank())) {
                return Classification.quarantine(r, classification.input(),
                        Classification.Reason.CONTRACT_UNSATISFIED, classification.matchedRuleIds());
            }
        }

        Map<String, Object> forTarget = new LinkedHashMap<>();
        required.forEach(name -> forTarget.put(name, available.get(name)));

        return new Classification(
                r,
                classification.input(),
                classification.outcome(),
                classification.targetElementId(),
                classification.reasonCode(),
                classification.matchedRuleIds(),
                classification.redundantRules(),
                Map.copyOf(forTarget));
    }

    /**
     * What the migrated instance carries.
     *
     * <p>Deliberately narrow. Zeebe variables are exported to secondary storage, so anything put
     * here lands in a new searchable store with its own retention and erasure obligations - in a
     * programme whose security posture keeps production data out of the POC entirely. Identifiers
     * and what the target element needs; the full record stays in the ledger.
     *
     * <p>{@code legacyStarterId} is carried as audited business data. On the reference
     * implementation an assignee expression is matched against the IdP username, so a legacy id
     * that maps to no subject would lock its owner out - that mapping is a programme dependency
     * and is NOT exercised here.
     */
    private static Map<String, Object> availableVariables(SourceRecord r) {
        Map<String, Object> v = new LinkedHashMap<>();
        v.put("applicantName", r.applicantName());
        v.put("permitCategory", r.permitCategory());
        v.put("feeAmount", r.feeAmount());
        v.put("legacyStarterId", r.applicantUserId());
        return v;
    }
}
