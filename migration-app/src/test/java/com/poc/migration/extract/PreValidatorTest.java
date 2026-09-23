package com.poc.migration.extract;

import static org.assertj.core.api.Assertions.assertThat;

import com.poc.migration.model.SourceRecord;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Pre-validation exists so that nothing reaching the decision table has to be guessed at, and so
 * that combinations the business calls impossible cannot slip through the table's "any" entries.
 */
class PreValidatorTest {

    private final PreValidator validator = new PreValidator();

    @Test
    @DisplayName("a clean record passes")
    void cleanRecordPasses() {
        assertThat(validator.validate(Records.valid().build())).isEmpty();
    }

    @Test
    @DisplayName("missing CASE_REF is rejected: no durable business key")
    void missingCaseRef() {
        // Fixture line 13. It still gets COUNTED, because source identity (line + hash) is
        // independent of business identity - which is the whole reason that separation exists.
        assertThat(validator.validate(Records.valid().caseRef("").build()))
                .get().asString().contains("CASE_REF missing");
    }

    @Test
    @DisplayName("missing LAST_UPDATED is rejected, NOT defaulted")
    void missingLastUpdatedMustNotDefault() {
        // Defaulting would make withinCutoff false, route to R4, and quietly push a LIVE case out
        // of scope. After Adabas is switched off that case exists in no system at all.
        assertThat(validator.validate(Records.valid().lastUpdated("").build()))
                .get().asString()
                .contains("LAST_UPDATED missing")
                .contains("silently push a live case out of scope");
    }

    @Test
    @DisplayName("unparseable dates are rejected rather than treated as absent")
    void unparseableDate() {
        assertThat(validator.validate(Records.valid().lastUpdated("not-a-date").build()))
                .get().asString().contains("unparseable");
    }

    @Test
    @DisplayName("malformed booleans are rejected, not coerced")
    void malformedBoolean() {
        assertThat(validator.validate(Records.valid().feePaid("SORT OF").build()))
                .get().asString().contains("FEE_PAID unrecognised");
    }

    @Test
    @DisplayName("an unrecognised vision result is rejected")
    void unrecognisedVisionResult() {
        assertThat(validator.validate(Records.valid()
                .visionTests(new SourceRecord.VisionTest(1, "2026-05-02", "MAYBE"))
                .build()))
                .get().asString().contains("vision result unrecognised");
    }

    @Test
    @DisplayName("contradiction: EXPIRED set with ISSUED empty")
    void expiredWithoutIssued() {
        // Fixture line 11. Without this check the record reaches the table, matches R4-R8 on the
        // "any" entry for hasExpired, and sails into a migration target.
        assertThat(validator.validate(Records.valid().expired("2026-02-01").build()))
                .get().asString().contains("EXPIRED set with ISSUED empty");
    }

    @Test
    @DisplayName("contradiction: DATE_COMPLETED set with ISSUED empty")
    void completedWithoutIssued() {
        assertThat(validator.validate(Records.valid().dateCompleted("2026-04-20").build()))
                .get().asString().contains("DATE_COMPLETED set with ISSUED empty");
    }

    @Test
    @DisplayName("issued and expired together is legitimate, not a contradiction")
    void issuedAndExpiredIsFine() {
        assertThat(validator.validate(
                Records.valid().issued("2021-03-04").expired("2024-03-04").build())).isEmpty();
    }
}
