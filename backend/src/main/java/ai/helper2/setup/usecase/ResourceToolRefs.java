package ai.helper2.setup.usecase;

import ai.helper2.catalog.ResourceDetail;
import ai.helper2.search.SourceHit;
import ai.helper2.setup.ToolRef;
import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;

/** What the setup guide needs to know about a catalog resource: where its documents are, and its registry name. */
final class ResourceToolRefs {

    private ResourceToolRefs() {
    }

    /** Where the resource's own documents are: identifiers parsed from its links and source records. */
    static ToolRef ref(ResourceDetail d) {
        List<String> urls = new ArrayList<>();
        urls.add(d.card().getUrl().toString());
        for (SourceHit h : d.card().getHits()) {
            urls.add(h.getUrl().toString());
            if (h.getRepositoryUrl() != null) {
                urls.add(h.getRepositoryUrl().getValue());
            }
        }
        d.sourceRecords().forEach(r -> urls.add(r.url().toString()));
        List<String> npm = d.sourceRecords().stream().filter(r -> "npm".equals(r.sourceId()))
            .map(ResourceDetail.SourceRecordInfo::externalId).toList();
        return ToolRef.Companion.of(d.card().getTitle(), d.card().getType().name(), urls, npm);
    }

    /** The server's name in the Official MCP Registry, or null when the resource has no registry record. */
    static @Nullable String registryName(ResourceDetail d) {
        return d.sourceRecords().stream()
            .filter(r -> "mcp-registry".equals(r.sourceId()))
            .map(ResourceDetail.SourceRecordInfo::externalId)
            .findFirst().orElse(null);
    }
}
