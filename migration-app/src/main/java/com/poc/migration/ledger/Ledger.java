package com.poc.migration.ledger;

import com.poc.migration.extract.Manifest;
import com.poc.migration.model.Classification;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Reads and writes the migration ledger. The only component that talks to the ledger schema. */
@Component
public class Ledger {

    private final JdbcTemplate jdbc;

    public Ledger(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void startRun(Manifest manifest) {
        jdbc.update("""
                INSERT INTO migration_run (run_id, freeze_date, cutoff_months, header_hash, record_count)
                VALUES (?, ?, ?, ?, ?)
                ON CONFLICT (run_id) DO NOTHING
                """,
                manifest.runId(), java.sql.Date.valueOf(manifest.freezeDate()),
                manifest.cutoffMonths(), manifest.headerHash(), manifest.recordCount());
    }

    /**
     * Closes creation for a run. After this the membership is frozen and the gate can count
     * against it. Draining the exporter is not enough on its own: it makes existing writes
     * visible, it does not stop new ones arriving from a retry or a quarantine resolution.
     */
    public void closeCreation(String runId) {
        jdbc.update("UPDATE migration_run SET creation_closed = TRUE WHERE run_id = ?", runId);
    }

    /**
     * Whether this run has closed creation.
     *
     * <p><b>A run that does not exist has not closed creation.</b> The question used to be asked
     * only from inside {@code Loader.load}, immediately after {@link #startRun}, so the row was
     * always there and {@code queryForObject} was safe. {@code migrate} asks it before loading
     * anything, to decide whether the load phase is already done - and on a freshly reset ledger
     * that threw {@code EmptyResultDataAccessException} instead of answering "no". Total in, total
     * out: no row is an answer, not an error.
     */
    public boolean isCreationClosed(String runId) {
        return jdbc.query("SELECT creation_closed FROM migration_run WHERE run_id = ?",
                        (rs, n) -> rs.getBoolean("creation_closed"), runId)
                .stream().findFirst().orElse(false);
    }

    /** Records a classification. Idempotent per (run, source line) so a rerun is safe. */
    @Transactional
    public void recordClassification(String runId, Classification c, LedgerState state) {
        jdbc.update("""
                INSERT INTO migration_ledger
                    (run_id, source_line, source_hash, case_ref, state, target_element,
                     reason_code, rule_ids, detail)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (run_id, source_line) DO UPDATE SET
                    state = EXCLUDED.state,
                    target_element = EXCLUDED.target_element,
                    reason_code = EXCLUDED.reason_code,
                    rule_ids = EXCLUDED.rule_ids,
                    detail = EXCLUDED.detail,
                    updated_at = now()
                """,
                runId,
                c.record().sourceLine(),
                c.record().sourceHash(),
                emptyToNull(c.record().caseRef()),
                state.name(),
                c.targetElementId(),
                c.reasonCode(),
                c.matchedRuleIds().isEmpty() ? null : String.join(",", c.matchedRuleIds()),
                c.targetVariables().get("detail") == null
                        ? null : String.valueOf(c.targetVariables().get("detail")));
    }

    /**
     * Writes CREATE intent BEFORE the engine is called.
     *
     * <p>This single ordering is why the ledger exists. If the process dies between this write and
     * the engine's response, the row sits at CREATE_PENDING with no instance key - and that is a
     * question the engine cannot answer for you, because a timed-out create may or may not have
     * landed. A rerun must refuse to auto-recreate on that row.
     */
    public void writeCreateIntent(String runId, int sourceLine) {
        transition(runId, sourceLine, LedgerState.CREATE_PENDING, null);
    }

    /** Current state of a row, or empty if this run has never seen that source line. */
    public java.util.Optional<LedgerState> stateOf(String runId, int sourceLine) {
        return jdbc.query(
                        "SELECT state FROM migration_ledger WHERE run_id = ? AND source_line = ?",
                        (rs, n) -> LedgerState.valueOf(rs.getString("state")), runId, sourceLine)
                .stream().findFirst();
    }

    public void confirmCreated(String runId, int sourceLine, String instanceKey) {
        jdbc.update("""
                UPDATE migration_ledger
                   SET state = ?, instance_key = ?, updated_at = now()
                 WHERE run_id = ? AND source_line = ?
                """, LedgerState.CREATED.name(), instanceKey, runId, sourceLine);
    }

    public void transition(String runId, int sourceLine, LedgerState state, String detail) {
        jdbc.update("""
                UPDATE migration_ledger
                   SET state = ?, detail = COALESCE(?, detail), updated_at = now()
                 WHERE run_id = ? AND source_line = ?
                """, state.name(), detail, runId, sourceLine);
    }

    public List<Row> rowsInState(String runId, LedgerState state) {
        return jdbc.query("""
                SELECT source_line, source_hash, case_ref, state, target_element, reason_code,
                       instance_key, detail
                  FROM migration_ledger
                 WHERE run_id = ? AND state = ?
                 ORDER BY source_line
                """,
                (rs, n) -> new Row(
                        rs.getInt("source_line"), rs.getString("source_hash"), rs.getString("case_ref"),
                        LedgerState.valueOf(rs.getString("state")), rs.getString("target_element"),
                        rs.getString("reason_code"), rs.getString("instance_key"), rs.getString("detail")),
                runId, state.name());
    }

    /** Counts per bucket. This is the left-hand side of the reconciliation identity. */
    public Map<LedgerState.Bucket, Integer> bucketCounts(String runId) {
        Map<LedgerState.Bucket, Integer> counts = new EnumMap<>(LedgerState.Bucket.class);
        for (LedgerState.Bucket b : LedgerState.Bucket.values()) {
            counts.put(b, 0);
        }
        jdbc.query("SELECT state, count(*) AS n FROM migration_ledger WHERE run_id = ? GROUP BY state",
                        (rs, n) -> Map.entry(LedgerState.valueOf(rs.getString("state")), rs.getInt("n")),
                        runId)
                .forEach(e -> counts.merge(e.getKey().bucket(), e.getValue(), Integer::sum));
        return counts;
    }

    /**
     * Cases holding more than one live instance within the run.
     *
     * <p>ONE aggregate query, not one per record. Run per-record against thousands of cases and
     * this becomes thousands of round-trips at the tensest moment of the cutover.
     *
     * <p>A backstop rather than the primary guard: the engine now refuses duplicate live instances
     * outright (businessId uniqueness). This catches anything that slipped past it, including rows
     * whose create outcome was never confirmed.
     */
    public Map<String, Integer> liveDuplicatesByCaseRef(String runId) {
        Map<String, Integer> dups = new LinkedHashMap<>();
        String states = LedgerState.LIVE_IN_ENGINE.stream()
                .map(s -> "'" + s.name() + "'").reduce((a, b) -> a + "," + b).orElseThrow();
        jdbc.query("SELECT case_ref, count(*) AS n FROM migration_ledger "
                        + " WHERE run_id = ? AND case_ref IS NOT NULL AND state IN (" + states + ")"
                        + " GROUP BY case_ref HAVING count(*) > 1",
                        (rs, n) -> Map.entry(rs.getString("case_ref"), rs.getInt("n")), runId)
                .forEach(e -> dups.put(e.getKey(), e.getValue()));
        return dups;
    }

    /** Every business key this run touched. Used by abort to find orphans the ledger lost. */
    public java.util.Set<String> caseRefsForRun(String runId) {
        return new java.util.HashSet<>(jdbc.query(
                "SELECT DISTINCT case_ref FROM migration_ledger WHERE run_id = ? AND case_ref IS NOT NULL",
                (rs, n) -> rs.getString("case_ref"), runId));
    }

    private static String emptyToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }

    public record Row(
            int sourceLine, String sourceHash, String caseRef, LedgerState state,
            String targetElement, String reasonCode, String instanceKey, String detail) {}
}
