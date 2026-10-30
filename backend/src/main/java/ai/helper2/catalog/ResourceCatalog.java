package ai.helper2.catalog;

import ai.helper2.catalog.internal.ObservationQueue;
import ai.helper2.catalog.internal.ResourceStore;
import ai.helper2.catalog.internal.ResourceStore.DataOrigin;
import ai.helper2.search.DescribedSource;
import ai.helper2.search.MergedResult;
import ai.helper2.search.RecordedOutcomes;
import ai.helper2.search.ResultObserver;
import ai.helper2.search.SourceDescriptor;
import ai.helper2.search.SourceHealth;
import ai.helper2.search.SourceHit;
import ai.helper2.search.SourceOutcome;
import ai.helper2.shared.ResourceId;
import ai.helper2.shared.SourceMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.stereotype.Service;

/**
 * Catalog entry point. Implements the search module's {@link ResultObserver}: every resource seen in
 * a live search is recorded so Helper2 can state when it was first discovered, and detail pages
 * work for live results, and the hits a source gave can be read back for a stored web result set (ADR 0037). Also
 * {@link RecordedOutcomes}: the Scout's last run outcome per source.
 *
 * Recording is asynchronous ({@link ObservationQueue}), so a search costs one batched read of discovery
 * dates and no writes. The trade-off: a resource seen for the first time has no discovery date in that
 * first response (it is not recorded yet); every later search shows it.
 */
@Service
public class ResourceCatalog implements ResultObserver, RecordedOutcomes, SmartInitializingSingleton {

    private final ResourceStore store;
    private final List<DescribedSource> sources;
    private final ObservationQueue observations;
    private final Set<String> liveSources;
    private final Set<String> demoSources;

    public ResourceCatalog(ResourceStore store, List<DescribedSource> sources, ObservationQueue observations) {
        this.store = store;
        this.sources = sources;
        this.observations = observations;
        this.liveSources = idsWithMode(SourceMode.LIVE);
        this.demoSources = idsWithMode(SourceMode.DEMO);
    }

    /** Make sure every configured source exists in the registry table before anything references it. */
    @Override
    public void afterSingletonsInstantiated() {
        store.registerSources(sources.stream().map(DescribedSource::getDescriptor).toList());
    }

    @Override
    public Map<String, SourceHealth.Entry> lastOutcomes() {
        return store.recordedOutcomes().stream().collect(Collectors.toMap(ResourceStore.RecordedOutcome::sourceId,
            r -> new SourceHealth.Entry(SourceOutcome.valueOf(r.outcome()), r.checkedAt())));
    }

    @Override
    public Map<ResourceId, Instant> firstDiscovered(List<MergedResult> results) {
        Map<UUID, Instant> found = store.firstDiscovered(results.stream().map(r -> r.getId().getValue()).toList());
        Map<ResourceId, Instant> out = new HashMap<>();
        found.forEach((k, v) -> out.put(new ResourceId(k), v));
        return out;
    }

    @Override
    public void record(List<MergedResult> results) {
        if (!results.isEmpty()) {
            observations.offer(() -> persistObserved(results));
        }
    }

    /**
     * Two short reads per id: the resource that owns the record of that URL (it may have moved to a repository's
     * resource), then that resource's records, read once per owner.
     */
    @Override
    public List<SourceHit> recordedHits(String sourceId, List<ResourceId> ids) {
        Map<UUID, List<ResourceStore.RecordRow>> recordsByOwner = new HashMap<>();
        List<SourceHit> hits = new ArrayList<>(ids.size());
        for (ResourceId id : ids) {
            store.ownerOfRecordUrl(id.getValue())
                .flatMap(owner -> recordsByOwner.computeIfAbsent(owner, store::recordsOf).stream()
                    .filter(r -> r.sourceId().equals(sourceId) && ResourceId.of(r.hit().getCanonicalUrl()).equals(id))
                    .findFirst())
                .ifPresent(r -> hits.add(r.hit()));
        }
        return hits;
    }

    private void persistObserved(List<MergedResult> results) {
        store.upsert(results, DataOrigin.OBSERVED, liveSources);
        // Fixture results are stored too (so demo detail pages work) but as DEMO rows, which the
        // Helper2 index never serves. Use a separate database for demo runs.
        if (!demoSources.isEmpty()) {
            store.upsert(results, DataOrigin.DEMO, demoSources);
        }
    }

    private Set<String> idsWithMode(SourceMode mode) {
        return sources.stream().map(DescribedSource::getDescriptor)
            .filter(d -> d.getMode() == mode).map(SourceDescriptor::getId).collect(Collectors.toSet());
    }

    /** Scout path: persist collected results; returns changed source-record ids. */
    public List<UUID> index(List<MergedResult> results, String sourceId) {
        return store.upsert(results, DataOrigin.INDEXED, Set.of(sourceId));
    }

    /** Resource ids owning the given source records. */
    public List<UUID> resourcesOfRecords(List<UUID> recordIds) {
        return store.resourcesOfRecords(recordIds);
    }

    public List<SourceDescriptor> descriptors() {
        return sources.stream().map(DescribedSource::getDescriptor).toList();
    }
}
