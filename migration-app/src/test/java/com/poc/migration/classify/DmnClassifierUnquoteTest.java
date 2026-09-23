package com.poc.migration.classify;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * DMN output values arrive JSON-encoded. Getting this wrong is the difference between a target
 * element id of {@code issuing-review} and one of {@code "issuing-review"} - the second matches no
 * element in the model, so the modification would fail on every release.
 */
class DmnClassifierUnquoteTest {

    @Test
    @DisplayName("quoted strings lose their quotes")
    void stripsQuotes() {
        assertThat(DmnClassifier.unquote("\"MIGRATE\"")).isEqualTo("MIGRATE");
        assertThat(DmnClassifier.unquote("\"vision-assessment\"")).isEqualTo("vision-assessment");
    }

    @Test
    @DisplayName("the literal string \"null\" means absent, not a value")
    void nullLiteralBecomesNull() {
        // Verified against the running engine: an output entry of `null` comes back as the
        // four-character string "null". Treating it as a value would give a reason code of
        // "null" on every migrated case.
        assertThat(DmnClassifier.unquote("null")).isNull();
        assertThat(DmnClassifier.unquote(null)).isNull();
    }

    @Test
    @DisplayName("an unquoted value passes through unchanged")
    void unquotedPassesThrough() {
        assertThat(DmnClassifier.unquote("42")).isEqualTo("42");
    }

    @Test
    @DisplayName("a quoted empty string stays an empty string, not null")
    void quotedEmptyStringIsNotNull() {
        // Empty and absent are different answers and must not collapse into one.
        assertThat(DmnClassifier.unquote("\"\"")).isEmpty();
    }
}
