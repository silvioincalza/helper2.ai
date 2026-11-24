package ai.searchavo.catalog.usecase;

import ai.searchavo.catalog.CatalogLimits;
import ai.searchavo.catalog.CatalogWriter;
import ai.searchavo.catalog.CuratedTypes;
import ai.searchavo.catalog.DataOrigin;
import ai.searchavo.catalog.Resources;
import ai.searchavo.search.MergedResult;
import ai.searchavo.search.ResultMerger;
import ai.searchavo.search.ServedFrom;
import ai.searchavo.search.SourceContext;
import ai.searchavo.search.SourceDescriptor;
import ai.searchavo.search.SourceHit;
import ai.searchavo.shared.CanonicalUrl;
import ai.searchavo.shared.ResourceType;
import java.net.URI;
import java.time.Clock;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

public class AddCuratedResource {

  static final int MAX_TITLE = CatalogLimits.TITLE_CHARS;
  static final int MAX_URL = CanonicalUrl.MAX_LENGTH;

  public sealed interface Outcome {
    record Added(UUID id, String title) implements Outcome {}

    record AlreadyListed(UUID id, String title) implements Outcome {}

    record InvalidTitle(int maxLength) implements Outcome {}

    record InvalidUrl() implements Outcome {}

    record NotACuratedType(ResourceType type) implements Outcome {}
  }

  private final CatalogWriter writer;
  private final Resources resources;
  private final SourceDescriptor curated;
  private final Clock clock;
  private final ResultMerger merger = new ResultMerger();

  public AddCuratedResource(
      CatalogWriter writer, Resources resources, SourceDescriptor curated, Clock clock) {
    this.writer = writer;
    this.resources = resources;
    this.curated = curated;
    this.clock = clock;
  }

  public Outcome add(@Nullable String title, @Nullable String url, ResourceType type) {
    String name = title == null ? "" : title.strip();
    if (!isATitle(name)) {
      return new Outcome.InvalidTitle(MAX_TITLE);
    }
    CanonicalUrl address = httpsAddress(url);
    if (address == null) {
      return new Outcome.InvalidUrl();
    }
    if (!CuratedTypes.TYPES.contains(type)) {
      return new Outcome.NotACuratedType(type);
    }
    return resources
        .atUrl(address.getValue())
        .<Outcome>map(known -> new Outcome.AlreadyListed(known.id(), known.title()))
        .orElseGet(() -> new Outcome.Added(index(name, address, type), name));
  }

  private UUID index(String title, CanonicalUrl url, ResourceType type) {
    String source = curated.getId();
    SourceHit hit =
        new SourceHit(
            source, url.getValue(), URI.create(url.getValue()), url, title, type, clock.instant());
    List<MergedResult> merged =
        merger.merge(List.of(hit), Map.of(source, new SourceContext(curated, ServedFrom.LIVE)));
    List<UUID> records = writer.upsert(merged, DataOrigin.CURATED, Set.of(source));
    return resources.resourcesOfRecords(records).stream()
        .findFirst()
        .orElseThrow(() -> new IllegalStateException("the resource was not stored"));
  }

  private static boolean isATitle(String name) {
    return !name.isEmpty()
        && name.length() <= MAX_TITLE
        && name.chars().noneMatch(Character::isISOControl);
  }

  private static @Nullable CanonicalUrl httpsAddress(@Nullable String url) {
    String value = url == null ? "" : url.strip();
    return value.length() > MAX_URL || !value.toLowerCase(Locale.ROOT).startsWith("https://")
        ? null
        : CanonicalUrl.of(value);
  }
}
