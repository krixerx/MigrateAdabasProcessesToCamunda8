package com.poc.migration.cli;

import com.poc.migration.ledger.Ledger;
import com.poc.migration.ledger.LedgerState;
import com.poc.migration.load.Loader;
import com.poc.migration.model.Classification;
import com.poc.migration.pipeline.DryRun;
import com.poc.migration.pipeline.ExpectationCheck;
import com.poc.migration.reconcile.Reconciler;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;

/**
 * The console blocks, shared by the single-phase entry points and by {@code migrate}.
 *
 * <p>These live here so a chained run prints exactly what the phase run prints. The demo is read
 * off the terminal by people comparing runs; two copies of this formatting would drift and the
 * audience would be told the difference is meaningful.
 *
 * <p>Each method that can find something wrong returns a boolean rather than setting an exit code:
 * the caller owns the exit code, because in a chain a bad phase also decides whether the next one
 * runs at all.
 */
final class Reports {

    static final String RULE = "=".repeat(62);

    private Reports() {}

    /**
     * The classification block. The heading is the caller's because the same numbers mean
     * different things: in a dry run nothing will be created, in a chained run nothing has been
     * created <i>yet</i>.
     */
    static void classification(DryRun.Report r, String heading) {
        var m = r.manifest();
        System.out.println();
        System.out.println(heading);
        System.out.println(RULE);
        System.out.printf("  runId          %s%n", m.runId());
        System.out.printf("  freezeDate     %s   (cutoff boundary %s, %d months)%n",
                m.freezeDate(), m.cutoffBoundary(), m.cutoffMonths());
        System.out.printf("  source records %d   (manifest says %d)%n", r.sourceCount(), m.recordCount());
        System.out.printf("  occurrence cols vision=%d restriction=%d   (discovered, not assumed)%n",
                r.visionColumnCount(), r.restrictionColumnCount());
        System.out.println();

        System.out.println("  OUTCOME");
        for (Classification.Outcome o : Classification.Outcome.values()) {
            System.out.printf("    %-12s %4d%n", o, r.byOutcome().getOrDefault(o, 0));
        }

        if (!r.byTargetElement().isEmpty()) {
            System.out.println();
            System.out.println("  MIGRATE TARGETS");
            r.byTargetElement().forEach((k, v) -> System.out.printf("    %-22s %4d%n", k, v));
        }

        System.out.println();
        System.out.println("  REASON CODES");
        r.byReasonCode().forEach((k, v) -> System.out.printf("    %-24s %4d%n", k, v));
        if (r.redundantRuleWarnings() > 0) {
            System.out.printf("    %-24s %4d  (proceeded; rules agreed)%n",
                    "REDUNDANT_RULE (warning)", r.redundantRuleWarnings());
        }

        System.out.println();
        if (r.unmatchedByCombination().isEmpty()) {
            System.out.println("  UNMATCHED COMBINATIONS: none");
        } else {
            System.out.println("  UNMATCHED COMBINATIONS  (the input to the improvement loop)");
            System.out.println("    key legend: C=completed I=issued E=expired W=withinCutoff F=feePaid");
            System.out.println("                uppercase true, lowercase false");
            r.unmatchedByCombination().entrySet().stream()
                    .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                    .forEach(e -> System.out.printf("    %-20s %4d%n", e.getKey(), e.getValue()));
        }
        System.out.println();
        System.out.println("  This run proves the pipeline. It says nothing about the real file:");
        System.out.println("  the data is invented and the expectations are hand-written.");
        System.out.println(RULE);
    }

    /**
     * The hand-written expectations block. Returns false on any mismatch, which is the one
     * classification result that must stop a chained run before it creates anything.
     */
    static boolean expectations(DryRun.Report report, Path expectedCsv) throws IOException {
        var expectations = ExpectationCheck.read(expectedCsv);
        var result = new ExpectationCheck().check(report, expectations);
        System.out.println();
        if (result.passed()) {
            System.out.printf("  EXPECTATIONS: %d/%d match%n", result.checked(), expectations.size());
            System.out.println("  Written from the business rules BEFORE the decision table existed.");
            System.out.println(RULE);
            return true;
        }
        System.out.printf("  EXPECTATIONS: %d MISMATCHES%n", result.mismatches().size());
        result.mismatches().forEach(m -> System.out.printf(
                "    line %-3d %-16s %-16s expected=%-22s actual=%s%n",
                m.sourceLine(), m.caseRef() == null ? "(no case ref)" : m.caseRef(),
                m.field(), m.expected(), m.actual()));
        System.out.println(RULE);
        return false;
    }

    /** The load block. Returns false when live duplicates exist, which would block release. */
    static boolean load(Loader.Result result, DryRun.Report report, Ledger ledger) {
        System.out.println();
        System.out.println("LOAD - instances created and HELD at the start event");
        System.out.println(RULE);
        System.out.printf("  runId              %s%n", result.runId());
        System.out.printf("  created (held)     %d%n", result.created());
        System.out.printf("  skipped            %d%n", result.skipped());
        System.out.printf("  quarantined        %d%n", result.quarantined());
        if (result.alreadyLoaded() > 0) {
            System.out.printf("  already loaded     %d  (this run had them; left untouched)%n",
                    result.alreadyLoaded());
        }
        if (result.alreadyExisted() > 0) {
            System.out.printf("  already existed    %d  (engine refused duplicates; parked for review)%n",
                    result.alreadyExisted());
        }
        if (result.failed() > 0) {
            System.out.printf("  failed/unknown     %d%n", result.failed());
        }

        System.out.println();
        System.out.println("  LEDGER BUCKETS");
        var counts = ledger.bucketCounts(result.runId());
        int total = 0;
        for (LedgerState.Bucket b : LedgerState.Bucket.values()) {
            int n = counts.getOrDefault(b, 0);
            total += n;
            System.out.printf("    %-12s %4d%n", b, n);
        }
        System.out.printf("    %-12s %4d   (manifest says %d)%n",
                "TOTAL", total, report.manifest().recordCount());

        var duplicates = ledger.liveDuplicatesByCaseRef(result.runId());
        boolean clean = duplicates.isEmpty();
        System.out.println();
        if (clean) {
            System.out.println("  LIVE DUPLICATES: none");
        } else {
            System.out.println("  LIVE DUPLICATES (would block release):");
            duplicates.forEach((k, v) -> System.out.printf("    %-24s %d instances%n", k, v));
        }

        System.out.println();
        System.out.println("  Nothing is released. No user task exists yet.");
        System.out.println(RULE);
        return clean;
    }

    static void gateHeader(String runId) {
        System.out.println();
        System.out.println("RECONCILE");
        System.out.println(RULE);
        System.out.printf("  runId  %s%n", runId);
        System.out.println("  closing creation, then waiting for the exporter to drain...");
    }

    /** The bucket identity and the gate verdict. Returns the verdict. */
    static boolean gate(Reconciler.GateResult gate) {
        System.out.println();
        System.out.println("  BUCKETS");
        for (LedgerState.Bucket b : LedgerState.Bucket.values()) {
            System.out.printf("    %-12s %4d%n", b, gate.counts().getOrDefault(b, 0));
        }
        System.out.printf("    %-12s %4d   (manifest says %d)%n",
                "TOTAL", gate.total(), gate.manifestCount());

        System.out.println();
        if (gate.passed()) {
            System.out.println("  GATE: PASS");
        } else {
            System.out.println("  GATE: FAIL");
            gate.failures().forEach(f -> System.out.println("    - " + f));
        }
        return gate.passed();
    }

    static void releaseRefused() {
        System.out.println("  RELEASE REFUSED: the gate did not pass.");
        System.out.println("  That is the gate doing its job, not an error to work around.");
    }

    /** The release block. Returns false if any single release failed. */
    static boolean release(Reconciler.ReleaseResult r) {
        System.out.printf("  RELEASE: released %d, failed %d%n", r.released(), r.failed());
        System.out.println("  Each release is ONE modification: activate the target, terminate");
        System.out.println("  the start-event token. The held migrator job cancels itself.");
        return r.failed() == 0;
    }
}
