package com.poc.migration.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

/**
 * {@code isCreationClosed} must answer for a run that does not exist yet.
 *
 * <p>It used to be called only from inside {@code Loader.load}, one line after {@code startRun},
 * where the row could not be missing. {@code migrate} asks it first, to skip a load phase that is
 * already done - and against a reset ledger {@code queryForObject} threw
 * {@code EmptyResultDataAccessException} out of the CLI instead of answering.
 */
class LedgerCreationClosedTest {

    private static final String RUN = "00000000-0000-0000-0000-000000000001";

    @Test
    @DisplayName("a run that was never started has not closed creation")
    void missingRunIsNotClosed() {
        assertThat(ledgerReturning(List.of()).isCreationClosed(RUN)).isFalse();
    }

    @Test
    @DisplayName("a run that closed creation says so")
    void closedRunIsClosed() {
        assertThat(ledgerReturning(List.of(true)).isCreationClosed(RUN)).isTrue();
    }

    @Test
    @DisplayName("a started but still open run says so")
    void openRunIsNotClosed() {
        assertThat(ledgerReturning(List.of(false)).isCreationClosed(RUN)).isFalse();
    }

    @SuppressWarnings("unchecked")
    private static Ledger ledgerReturning(List<Boolean> rows) {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.query(anyString(), any(RowMapper.class), any(Object[].class))).thenReturn(rows);
        return new Ledger(jdbc);
    }
}
