package com.poc.migration.classify;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.poc.migration.model.ClassificationInput;
import com.poc.migration.model.ClassificationInput.VisionOutcome;
import io.camunda.client.CamundaClient;
import io.camunda.process.test.api.CamundaSpringProcessTest;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Criterion 9a: the decision table's coverage over its ENTIRE input domain.
 *
 * <p>Thirteen fixtures exercise a handful of combinations. This walks all
 * {@code 2^5 x 3 = 96} of them and asserts the exact partition:
 *
 * <pre>
 *   92 covered by exactly one rule
 *    4 covered by none   (deliberate: fee paid with no passed vision test)
 *    0 covered by two or more
 * </pre>
 *
 * <p>Why the multi-hit count matters as much as the others: two rules matching the same record is
 * how a table starts quietly disagreeing with itself. Under COLLECT nothing throws - the caller
 * just sees two answers and has to quarantine, which inflates the exception queue on the one night
 * it must be small.
 *
 * <p>Why the uncovered count is asserted rather than minimised: those four cells are business
 * impossibilities (a fee taken before any vision test passed). They MUST match nothing, so that
 * such a record quarantines as unmatched rather than being routed somewhere plausible.
 *
 * <p>This runs against a throwaway engine, so it proves the deployed table behaves this way -
 * not that a model of it does.
 */
@SpringBootTest(properties = {
        "camunda.client.worker.defaults.enabled=false",
        // No ledger in this test: the decision table creates nothing and touches no database.
        // Spring Boot 4 moved these out of org.springframework.boot.autoconfigure.jdbc.
        // Boot ignores an exclude naming a class that is not on the classpath, so the old
        // names were silently no-ops and this test built a real DataSource and demanded a
        // Postgres it does not use.
        "spring.autoconfigure.exclude="
                + "org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration,"
                + "org.springframework.boot.jdbc.autoconfigure.DataSourceTransactionManagerAutoConfiguration,"
                + "org.springframework.boot.jdbc.autoconfigure.DataSourceInitializationAutoConfiguration"
})
@CamundaSpringProcessTest
class DecisionTableDomainTest {

    private static final Path DMN = Path.of("..", "processes", "migration-classification.dmn");

    @Autowired
    private CamundaClient client;

    private DmnClassifier classifier;

    @BeforeEach
    void deployTable() {
        client.newDeployResourceCommand()
                .addResourceFile(DMN.toAbsolutePath().normalize().toString())
                .send()
                .join();
        classifier = new DmnClassifier(client, "migration-classification");
    }

    @Test
    @DisplayName("96-cell domain partitions exactly 92 covered / 4 uncovered / 0 multi-hit")
    void domainPartition() {
        List<ClassificationInput> uncovered = new ArrayList<>();
        Map<ClassificationInput, List<String>> multiHit = new LinkedHashMap<>();
        int covered = 0;

        for (ClassificationInput input : wholeDomain()) {
            List<String> ruleIds = classifier.matchedRuleIds(input);
            if (ruleIds.isEmpty()) {
                uncovered.add(input);
            } else if (ruleIds.size() > 1) {
                multiHit.put(input, ruleIds);
            } else {
                covered++;
            }
        }

        assertThat(multiHit)
                .as("no record may match two rules; each entry is a cell where the table "
                        + "disagrees with itself")
                .isEmpty();
        assertThat(covered).as("cells matched by exactly one rule").isEqualTo(92);
        assertThat(uncovered).as("cells matched by no rule").hasSize(4);

        // And they must be the FOUR WE MEANT, not four others that happen to add up. A gap that
        // moved would be a live case falling through, which the count alone would not reveal.
        assertThat(uncovered).allSatisfy(input -> {
            assertThat(input.feePaid()).isTrue();
            assertThat(input.visionOutcome()).isIn(VisionOutcome.NONE, VisionOutcome.FAIL);
            assertThat(input.hasDateCompleted()).isFalse();
            assertThat(input.hasIssued()).isFalse();
            assertThat(input.withinCutoff()).isTrue();
        });
    }

    @Test
    @DisplayName("a non-matching evaluation returns no matched rules, whatever the top-level says")
    void nonMatchingReturnsNoRules() {
        // The top-level output of a non-matching COLLECT evaluation is the STRING "null". Code
        // that branches on it treats "nothing matched" as a value that matched, so the classifier
        // reads matchedRules instead. This pins that behaviour to the deployed table.
        var impossible = new ClassificationInput(false, false, false, true, true, VisionOutcome.NONE);

        assertThat(classifier.matchedRuleIds(impossible)).isEmpty();
    }

    /** All 2^5 x 3 combinations of the six total inputs. */
    private static List<ClassificationInput> wholeDomain() {
        List<ClassificationInput> all = new ArrayList<>(96);
        for (boolean completed : new boolean[] {false, true}) {
            for (boolean issued : new boolean[] {false, true}) {
                for (boolean expired : new boolean[] {false, true}) {
                    for (boolean withinCutoff : new boolean[] {false, true}) {
                        for (boolean feePaid : new boolean[] {false, true}) {
                            for (VisionOutcome vision : VisionOutcome.values()) {
                                all.add(new ClassificationInput(
                                        completed, issued, expired, withinCutoff, feePaid, vision));
                            }
                        }
                    }
                }
            }
        }
        return all;
    }

    /**
     * The decision table creates nothing and touches no database, but the application's
     * component scan still builds {@code Ledger} and everything downstream of it, and
     * {@code Ledger} takes a {@link JdbcTemplate}. The excludes on the class remove the real
     * DataSource; this supplies the one bean those components need so no Postgres has to be
     * running. Nothing in this test should ever call it.
     */
    @TestConfiguration
    static class NoDatabase {

        @Bean
        JdbcTemplate jdbcTemplate() {
            return mock(JdbcTemplate.class);
        }
    }
}
