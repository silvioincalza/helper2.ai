package ai.helper2.catalog;

import ai.helper2.shared.Verification;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * Inbound port of the catalog for enrichment: what one enrichment run of a resource leaves in the catalog. The
 * catalog owns the tables (runs, AI-extracted fields, relationships, summary claims, evidence) and how a new run
 * replaces an earlier one; the Scout only says what the run found.
 */
public interface EnrichmentResults {

    /**
     * One run. [outcome] is VALIDATED, PARTIAL, REJECTED, LLM_UNAVAILABLE or FAILED; [errors] are the validation
     * errors, or the failure.
     */
    record Run(UUID resourceId, String provider, String model, String promptVersion, String inputFingerprint,
               Instant startedAt, String outcome, List<String> errors) {
    }

    /** A passage of a source record that supports a fact. */
    record Citation(UUID sourceRecordId, String excerpt) {
    }

    record Field(String field, String value, Verification verification, List<Citation> citations) {
    }

    /** [targetId] is the catalog resource the target names, or null when none is known. */
    record Relationship(String kind, String target, @Nullable UUID targetId, Verification verification,
                        List<Citation> citations) {
    }

    record Claim(String text, List<Citation> citations) {
    }

    /** What the validator kept of the model's answer, REJECTED facts included (they are not written). */
    record Checked(List<Field> fields, List<Relationship> relationships, List<Claim> summary) {
    }

    /**
     * Records [run] and sets the resource's enrichment status. With [checked] (the model answered), the run's facts
     * replace the AI-extracted facts of earlier runs, and every problem opens a review item. Returns the run's id.
     */
    UUID record(Run run, @Nullable Checked checked);
}
