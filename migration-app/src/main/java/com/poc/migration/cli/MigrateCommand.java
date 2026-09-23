package com.poc.migration.cli;

import com.poc.migration.ledger.Ledger;
import com.poc.migration.load.Loader;
import com.poc.migration.pipeline.DryRun;
import com.poc.migration.reconcile.Reconciler;
import java.nio.file.Path;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.ExitCodeGenerator;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * {@code migrate <export.csv> <manifest.csv> [expected-outcomes.csv] [--dry-run] [--release]} -
 * classify, load, gate, in one process.
 *
 * <p>The phase commands stay; this runs the same objects in the same order so an operator does not
 * have to re-type three invocations and keep their arguments consistent between them. It also
 * classifies <b>once</b> and hands the one report to the loader, where running {@code classify}
 * then {@code load} evaluates the DMN twice and writes two sets of decision-evaluation records for
 * the same population.
 *
 * <p>Two properties are deliberately not collapsed along with the commands:
 *
 * <ul>
 *   <li><b>{@code --release} stays opt-in.</b> Without it this stops at the gate verdict, which is
 *       where a human decides today. With it, release still goes through {@link Reconciler} and is
 *       still refused when the gate fails - the chain gets no route around the gate.
 *   <li><b>A failed expectation check halts before the load.</b> Run by hand, a mismatching
 *       {@code classify} exits 1 and the operator simply does not type {@code load}. Chained, that
 *       decision has to be made in code or the chain would create instances from a population it
 *       just reported as wrong.
 * </ul>
 *
 * <p><b>Rerunnable.</b> Run it again and it resumes rather than starting over: the loader is
 * idempotent, and once creation is closed the load phase is skipped entirely and the chain
 * continues to the gate. {@code migrate --release} on a run that already loaded therefore does
 * what you meant, which is the point of having one command.
 *
 * <p>{@code --abort} is not accepted here. Abort is not a step in the happy path; it undoes one,
 * and it belongs on {@code reconcile} where nothing else runs alongside it.
 */
@Component
@Order(5)
public class MigrateCommand implements ApplicationRunner, ExitCodeGenerator {

    private final DryRun dryRun;
    private final Loader loader;
    private final Ledger ledger;
    private final Reconciler reconciler;
    private int exitCode = 0;

    public MigrateCommand(DryRun dryRun, Loader loader, Ledger ledger, Reconciler reconciler) {
        this.dryRun = dryRun;
        this.loader = loader;
        this.ledger = ledger;
        this.reconciler = reconciler;
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        var positional = args.getNonOptionArgs();
        if (positional.isEmpty() || !positional.get(0).equals("migrate")) {
            return;
        }
        if (positional.size() < 3) {
            System.err.println("usage: migrate <export.csv> <manifest.csv> [expected-outcomes.csv] "
                    + "[--dry-run] [--release]");
            exitCode = 2;
            return;
        }

        boolean dryRunOnly = args.containsOption("dry-run");
        boolean doRelease = args.containsOption("release");
        if (dryRunOnly && doRelease) {
            System.err.println("migrate: --dry-run and --release contradict each other. A dry run "
                    + "creates nothing, so there is nothing to release.");
            exitCode = 2;
            return;
        }
        if (args.containsOption("abort")) {
            System.err.println("migrate: --abort is not a migrate flag. Use: "
                    + "reconcile <manifest.csv> --abort");
            exitCode = 2;
            return;
        }

        // 1. Classify. One evaluation, reused by every phase below.
        DryRun.Report report = dryRun.run(Path.of(positional.get(1)), Path.of(positional.get(2)));
        Reports.classification(report, dryRunOnly
                ? "DRY RUN - no process instances created"
                : "CLASSIFY - nothing created yet");

        if (positional.size() >= 4 && !Reports.expectations(report, Path.of(positional.get(3)))) {
            exitCode = 1;
            System.out.println();
            System.out.println("  HALTED before load: the population does not match the expected");
            System.out.println("  outcomes. Nothing was created.");
            return;
        }

        if (dryRunOnly) {
            System.out.println();
            System.out.println("  Dry run. Stopped before load: no instance, no ledger row, no");
            System.out.println("  user task. Decision evaluations in Operate are the audit trail.");
            return;
        }

        // 2. Load into holding. Idempotent; a rerun leaves existing instances untouched.
        //
        // Creation closes once a gate has counted the run, and the loader refuses to add members
        // after that - correctly, because the gate authorised a membership it had already frozen.
        // That is not a reason to refuse the REST of the chain: resuming a run whose load is
        // finished is how you finish a cutover that stopped at the gate, and it is the whole
        // reason to have one command. So skip the phase that is already done and go on to the
        // gate, which is the arbiter either way.
        if (ledger.isCreationClosed(report.manifest().runId())) {
            System.out.println();
            System.out.println("LOAD - already done");
            System.out.println(Reports.RULE);
            System.out.println("  Creation is closed for this run: the gate has counted it. Nothing");
            System.out.println("  is created or re-created; resuming at the gate.");
            System.out.println(Reports.RULE);
        } else if (!Reports.load(loader.load(report), report, ledger)) {
            exitCode = 1;
        }

        // 3. Gate. Runs even when the load reported duplicates - the gate is what says out loud
        //    why release is refused, and suppressing it here would hide that reason.
        Reports.gateHeader(report.manifest().runId());
        boolean passed = Reports.gate(reconciler.gate(report.manifest()));
        if (!passed) {
            exitCode = 1;
        }

        System.out.println();
        if (!doRelease) {
            System.out.println("  Stopped at the gate. Nothing is released; no user task exists.");
            System.out.println("  Pass --release to act on a PASS, or run: reconcile <manifest.csv>");
        } else if (!passed) {
            Reports.releaseRefused();
            exitCode = 1;
        } else if (!Reports.release(reconciler.release(report.manifest().runId()))) {
            exitCode = 1;
        }
        System.out.println(Reports.RULE);
    }

    @Override
    public int getExitCode() {
        return exitCode;
    }
}
