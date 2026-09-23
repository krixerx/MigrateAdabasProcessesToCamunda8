package com.poc.migration.model;

import java.util.List;

/**
 * One raw record as it came out of the Adabas export, before any interpretation.
 *
 * <p>Nothing here is normalised. Dates are still strings, booleans are still whatever the export
 * wrote, and the periodic group is still a list of occurrences. That is deliberate: the raw record
 * is what the manifest hash covers, and normalisation must not be able to invalidate a manifest.
 *
 * <pre>
 *   CSV line ──► SourceRecord ──► NormalisedRecord ──► DMN inputs
 *   (hashed)     (this)           (total, no nulls)    (6 values)
 * </pre>
 *
 * <p><b>sourceLine is the identity that always exists.</b> {@code caseRef} is the business key and
 * the eventual {@code businessId}, but a record can be missing it - that is fixture 12, and it is
 * exactly the record that cannot be keyed by a value it does not have. Every rejected record is
 * still counted because {@code sourceLine} plus {@code sourceHash} identify it independently of
 * business identity.
 *
 * @param sourceLine  1-based line number in the export, header counted as line 1
 * @param sourceHash  sha256 of the RAW byte-exact source line, trailing CR stripped
 * @param caseRef     durable business key. MAY BE BLANK - see above
 * @param isn         Adabas physical record address. AUDIT ONLY. Never a key: an ISN changes
 *                    across ADAULD/ADALOD and file reorganisation
 * @param visionTests periodic group occurrences, in the order the columns were discovered
 * @param restrictions multiple-value field occurrences
 */
public record SourceRecord(
        int sourceLine,
        String sourceHash,
        String caseRef,
        String isn,
        String applicantUserId,
        String applicantName,
        String permitCategory,
        String feeAmount,
        String lastUpdated,
        String feePaid,
        List<VisionTest> visionTests,
        List<String> restrictions,
        String issued,
        String expired,
        String dateCompleted) {

    /** One occurrence of the VISION-TEST periodic group. */
    public record VisionTest(int occurrence, String date, String result) {}

    /** Identity for reporting and for the ledger, usable even when caseRef is blank. */
    public String sourceIdentity() {
        return "line:" + sourceLine + "/" + sourceHash;
    }
}
