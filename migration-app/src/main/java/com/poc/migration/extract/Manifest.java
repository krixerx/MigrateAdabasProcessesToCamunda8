package com.poc.migration.extract;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The freeze manifest. It is the run's control total AND its interpretation contract.
 *
 * <p><b>Why a manifest at all.</b> Without an independent record count and per-record hash, the
 * "source count" in reconciliation is simply whatever extract produced. If extract silently drops
 * rows, the identity still balances perfectly and the gate passes while records have vanished.
 * That is the exact failure a reconciliation gate exists to catch, so the total cannot come from
 * the thing being checked.
 *
 * <p><b>Why the header is hashed too.</b> Row hashes cover values, not column names. Rename a
 * column and classification changes while every row hash still matches. The header hash binds the
 * run to the source schema.
 *
 * <p><b>Why freezeDate lives here and not in the clock.</b> {@code withinCutoff} is computed
 * against the freeze date. Read it from the clock and a rerun on a later day silently reclassifies
 * records that sat near the boundary - the same input producing a different answer, which is the
 * one thing a migration must never do.
 */
public record Manifest(
        LocalDate freezeDate,
        int cutoffMonths,
        String runId,
        int recordCount,
        String headerHash,
        Map<Integer, String> hashBySourceLine) {

    /** The cutoff boundary. Records last updated before this are outside the agreed scope. */
    public LocalDate cutoffBoundary() {
        return freezeDate.minusMonths(cutoffMonths);
    }

    public static Manifest read(Path path) throws IOException {
        List<String> lines = Files.readAllLines(path, StandardCharsets.UTF_8);

        LocalDate freezeDate = null;
        Integer cutoffMonths = null;
        String runId = null;
        Integer recordCount = null;
        String headerHash = null;
        Map<Integer, String> hashes = new HashMap<>();
        boolean inRows = false;

        for (String line : lines) {
            if (line.isBlank() || line.startsWith("#")) {
                continue;
            }
            if (line.startsWith("SOURCE_LINE,")) {
                inRows = true;
                continue;
            }
            if (!inRows) {
                int eq = line.indexOf('=');
                if (eq < 0) {
                    continue;
                }
                String key = line.substring(0, eq).trim();
                String value = line.substring(eq + 1).trim();
                switch (key) {
                    case "freezeDate" -> freezeDate = LocalDate.parse(value);
                    case "cutoffMonths" -> cutoffMonths = Integer.parseInt(value);
                    case "runId" -> runId = value;
                    case "recordCount" -> recordCount = Integer.parseInt(value);
                    case "headerHash" -> headerHash = value;
                    default -> { /* forward compatible: unknown keys ignored */ }
                }
            } else {
                String[] parts = line.split(",", -1);
                if (parts.length >= 3) {
                    hashes.put(Integer.parseInt(parts[0].trim()), parts[2].trim());
                }
            }
        }

        // A manifest missing any of these cannot bind the run. Halt rather than default:
        // every default here would silently change what the run means.
        require(freezeDate != null, "manifest: freezeDate missing");
        require(cutoffMonths != null, "manifest: cutoffMonths missing");
        require(runId != null && !runId.isBlank(), "manifest: runId missing");
        require(recordCount != null, "manifest: recordCount missing");
        require(headerHash != null && !headerHash.isBlank(), "manifest: headerHash missing");
        require(hashes.size() == recordCount,
                "manifest: recordCount says " + recordCount + " but " + hashes.size() + " row hashes present");

        return new Manifest(freezeDate, cutoffMonths, runId, recordCount, headerHash, Map.copyOf(hashes));
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalStateException(message);
        }
    }
}
