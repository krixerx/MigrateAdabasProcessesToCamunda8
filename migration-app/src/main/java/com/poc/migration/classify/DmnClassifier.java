package com.poc.migration.classify;

import com.poc.migration.model.Classification;
import com.poc.migration.model.ClassificationInput;
import com.poc.migration.model.SourceRecord;
import io.camunda.client.CamundaClient;
import io.camunda.client.api.response.EvaluateDecisionResponse;
import io.camunda.client.api.response.EvaluatedDecisionOutput;
import io.camunda.client.api.response.MatchedDecisionRule;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Evaluates the decision table and turns matched rules into a decision.
 *
 * <p>Creates nothing. Standalone decision evaluation is what makes the dry run possible: the whole
 * population can be classified, repeatedly, without a single process instance existing. That is
 * the mechanism the improvement loop runs on.
 *
 * <h2>Two rules that look like details and are not</h2>
 *
 * <p><b>1. Read matchedRules, never the top-level output.</b> Verified on this stack: a
 * non-matching COLLECT evaluation returns the <em>string</em> {@code "null"} as its top-level
 * output, not an empty result. Branching on that value would treat "nothing matched" as a value
 * that matched.
 *
 * <p><b>2. Compare on the business tuple, EXCLUDING ruleId.</b> {@code ruleId} is an output column,
 * so two rules that agree on the decision but differ in id produce different raw tuples. Comparing
 * raw tuples would mark harmless redundancy as AMBIGUOUS and quarantine it - inflating the
 * exception queue on the one night it has to be small. Semantic equality is
 * {@code {outcome, targetElementId, reasonCode}}; rule ids are carried alongside as provenance.
 *
 * <pre>
 *   matchedRules ──► distinct {outcome,target,reason} tuples
 *                      │
 *                      ├─ 0            → QUARANTINE(UNMATCHED)
 *                      ├─ 1, one rule  → proceed
 *                      ├─ 1, N rules   → proceed + REDUNDANT_RULE warning
 *                      └─ 2+           → QUARANTINE(AMBIGUOUS)
 * </pre>
 */
@Component
public class DmnClassifier {

    // Output IDs from migration-classification.dmn. Structural, not cosmetic: see outputsOf().
    private static final String OUT_OUTCOME = "out-outcome";
    private static final String OUT_TARGET = "out-target";
    private static final String OUT_REASON = "out-reason";

    /** Elements a case may legitimately be migrated to. Anything else is a guard failure. */
    private static final Set<String> BACK_OFFICE_TARGETS =
            Set.of("vision-assessment", "cashier-fee-review", "issuing-review");

    private final CamundaClient camunda;
    private final String decisionId;

    // ONE constructor on purpose. Two would leave Spring unable to choose without an
    // @Autowired hint, which fails at context startup rather than at compile time.
    public DmnClassifier(
            CamundaClient camunda,
            @Value("${migration.decision-id:migration-classification}") String decisionId) {
        this.camunda = camunda;
        this.decisionId = decisionId;
    }

    public Classification classify(SourceRecord record, ClassificationInput input) {
        EvaluateDecisionResponse response = camunda.newEvaluateDecisionCommand()
                .decisionId(decisionId)
                .variables(input.toVariables())
                .send()
                .join();

        List<MatchedDecisionRule> matched = response.getEvaluatedDecisions().stream()
                .flatMap(d -> d.getMatchedRules().stream())
                .toList();

        if (matched.isEmpty()) {
            return Classification.quarantine(record, input, Classification.Reason.UNMATCHED);
        }

        List<String> ruleIds = matched.stream().map(MatchedDecisionRule::getRuleId).toList();

        // Semantic tuple: provenance deliberately excluded.
        Set<Decision> distinct = new LinkedHashSet<>();
        for (MatchedDecisionRule rule : matched) {
            Map<String, String> outputs = outputsOf(rule);
            distinct.add(new Decision(
                    outputs.get(OUT_OUTCOME),
                    outputs.get(OUT_TARGET),
                    outputs.get(OUT_REASON)));
        }

        if (distinct.size() > 1) {
            return Classification.quarantine(record, input, Classification.Reason.AMBIGUOUS, ruleIds);
        }

        Decision decision = distinct.iterator().next();
        boolean redundant = matched.size() > 1;

        if (decision.outcome() == null) {
            throw new IllegalStateException(
                    "decision table returned no outcome for " + record.sourceIdentity()
                            + "; the table's output contract is broken, not the data");
        }

        Classification.Outcome outcome = Classification.Outcome.valueOf(decision.outcome());

        if (outcome == Classification.Outcome.MIGRATE
                && !BACK_OFFICE_TARGETS.contains(decision.targetElementId())) {
            // Guard against rule-set growth. Unreachable with the baseline table; it exists so a
            // rule added later without reading the design fails loudly instead of routing a case
            // to a citizen-assigned element, where the assignee expression would evaluate to a
            // legacy id no IdP subject matches and lock the applicant out of their own case.
            return Classification.quarantine(record, input, Classification.Reason.CITIZEN_TARGET, ruleIds);
        }

        return new Classification(
                record,
                input,
                outcome,
                decision.targetElementId(),
                decision.reasonCode(),
                ruleIds,
                redundant,
                Map.of());
    }

    /**
     * Keyed by output ID, NOT output name.
     *
     * <p>Verified on the running engine: {@code getOutputName()} returns the column's human-facing
     * LABEL ("Outcome", "Target element"), not the {@code name} attribute ("outcome",
     * "targetElementId"). Labels are what a business analyst edits in Modeler while tidying up a
     * table, and an edit there would silently break classification with no compile error and no
     * test failure that names the cause. Output IDs are structural and owned by this project.
     */
    private static Map<String, String> outputsOf(MatchedDecisionRule rule) {
        Map<String, String> out = new LinkedHashMap<>();
        for (EvaluatedDecisionOutput o : rule.getEvaluatedOutputs()) {
            out.put(o.getOutputId(), unquote(o.getOutputValue()));
        }
        return out;
    }

    /**
     * Output values arrive JSON-encoded: {@code "MIGRATE"} with quotes, and the literal
     * {@code null} for an absent value. Verified against the running engine.
     */
    static String unquote(String raw) {
        if (raw == null || raw.equals("null")) {
            return null;
        }
        String trimmed = raw.trim();
        if (trimmed.length() >= 2 && trimmed.startsWith("\"") && trimmed.endsWith("\"")) {
            return trimmed.substring(1, trimmed.length() - 1);
        }
        return trimmed;
    }

    /** The part of a rule's output that carries meaning. Rule ids are not part of it. */
    private record Decision(String outcome, String targetElementId, String reasonCode) {}

    /** Exposed for the 96-cell domain check (criterion 9a). */
    public List<String> matchedRuleIds(ClassificationInput input) {
        EvaluateDecisionResponse response = camunda.newEvaluateDecisionCommand()
                .decisionId(decisionId)
                .variables(input.toVariables())
                .send()
                .join();
        List<String> ids = new ArrayList<>();
        response.getEvaluatedDecisions()
                .forEach(d -> d.getMatchedRules().forEach(r -> ids.add(r.getRuleId())));
        return ids;
    }
}
