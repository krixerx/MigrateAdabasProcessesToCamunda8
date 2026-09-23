package com.poc.migration.pipeline;

import com.poc.migration.model.Classification;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Compares what the pipeline decided against what a human wrote down beforehand.
 *
 * <p>This is criterion 2, and the discipline behind it is the whole point: {@code
 * expected-outcomes.csv} was written from the business rules <em>before the decision table
 * existed</em>. Generate expectations from the table and the check only proves the table agrees
 * with itself, which is worth nothing.
 *
 * <p>Keyed by {@code SOURCE_LINE}, never by case reference. One fixture deliberately has no case
 * reference at all - it cannot be keyed by a value it does not have, and it still has to be
 * counted.
 */
public class ExpectationCheck {

    public record Expectation(
            int sourceLine, String caseRef, String outcome, String targetElementId, String reasonCode) {}

    public record Mismatch(int sourceLine, String caseRef, String field, String expected, String actual) {}

    public record Result(int checked, List<Mismatch> mismatches) {
        public boolean passed() {
            return mismatches.isEmpty();
        }
    }

    public static Map<Integer, Expectation> read(Path path) throws IOException {
        Map<Integer, Expectation> out = new LinkedHashMap<>();
        for (String line : Files.readAllLines(path, StandardCharsets.UTF_8)) {
            if (line.isBlank() || line.startsWith("#") || line.startsWith("SOURCE_LINE,")) {
                continue;
            }
            String[] f = line.split(",", -1);
            int sourceLine = Integer.parseInt(f[0].trim());
            out.put(sourceLine, new Expectation(
                    sourceLine,
                    blankToNull(f[1]),
                    f[2].trim(),
                    blankToNull(f[3]),
                    blankToNull(f[4])));
        }
        return out;
    }

    public Result check(DryRun.Report report, Map<Integer, Expectation> expectations) {
        List<Mismatch> mismatches = new ArrayList<>();
        int checked = 0;

        for (Classification c : report.classifications()) {
            int line = c.record().sourceLine();
            Expectation e = expectations.get(line);
            if (e == null) {
                mismatches.add(new Mismatch(line, c.record().caseRef(),
                        "expectation", "a row for source line " + line, "none"));
                continue;
            }
            checked++;
            compare(mismatches, line, e.caseRef(), "outcome", e.outcome(), c.outcome().name());
            compare(mismatches, line, e.caseRef(), "targetElementId", e.targetElementId(), c.targetElementId());
            compare(mismatches, line, e.caseRef(), "reasonCode", e.reasonCode(), c.reasonCode());
        }

        for (Integer line : expectations.keySet()) {
            boolean seen = report.classifications().stream()
                    .anyMatch(c -> c.record().sourceLine() == line);
            if (!seen) {
                mismatches.add(new Mismatch(line, expectations.get(line).caseRef(),
                        "record", "a record at source line " + line, "none"));
            }
        }

        return new Result(checked, List.copyOf(mismatches));
    }

    private static void compare(
            List<Mismatch> out, int line, String caseRef, String field, String expected, String actual) {
        if (!Objects.equals(expected, actual)) {
            out.add(new Mismatch(line, caseRef, field, String.valueOf(expected), String.valueOf(actual)));
        }
    }

    private static String blankToNull(String s) {
        String t = s == null ? "" : s.trim();
        return t.isEmpty() ? null : t;
    }
}
