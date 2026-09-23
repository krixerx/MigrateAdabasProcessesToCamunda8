package com.poc.migration.extract;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.poc.migration.model.ClassificationInput.VisionOutcome;
import com.poc.migration.model.SourceRecord;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The collapsing rule and the cutoff are the two places where a quiet mistake changes where a
 * citizen's case lands. Both are tested here rather than inferred from a passing pipeline.
 */
class RecordNormaliserTest {

    // freezeDate 2026-09-15, 6 months -> boundary 2026-03-15
    private static final Manifest MANIFEST = new Manifest(
            LocalDate.of(2026, 9, 15), 6, "run-1", 1, "sha256:header", Map.of());

    private final RecordNormaliser normaliser = new RecordNormaliser(MANIFEST);

    private static SourceRecord record(String lastUpdated, String feePaid, SourceRecord.VisionTest... vts) {
        return new SourceRecord(2, "sha256:x", "LP-1", "1", "U.SER", "User Name", "B", "42.00",
                lastUpdated, feePaid, List.of(vts), List.of(), "", "", "");
    }

    @Test
    @DisplayName("PE collapse takes the LATEST occurrence by date, not the first")
    void collapsesToLatestOccurrence() {
        // This is fixture LP-2026-000002: FAIL then PASS. Under "latest" it is a PASS awaiting the
        // cashier. Under "first" it would be a FAIL and the case would be SKIPPED - a live case
        // silently dropped. The rule is PROVISIONAL (OQ2), so the test pins the current choice.
        var r = record("2026-06-10", "FALSE",
                new SourceRecord.VisionTest(1, "2026-05-02", "FAIL"),
                new SourceRecord.VisionTest(2, "2026-06-10", "PASS"));

        assertThat(normaliser.collapseVision(r)).isEqualTo(VisionOutcome.PASS);
    }

    @Test
    @DisplayName("PE collapse is by DATE, not by occurrence number")
    void collapsesByDateNotOccurrenceOrder() {
        // Occurrence 1 is the later test. An export that is not chronologically ordered must not
        // change the answer.
        var r = record("2026-06-10", "FALSE",
                new SourceRecord.VisionTest(1, "2026-06-10", "PASS"),
                new SourceRecord.VisionTest(2, "2026-05-02", "FAIL"));

        assertThat(normaliser.collapseVision(r)).isEqualTo(VisionOutcome.PASS);
    }

    @Test
    @DisplayName("tie on date resolves deterministically by occurrence number")
    void tieOnDateIsDeterministic() {
        // Arbitrary, but a rerun must give the same answer as the first run. That property matters
        // more than which of the two wins.
        var r = record("2026-06-10", "FALSE",
                new SourceRecord.VisionTest(1, "2026-06-10", "FAIL"),
                new SourceRecord.VisionTest(2, "2026-06-10", "PASS"));

        assertThat(normaliser.collapseVision(r)).isEqualTo(VisionOutcome.PASS);
        assertThat(normaliser.collapseVision(r)).isEqualTo(VisionOutcome.PASS);
    }

    @Test
    @DisplayName("no occurrences collapses to NONE, never to null")
    void noOccurrencesIsNone() {
        assertThat(normaliser.collapseVision(record("2026-06-10", "FALSE"))).isEqualTo(VisionOutcome.NONE);
    }

    @Test
    @DisplayName("withinCutoff is computed from the manifest's freeze date, never the clock")
    void cutoffComesFromTheManifest() {
        // If this read the clock, a rerun on a later day would reclassify boundary records - the
        // same input giving a different answer, which is the one thing a migration must not do.
        assertThat(normaliser.normalise(record("2026-03-16", "FALSE")).withinCutoff()).isTrue();
        assertThat(normaliser.normalise(record("2025-11-02", "FALSE")).withinCutoff()).isFalse();
    }

    @Test
    @DisplayName("a record exactly on the cutoff boundary is INSIDE scope")
    void boundaryIsInclusive() {
        assertThat(normaliser.normalise(record("2026-03-15", "FALSE")).withinCutoff()).isTrue();
    }

    @Test
    @DisplayName("booleans are parsed strictly; nonsense throws rather than defaulting to false")
    void booleansAreStrict() {
        // Coercing "MAYBE" to false would route the case to a different rule with no trace.
        // Pre-validation catches it first in the pipeline; this asserts the parser itself refuses.
        assertThat(RecordNormaliser.parseBoolean("TRUE")).isTrue();
        assertThat(RecordNormaliser.parseBoolean("Y")).isTrue();
        assertThat(RecordNormaliser.parseBoolean("")).isFalse();
        assertThatThrownBy(() -> RecordNormaliser.parseBoolean("MAYBE"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("unrecognised boolean");
    }

    @Test
    @DisplayName("every DMN input is total: no nulls reach the decision table")
    void inputsAreTotal() {
        var input = normaliser.normalise(record("2026-06-10", "FALSE"));

        // In FEEL an entry tested against null generally does not match, so a null-carrying record
        // would fall to zero hits and quarantine whether or not that was intended - the unmatched
        // count would be measuring FEEL semantics rather than rule coverage.
        assertThat(input.visionOutcome()).isNotNull();
        assertThat(input.toVariables()).allSatisfy((k, v) -> assertThat(v).isNotNull());
    }
}
