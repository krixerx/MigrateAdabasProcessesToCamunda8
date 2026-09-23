package com.poc.migration.extract;

import com.poc.migration.model.SourceRecord;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads an Adabas CSV export into raw {@link SourceRecord}s. Interprets nothing.
 *
 * <p><b>Occurrence columns are discovered, never assumed.</b> An Adabas export flattens a periodic
 * group and a multiple-value field into numbered columns, and how many there are is a property of
 * the export, not a schema:
 *
 * <pre>
 *   VT_1_DATE, VT_1_RESULT, VT_2_DATE, VT_2_RESULT, ...   periodic group VISION-TEST
 *   RESTR_1, RESTR_2, RESTR_3, ...                        multiple-value field RESTRICTION
 * </pre>
 *
 * The header is scanned for these patterns. Hard-coding three vision-test columns would mean a
 * fourth occurrence is silently dropped, and silently dropping a vision test changes where a case
 * belongs.
 *
 * <p><b>Truncation is data loss and must be visible.</b> This reader cannot detect occurrences that
 * were cut off before the CSV was written - the columns simply are not there. Detecting that needs
 * an independent occurrence count in the export, which the real extract must carry. Recorded as a
 * known limit rather than pretended away.
 *
 * <p><b>The hash covers the raw line.</b> Byte-exact, canonicalised only by stripping a trailing
 * CR. Deliberately not the normalised record: a change to normalisation must not invalidate every
 * manifest ever written.
 */
public class AdabasCsvReader {

    private static final Pattern VT_DATE = Pattern.compile("^VT_(\\d+)_DATE$");
    private static final Pattern VT_RESULT = Pattern.compile("^VT_(\\d+)_RESULT$");
    private static final Pattern RESTR = Pattern.compile("^RESTR_(\\d+)$");

    /** Reads every record. Blank lines are skipped but still consume a line number. */
    public Result read(Path csv) throws IOException {
        List<String> rawLines = Files.readAllLines(csv, StandardCharsets.UTF_8);
        if (rawLines.isEmpty()) {
            throw new IllegalStateException("export is empty: " + csv);
        }

        String headerLine = stripTrailingCr(rawLines.get(0));
        String[] header = headerLine.split(",", -1);
        Map<String, Integer> col = new LinkedHashMap<>();
        for (int i = 0; i < header.length; i++) {
            col.put(header[i].trim(), i);
        }

        TreeSet<Integer> visionOccurrences = new TreeSet<>();
        TreeSet<Integer> restrictionOccurrences = new TreeSet<>();
        for (String name : col.keySet()) {
            Matcher d = VT_DATE.matcher(name);
            if (d.matches()) {
                visionOccurrences.add(Integer.parseInt(d.group(1)));
            }
            Matcher r = VT_RESULT.matcher(name);
            if (r.matches()) {
                visionOccurrences.add(Integer.parseInt(r.group(1)));
            }
            Matcher m = RESTR.matcher(name);
            if (m.matches()) {
                restrictionOccurrences.add(Integer.parseInt(m.group(1)));
            }
        }

        List<SourceRecord> records = new ArrayList<>();
        for (int i = 1; i < rawLines.size(); i++) {
            String raw = stripTrailingCr(rawLines.get(i));
            if (raw.isBlank()) {
                continue;
            }
            int sourceLine = i + 1; // header is line 1
            String[] f = raw.split(",", -1);

            List<SourceRecord.VisionTest> visionTests = new ArrayList<>();
            for (int occ : visionOccurrences) {
                String date = get(f, col, "VT_" + occ + "_DATE");
                String result = get(f, col, "VT_" + occ + "_RESULT");
                // An occurrence with neither value was never present; it is padding, not data.
                if (!date.isBlank() || !result.isBlank()) {
                    visionTests.add(new SourceRecord.VisionTest(occ, date, result));
                }
            }

            List<String> restrictions = new ArrayList<>();
            for (int occ : restrictionOccurrences) {
                String v = get(f, col, "RESTR_" + occ);
                if (!v.isBlank()) {
                    restrictions.add(v);
                }
            }

            records.add(new SourceRecord(
                    sourceLine,
                    sha256(raw),
                    get(f, col, "CASE_REF"),
                    get(f, col, "ISN"),
                    get(f, col, "APPLICANT_USER_ID"),
                    get(f, col, "APPLICANT_NAME"),
                    get(f, col, "PERMIT_CATEGORY"),
                    get(f, col, "FEE_AMOUNT"),
                    get(f, col, "LAST_UPDATED"),
                    get(f, col, "FEE_PAID"),
                    List.copyOf(visionTests),
                    List.copyOf(restrictions),
                    get(f, col, "ISSUED"),
                    get(f, col, "EXPIRED"),
                    get(f, col, "DATE_COMPLETED")));
        }

        return new Result(sha256(headerLine), List.copyOf(records),
                visionOccurrences.size(), restrictionOccurrences.size());
    }

    private static String get(String[] fields, Map<String, Integer> col, String name) {
        Integer idx = col.get(name);
        if (idx == null || idx >= fields.length) {
            return "";
        }
        return fields[idx].trim();
    }

    private static String stripTrailingCr(String s) {
        return s.endsWith("\r") ? s.substring(0, s.length() - 1) : s;
    }

    static String sha256(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return "sha256:" + HexFormat.of().formatHex(md.digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    /**
     * @param visionColumnCount      how many VT occurrence slots the header declared
     * @param restrictionColumnCount how many RESTR occurrence slots the header declared
     */
    public record Result(
            String headerHash,
            List<SourceRecord> records,
            int visionColumnCount,
            int restrictionColumnCount) {}
}
