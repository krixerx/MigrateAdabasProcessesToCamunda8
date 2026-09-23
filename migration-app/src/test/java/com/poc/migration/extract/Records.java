package com.poc.migration.extract;

import com.poc.migration.model.SourceRecord;
import java.util.ArrayList;
import java.util.List;

/**
 * Test-only builder for {@link SourceRecord}.
 *
 * <p>Deliberately not on the production record: a 15-field record with one valid shape and many
 * invalid ones is exactly what a test wants and exactly what production code should not offer,
 * because a builder with sensible defaults makes it easy to construct a record that could never
 * come out of an export.
 *
 * <p>Defaults are a clean, valid record. Each test breaks precisely one thing.
 */
final class Records {

    static Builder valid() {
        return new Builder();
    }

    static final class Builder {
        private int sourceLine = 2;
        private String sourceHash = "sha256:test";
        private String caseRef = "LP-2026-000001";
        private String isn = "8891";
        private String applicantUserId = "K.TAMM";
        private String applicantName = "Kadri Tamm";
        private String permitCategory = "B";
        private String feeAmount = "42.00";
        private String lastUpdated = "2026-08-01";
        private String feePaid = "FALSE";
        private final List<SourceRecord.VisionTest> visionTests = new ArrayList<>();
        private final List<String> restrictions = new ArrayList<>();
        private String issued = "";
        private String expired = "";
        private String dateCompleted = "";

        Builder sourceLine(int v) { this.sourceLine = v; return this; }
        Builder caseRef(String v) { this.caseRef = v; return this; }
        Builder applicantUserId(String v) { this.applicantUserId = v; return this; }
        Builder applicantName(String v) { this.applicantName = v; return this; }
        Builder permitCategory(String v) { this.permitCategory = v; return this; }
        Builder lastUpdated(String v) { this.lastUpdated = v; return this; }
        Builder feePaid(String v) { this.feePaid = v; return this; }
        Builder issued(String v) { this.issued = v; return this; }
        Builder expired(String v) { this.expired = v; return this; }
        Builder dateCompleted(String v) { this.dateCompleted = v; return this; }

        Builder visionTests(SourceRecord.VisionTest... v) {
            visionTests.clear();
            visionTests.addAll(List.of(v));
            return this;
        }

        Builder restrictions(String... v) {
            restrictions.clear();
            restrictions.addAll(List.of(v));
            return this;
        }

        SourceRecord build() {
            return new SourceRecord(sourceLine, sourceHash, caseRef, isn, applicantUserId,
                    applicantName, permitCategory, feeAmount, lastUpdated, feePaid,
                    List.copyOf(visionTests), List.copyOf(restrictions),
                    issued, expired, dateCompleted);
        }
    }

    private Records() {}
}
