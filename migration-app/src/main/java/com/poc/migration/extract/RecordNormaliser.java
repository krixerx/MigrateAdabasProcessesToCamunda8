package com.poc.migration.extract;

import com.poc.migration.model.ClassificationInput;
import com.poc.migration.model.ClassificationInput.VisionOutcome;
import com.poc.migration.model.SourceRecord;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Comparator;
import java.util.Optional;

/**
 * Turns a raw record into the six total inputs the decision table evaluates.
 *
 * <p>Runs only on records that passed {@link PreValidator}, so anything reaching here is known to
 * be interpretable. That ordering matters: normalisation must never have to invent a value to
 * cover for missing data, because an invented value is a silent decision.
 *
 * <p><b>The collapsing rule is the most consequential assumption in the POC.</b> A periodic group
 * of vision-test attempts has to become one {@code visionOutcome}, and which one you pick changes
 * where cases land:
 *
 * <pre>
 *   LP-2026-000002:  2026-05-02 FAIL  ,  2026-06-10 PASS
 *
 *     latest occurrence  -> PASS  -> R6 -> cashier-fee-review   (what we do)
 *     first occurrence   -> FAIL  -> R8 -> SKIP terminated      (a case dropped!)
 *     any PASS           -> PASS  -> R6 -> cashier-fee-review
 * </pre>
 *
 * Pending business sign-off (OQ2). Recorded as an assumption, not a fact.
 */
public class RecordNormaliser {

    private final LocalDate cutoffBoundary;

    public RecordNormaliser(Manifest manifest) {
        // From the manifest's freeze date, NEVER from the clock. A rerun on a later day must
        // classify boundary records identically or the same input gives a different answer.
        this.cutoffBoundary = manifest.cutoffBoundary();
    }

    public ClassificationInput normalise(SourceRecord r) {
        boolean hasDateCompleted = present(r.dateCompleted());
        boolean hasIssued = present(r.issued());
        boolean hasExpired = present(r.expired());
        boolean feePaid = parseBoolean(r.feePaid());

        LocalDate lastUpdated = parseDate(r.lastUpdated())
                .orElseThrow(() -> new IllegalStateException(
                        "normalise called on a record with unusable LAST_UPDATED; "
                                + "pre-validation should have quarantined it: " + r.sourceIdentity()));
        // Boundary inclusive: a record updated exactly on the boundary is within scope.
        boolean withinCutoff = !lastUpdated.isBefore(cutoffBoundary);

        return new ClassificationInput(
                hasDateCompleted, hasIssued, hasExpired, withinCutoff, feePaid, collapseVision(r));
    }

    /** PROVISIONAL (OQ2): latest occurrence by date wins. */
    VisionOutcome collapseVision(SourceRecord r) {
        return r.visionTests().stream()
                .filter(vt -> parseDate(vt.date()).isPresent())
                .max(Comparator.comparing((SourceRecord.VisionTest vt) -> parseDate(vt.date()).orElseThrow())
                        // Tie on date: later occurrence number wins. Arbitrary but deterministic,
                        // and determinism is the property that matters for a rerun.
                        .thenComparingInt(SourceRecord.VisionTest::occurrence))
                .map(vt -> switch (vt.result().trim().toUpperCase()) {
                    case "PASS" -> VisionOutcome.PASS;
                    case "FAIL" -> VisionOutcome.FAIL;
                    default -> throw new IllegalStateException(
                            "unrecognised vision result '" + vt.result() + "'; pre-validation "
                                    + "should have quarantined it: " + r.sourceIdentity());
                })
                .orElse(VisionOutcome.NONE);
    }

    static boolean present(String s) {
        return s != null && !s.isBlank();
    }

    /** Strict. Anything that is not clearly true or false is rejected in pre-validation. */
    static boolean parseBoolean(String s) {
        String v = s == null ? "" : s.trim().toUpperCase();
        return switch (v) {
            case "TRUE", "T", "Y", "YES", "1" -> true;
            case "FALSE", "F", "N", "NO", "0", "" -> false;
            default -> throw new IllegalStateException("unrecognised boolean '" + s + "'");
        };
    }

    static boolean isParseableBoolean(String s) {
        try {
            parseBoolean(s);
            return true;
        } catch (IllegalStateException e) {
            return false;
        }
    }

    static Optional<LocalDate> parseDate(String s) {
        if (!present(s)) {
            return Optional.empty();
        }
        try {
            return Optional.of(LocalDate.parse(s.trim()));
        } catch (DateTimeParseException e) {
            return Optional.empty();
        }
    }
}
