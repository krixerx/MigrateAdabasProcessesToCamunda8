package com.poc.migration.cli;

import com.poc.migration.ledger.Ledger;
import com.poc.migration.load.Loader;
import com.poc.migration.pipeline.DryRun;
import java.nio.file.Path;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.ExitCodeGenerator;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * {@code load <export.csv> <manifest.csv>} - classify, then create HELD instances.
 *
 * <p>Creates nothing that anyone can work on. Every instance parks at the start event's migrator
 * listener; no user task exists until reconcile releases it. Nothing here releases anything, and
 * this application deliberately runs no job worker, so nothing can release by accident.
 */
@Component
@Order(20)
public class LoadCommand implements ApplicationRunner, ExitCodeGenerator {

    private final DryRun dryRun;
    private final Loader loader;
    private final Ledger ledger;
    private int exitCode = 0;

    public LoadCommand(DryRun dryRun, Loader loader, Ledger ledger) {
        this.dryRun = dryRun;
        this.loader = loader;
        this.ledger = ledger;
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        var positional = args.getNonOptionArgs();
        if (positional.isEmpty() || !positional.get(0).equals("load")) {
            return;
        }
        if (positional.size() < 3) {
            System.err.println("usage: load <export.csv> <manifest.csv>");
            exitCode = 2;
            return;
        }

        DryRun.Report report = dryRun.run(Path.of(positional.get(1)), Path.of(positional.get(2)));
        Loader.Result result = loader.load(report);

        if (!Reports.load(result, report, ledger)) {
            exitCode = 1;
        }
    }

    @Override
    public int getExitCode() {
        return exitCode;
    }
}
