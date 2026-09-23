package com.poc.migration.extract;

import com.poc.migration.model.SourceRecord;
import java.util.List;
import java.util.Optional;

/**
 * Rejects records that cannot be interpreted, BEFORE the decision table sees them.
 *
 * <p>Two distinct jobs, and both matter:
 *
 * <ol>
 *   <li><b>Make the DMN inputs total.</b> Anything that would have to become null, or be guessed,
 *       is stopped here. That is what keeps the unmatched count a measure of rule coverage rather
 *       than of FEEL null semantics.
 *   <li><b>Catch contradictions.</b> Combinations the business says are impossible. Without this
 *       the table's {@code -} entries would let them through: a record with EXPIRED set and ISSUED
 *       empty matches R4-R8 on {@code hasExpired} and would sail into a migration target.
 * </ol>
 *
 * <p><b>Why a missing LAST_UPDATED must reject rather than default.</b> Defaulting it would make
 * {@code withinCutoff} false, which routes to R4, which is an out-of-scope outcome. A live case
 * would be quietly pushed out of scope by a missing field. After Adabas is switched off that case
 * exists in no system at all. So: quarantine, loudly.
 */
public class PreValidator {

    /** @return the reason it fails, or empty if it passes. */
    public Optional<String> validate(SourceRecord r) {
        // Identity first. Without CASE_REF there is no business key, no businessId for the
        // engine's uniqueness check, and nothing to key the ledger on. The record is still
        // COUNTED, via sourceLine + sourceHash, which is the whole point of source identity
        // being separate from business identity.
        if (!RecordNormaliser.present(r.caseRef())) {
            return Optional.of("CASE_REF missing: no durable business key");
        }
        if (!RecordNormaliser.present(r.applicantUserId())) {
            return Optional.of("APPLICANT_USER_ID missing: starter identity unrecoverable");
        }

        // Must not default. See class comment.
        if (!RecordNormaliser.present(r.lastUpdated())) {
            return Optional.of("LAST_UPDATED missing: cannot evaluate the cutoff, and defaulting "
                    + "it would silently push a live case out of scope");
        }
        if (RecordNormaliser.parseDate(r.lastUpdated()).isEmpty()) {
            return Optional.of("LAST_UPDATED unparseable: '" + r.lastUpdated() + "'");
        }

        // Reject malformed rather than coercing. "Never null" says nothing about "never nonsense".
        if (!RecordNormaliser.isParseableBoolean(r.feePaid())) {
            return Optional.of("FEE_PAID unrecognised: '" + r.feePaid() + "'");
        }
        for (String dateField : List.of(r.issued(), r.expired(), r.dateCompleted())) {
            if (RecordNormaliser.present(dateField) && RecordNormaliser.parseDate(dateField).isEmpty()) {
                return Optional.of("date field unparseable: '" + dateField + "'");
            }
        }
        for (SourceRecord.VisionTest vt : r.visionTests()) {
            String result = vt.result() == null ? "" : vt.result().trim().toUpperCase();
            if (!result.equals("PASS") && !result.equals("FAIL")) {
                return Optional.of("vision result unrecognised at occurrence " + vt.occurrence()
                        + ": '" + vt.result() + "'");
            }
            if (RecordNormaliser.parseDate(vt.date()).isEmpty()) {
                return Optional.of("vision date unparseable at occurrence " + vt.occurrence()
                        + ": '" + vt.date() + "'");
            }
        }

        // Contradiction set. Explicit and testable, not "handle errors".
        boolean hasIssued = RecordNormaliser.present(r.issued());
        boolean hasExpired = RecordNormaliser.present(r.expired());
        boolean hasCompleted = RecordNormaliser.present(r.dateCompleted());

        if (hasExpired && !hasIssued) {
            return Optional.of("contradiction: EXPIRED set with ISSUED empty "
                    + "(a permit that expired without being issued)");
        }
        if (hasCompleted && !hasIssued) {
            return Optional.of("contradiction: DATE_COMPLETED set with ISSUED empty "
                    + "(completed without issuance)");
        }

        return Optional.empty();
    }
}
