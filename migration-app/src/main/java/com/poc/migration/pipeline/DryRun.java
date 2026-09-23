package com.poc.migration.pipeline;

import com.poc.migration.classify.ContractChecker;
import com.poc.migration.classify.DmnClassifier;
import com.poc.migration.extract.AdabasCsvReader;
import com.poc.migration.extract.Manifest;
import com.poc.migration.extract.PreValidator;
import com.poc.migration.extract.RecordNormaliser;
import com.poc.migration.model.Classification;
import com.poc.migration.model.ClassificationInput;
import com.poc.migration.model.SourceRecord;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import org.springframework.stereotype.Component;

/**
 * Classifies the whole population and creates nothing.
 *
 * <pre>
 *   manifest ──► freezeDate, cutoffMonths, headerHash, per-line hashes
 *      │
 *      ▼
 *   CSV ──► discover occurrence columns ──► SourceRecord (raw, hashed)
 *      │
 *      ├─ header hash mismatch  ──► HALT the run (schema changed)
 *      ├─ row hash mismatch     ──► HALT the run (source was not frozen)
 *      ├─ not in manifest       ──► HALT the run
 *      │
 *      ▼
 *   pre-validate ──fail──► QUARANTINE(PREVALIDATION)
 *      │
 *      ▼
 *   normalise ──► 6 total inputs ──► DMN ──► contract check ──► Classification
 * </pre>
 *
 * <p>Hash and count mismatches HALT rather than quarantine. A single bad record is an exception to
 * be worked; a source that changed under the run is not an exception, it means every decision in
 * the run is about data that no longer exists.
 */
@Component
public class DryRun {

    private final AdabasCsvReader reader = new AdabasCsvReader();
    private final PreValidator preValidator = new PreValidator();
    private final DmnClassifier classifier;
    private final ContractChecker contractChecker;

    public DryRun(DmnClassifier classifier, ContractChecker contractChecker) {
        this.classifier = classifier;
        this.contractChecker = contractChecker;
    }

    public Report run(Path exportCsv, Path manifestCsv) throws IOException {
        Manifest manifest = Manifest.read(manifestCsv);
        AdabasCsvReader.Result source = reader.read(exportCsv);
        RecordNormaliser normaliser = new RecordNormaliser(manifest);

        if (!manifest.headerHash().equals(source.headerHash())) {
            throw new IllegalStateException(
                    "header hash mismatch: the source SCHEMA changed since the freeze. "
                            + "Column names affect classification while row hashes stay valid, "
                            + "so this halts the run. manifest=" + manifest.headerHash()
                            + " source=" + source.headerHash());
        }
        if (source.records().size() != manifest.recordCount()) {
            throw new IllegalStateException("record count mismatch: manifest says "
                    + manifest.recordCount() + ", source has " + source.records().size());
        }

        List<Classification> classifications = new ArrayList<>();
        for (SourceRecord record : source.records()) {
            String expectedHash = manifest.hashBySourceLine().get(record.sourceLine());
            if (expectedHash == null) {
                throw new IllegalStateException(
                        "source line " + record.sourceLine() + " is absent from the manifest");
            }
            if (!expectedHash.equals(record.sourceHash())) {
                throw new IllegalStateException("source hash mismatch at line " + record.sourceLine()
                        + ": the source was not frozen");
            }

            Optional<String> failure = preValidator.validate(record);
            if (failure.isPresent()) {
                classifications.add(Classification.preValidationFailure(record, failure.get()));
                continue;
            }

            ClassificationInput input = normaliser.normalise(record);
            Classification classified = classifier.classify(record, input);
            classifications.add(contractChecker.check(classified));
        }

        return Report.of(manifest, source, classifications);
    }

    /** What the dry run produces. Counts, plus the causes behind the unmatched ones. */
    public record Report(
            Manifest manifest,
            int sourceCount,
            int visionColumnCount,
            int restrictionColumnCount,
            Map<Classification.Outcome, Integer> byOutcome,
            Map<String, Integer> byTargetElement,
            Map<String, Integer> byReasonCode,
            Map<String, Integer> unmatchedByCombination,
            int redundantRuleWarnings,
            List<Classification> classifications) {

        static Report of(Manifest manifest, AdabasCsvReader.Result source, List<Classification> cs) {
            Map<Classification.Outcome, Integer> byOutcome = new LinkedHashMap<>();
            Map<String, Integer> byTarget = new TreeMap<>();
            Map<String, Integer> byReason = new TreeMap<>();
            Map<String, Integer> unmatched = new TreeMap<>();
            int redundant = 0;

            for (Classification c : cs) {
                byOutcome.merge(c.outcome(), 1, Integer::sum);
                if (c.targetElementId() != null) {
                    byTarget.merge(c.targetElementId(), 1, Integer::sum);
                }
                if (c.reasonCode() != null) {
                    byReason.merge(c.reasonCode(), 1, Integer::sum);
                }
                if (c.redundantRules()) {
                    redundant++;
                }
                // The improvement loop needs causes, not just a total: group every unmatched
                // record by the exact input combination that matched nothing, so "N unmatched"
                // becomes an ordered list of patterns a business analyst can work through.
                if (Classification.Reason.UNMATCHED.equals(c.reasonCode()) && c.input() != null) {
                    unmatched.merge(c.input().combinationKey(), 1, Integer::sum);
                }
            }
            return new Report(manifest, source.records().size(), source.visionColumnCount(),
                    source.restrictionColumnCount(), byOutcome, byTarget, byReason, unmatched,
                    redundant, List.copyOf(cs));
        }
    }
}
