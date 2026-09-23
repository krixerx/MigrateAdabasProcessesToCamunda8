package com.poc.migration.extract;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * An Adabas export flattens periodic groups and multiple-value fields into numbered columns, and
 * how many there are is a property of the export, not a schema. Hard-code the count and a fourth
 * occurrence is silently dropped - and a dropped vision test changes where a case belongs.
 */
class AdabasCsvReaderTest {

    @TempDir
    Path tmp;

    private final AdabasCsvReader reader = new AdabasCsvReader();

    private Path write(String content) throws IOException {
        Path p = tmp.resolve("export.csv");
        Files.writeString(p, content, StandardCharsets.UTF_8);
        return p;
    }

    @Test
    @DisplayName("occurrence columns are discovered from the header, whatever the count")
    void discoversOccurrenceColumns() throws IOException {
        Path csv = write("""
                CASE_REF,VT_1_DATE,VT_1_RESULT,VT_2_DATE,VT_2_RESULT,VT_3_DATE,VT_3_RESULT,VT_4_DATE,VT_4_RESULT,RESTR_1,RESTR_2
                LP-1,2026-01-01,FAIL,2026-02-01,FAIL,2026-03-01,FAIL,2026-04-01,PASS,B,C
                """);

        var result = reader.read(csv);

        assertThat(result.visionColumnCount()).isEqualTo(4);
        assertThat(result.restrictionColumnCount()).isEqualTo(2);
        assertThat(result.records().get(0).visionTests()).hasSize(4);
        assertThat(result.records().get(0).restrictions()).containsExactly("B", "C");
    }

    @Test
    @DisplayName("an occurrence with neither date nor result is padding, not data")
    void emptyOccurrenceIsNotData() throws IOException {
        Path csv = write("""
                CASE_REF,VT_1_DATE,VT_1_RESULT,VT_2_DATE,VT_2_RESULT,VT_3_DATE,VT_3_RESULT
                LP-1,2026-01-01,PASS,,,,
                """);

        assertThat(reader.read(csv).records().get(0).visionTests()).hasSize(1);
    }

    @Test
    @DisplayName("sparse occurrences are read by their number, not their position")
    void sparseOccurrences() throws IOException {
        // Occurrence 1 empty, occurrence 2 populated. Reading positionally would mis-pair the
        // date with the wrong result.
        Path csv = write("""
                CASE_REF,VT_1_DATE,VT_1_RESULT,VT_2_DATE,VT_2_RESULT
                LP-1,,,2026-02-01,PASS
                """);

        var tests = reader.read(csv).records().get(0).visionTests();
        assertThat(tests).hasSize(1);
        assertThat(tests.get(0).occurrence()).isEqualTo(2);
        assertThat(tests.get(0).result()).isEqualTo("PASS");
    }

    @Test
    @DisplayName("source line numbering treats the header as line 1")
    void sourceLineNumbering() throws IOException {
        // An off-by-one here made every manifest hash lookup miss. The hash check caught it, which
        // is the check working, but the numbering is worth pinning.
        Path csv = write("""
                CASE_REF
                LP-1
                LP-2
                """);

        var records = reader.read(csv).records();
        assertThat(records.get(0).sourceLine()).isEqualTo(2);
        assertThat(records.get(1).sourceLine()).isEqualTo(3);
    }

    @Test
    @DisplayName("the hash covers the raw line and is stable across a trailing CR")
    void hashIsOverTheRawLineAndCrInsensitive() throws IOException {
        // Row hashes must survive a checkout that changed line endings, or every manifest written
        // on one machine would be rejected on another.
        var unix = reader.read(write("CASE_REF\nLP-1\n")).records().get(0);
        var windows = reader.read(write("CASE_REF\r\nLP-1\r\n")).records().get(0);

        assertThat(unix.sourceHash()).isEqualTo(windows.sourceHash());
    }

    @Test
    @DisplayName("the header is hashed separately, so a renamed column is detectable")
    void headerHashChangesWhenAColumnIsRenamed() throws IOException {
        // Row hashes cover values, not column names. Rename a column and classification changes
        // while every row hash still matches - which is why the header is bound to the run too.
        String before = reader.read(write("CASE_REF,FEE_PAID\nLP-1,TRUE\n")).headerHash();
        String after = reader.read(write("CASE_REF,FEE_SETTLED\nLP-1,TRUE\n")).headerHash();

        assertThat(before).isNotEqualTo(after);
    }

    @Test
    @DisplayName("ISN is read but is never the business key")
    void isnIsAuditOnly() throws IOException {
        // An ISN is a physical record address that changes across ADAULD/ADALOD and file
        // reorganisation. Using it as a key would silently re-identify every record after a reorg.
        Path csv = write("""
                CASE_REF,ISN
                LP-1,8891
                """);

        var r = reader.read(csv).records().get(0);
        assertThat(r.caseRef()).isEqualTo("LP-1");
        assertThat(r.isn()).isEqualTo("8891");
        assertThat(r.sourceIdentity()).doesNotContain("8891");
    }
}
