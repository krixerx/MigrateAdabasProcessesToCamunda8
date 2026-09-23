package com.poc.migration.cli;

import com.poc.migration.pipeline.DryRun;
import java.nio.file.Path;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.ExitCodeGenerator;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * {@code classify <export.csv> <manifest.csv> [expected-outcomes.csv]} - classify the whole
 * population, create nothing.
 *
 * <p>The report is the POC's primary sponsor-facing artifact. It deliberately ends with the
 * unmatched combinations rather than a single total: a count says how big the problem is, the
 * combinations say what it actually is, and only the second kind is something a business analyst
 * can act on.
 *
 * <p>{@code migrate --dry-run} runs the same phase; see {@link MigrateCommand}.
 */
@Component
@Order(10)
public class DryRunCommand implements ApplicationRunner, ExitCodeGenerator {

    private final DryRun dryRun;
    private int exitCode = 0;

    public DryRunCommand(DryRun dryRun) {
        this.dryRun = dryRun;
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        var positional = args.getNonOptionArgs();
        if (positional.isEmpty() || !positional.get(0).equals("classify")) {
            // Another entry point will claim it, or nothing will. Not an error here.
            return;
        }
        if (positional.size() < 3) {
            System.err.println("usage: classify <export.csv> <manifest.csv> [expected-outcomes.csv]");
            exitCode = 2;
            return;
        }

        DryRun.Report report = dryRun.run(Path.of(positional.get(1)), Path.of(positional.get(2)));
        Reports.classification(report, "DRY RUN - no process instances created");

        // Optional 4th argument: hand-written expectations. When present this stops being a
        // report and becomes a gate.
        if (positional.size() >= 4 && !Reports.expectations(report, Path.of(positional.get(3)))) {
            exitCode = 1;
        }
        // A dry run that found nothing wrong still exits 0; quarantine is an outcome, not a
        // failure. Only an unusable source halts, and that throws.
    }

    @Override
    public int getExitCode() {
        return exitCode;
    }
}
