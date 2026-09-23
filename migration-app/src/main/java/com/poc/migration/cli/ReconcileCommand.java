package com.poc.migration.cli;

import com.poc.migration.extract.Manifest;
import com.poc.migration.reconcile.Reconciler;
import java.nio.file.Path;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.ExitCodeGenerator;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * {@code reconcile <manifest.csv> [--release|--abort]}
 *
 * <p>With no flag it is read-only: close creation, drain, count, report. Releasing and aborting
 * are separate, explicit acts - the gate reports, a human decides.
 */
@Component
@Order(30)
public class ReconcileCommand implements ApplicationRunner, ExitCodeGenerator {

    private final Reconciler reconciler;
    private int exitCode = 0;

    public ReconcileCommand(Reconciler reconciler) {
        this.reconciler = reconciler;
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        var positional = args.getNonOptionArgs();
        if (positional.isEmpty() || !positional.get(0).equals("reconcile")) {
            return;
        }
        if (positional.size() < 2) {
            System.err.println("usage: reconcile <manifest.csv> [--release|--abort]");
            exitCode = 2;
            return;
        }

        Manifest manifest = Manifest.read(Path.of(positional.get(1)));
        boolean doRelease = args.containsOption("release");
        boolean doAbort = args.containsOption("abort");

        Reports.gateHeader(manifest.runId());
        Reconciler.GateResult gate = reconciler.gate(manifest);
        boolean passed = Reports.gate(gate);
        if (!passed) {
            exitCode = 1;
        }

        if (doAbort) {
            System.out.println();
            try {
                Reconciler.AbortResult a = reconciler.abort(manifest.runId());
                System.out.printf("  ABORT: confirmed gone %d, unconfirmed %d, orphans found %d%n",
                        a.cancelled(), a.unconfirmed(), a.orphansFound());
                if (!a.clean()) {
                    System.out.println("  STILL ACTIVE after cancellation: " + a.stillActive());
                    System.out.println("  These are NOT marked aborted. An abort that reports success");
                    System.out.println("  on a running instance is worse than one that reports failure.");
                    exitCode = 1;
                }
            } catch (IllegalStateException e) {
                System.out.println("  ABORT REFUSED: " + e.getMessage());
                exitCode = 1;
            }
        } else if (doRelease) {
            System.out.println();
            if (!passed) {
                Reports.releaseRefused();
                exitCode = 1;
            } else if (!Reports.release(reconciler.release(manifest.runId()))) {
                exitCode = 1;
            }
        } else {
            System.out.println();
            System.out.println("  Read-only. Pass --release or --abort to act.");
        }

        System.out.println("=".repeat(62));
    }

    @Override
    public int getExitCode() {
        return exitCode;
    }
}
