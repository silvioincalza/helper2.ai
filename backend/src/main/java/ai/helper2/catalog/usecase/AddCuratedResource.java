package ai.helper2.catalog.usecase;

import ai.helper2.catalog.internal.CatalogSearchSource;
import ai.helper2.catalog.internal.CuratedTypes;
import ai.helper2.catalog.internal.ResourceStore;
import ai.helper2.catalog.internal.ResourceStore.DataOrigin;
import ai.helper2.platform.BadRequestException;
import ai.helper2.search.MergedResult;
import ai.helper2.search.ResultMerger;
import ai.helper2.search.ServedFrom;
import ai.helper2.search.SourceContext;
import ai.helper2.search.SourceHit;
import ai.helper2.shared.CanonicalUrl;
import ai.helper2.shared.ResourceType;
import java.net.URI;
import java.time.Clock;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

/**
 * An admin adds the model or product a relationship is about, from the review list (ADR 0064): its
 * name, its official https address and its type. It becomes a record of the curated source, with no
 * description, and the next Scout run enriches it from its own pages. An address the catalog
 * already has gives that resource, never a second one.
 */
@Service
public class AddCuratedResource {

  static final int MAX_TITLE = 300;
  static final int MAX_URL = 2000;

  /** The resource: added now, or already in the catalog under that address. */
  public record Outcome(UUID id, String title, boolean added) {}

  private final ResourceStore store;
  private final CatalogSearchSource curated;
  private final Clock clock;
  private final ResultMerger merger = new ResultMerger();

  public AddCuratedResource(
      ResourceStore store,
      @Qualifier("curatedSearchSource") CatalogSearchSource curated,
      Clock clock) {
    this.store = store;
    this.curated = curated;
    this.clock = clock;
  }

  /** Call inside a transaction. Throws {@link BadRequestException} for an unusable value. */
  public Outcome add(String title, String url, ResourceType type) {
    String name = checkedTitle(title);
    CanonicalUrl address = checkedUrl(url);
    if (!CuratedTypes.TYPES.contains(type)) {
      throw new BadRequestException("A relationship can point only at a tool or a model.");
    }
    Optional<ResourceStore.Titled> known = store.atUrl(address.getValue());
    if (known.isPresent()) {
      return new Outcome(known.get().id(), known.get().title(), false);
    }
    return new Outcome(index(name, address, type), name, true);
  }

  private UUID index(String title, CanonicalUrl url, ResourceType type) {
    String source = curated.getDescriptor().getId();
    SourceHit hit =
        new SourceHit(
            source, url.getValue(), URI.create(url.getValue()), url, title, type, clock.instant());
    List<MergedResult> merged =
        merger.merge(
            List.of(hit),
            Map.of(source, new SourceContext(curated.getDescriptor(), ServedFrom.LIVE)));
    List<UUID> records = store.upsert(merged, DataOrigin.CURATED, Set.of(source));
    return store.resourcesOfRecords(records).stream()
        .findFirst()
        .orElseThrow(() -> new IllegalStateException("the resource was not stored"));
  }

  private static String checkedTitle(String title) {
    String name = title == null ? "" : title.strip();
    if (name.isEmpty()
        || name.length() > MAX_TITLE
        || name.chars().anyMatch(Character::isISOControl)) {
      throw new BadRequestException("The name must be 1 to " + MAX_TITLE + " characters.");
    }
    return name;
  }

  private static CanonicalUrl checkedUrl(String url) {
    String value = url == null ? "" : url.strip();
    CanonicalUrl address =
        value.length() > MAX_URL || !value.toLowerCase(Locale.ROOT).startsWith("https://")
            ? null
            : CanonicalUrl.of(value);
    if (address == null) {
      throw new BadRequestException("The address must be the resource's own https address.");
    }
    return address;
  }
}
