package ai.helper2.catalog.usecase;

import ai.helper2.catalog.CatalogLimits;
import ai.helper2.catalog.CatalogWriter;
import ai.helper2.catalog.CuratedTypes;
import ai.helper2.catalog.DataOrigin;
import ai.helper2.catalog.Resources;
import ai.helper2.platform.BadRequestException;
import ai.helper2.search.MergedResult;
import ai.helper2.search.ResultMerger;
import ai.helper2.search.ServedFrom;
import ai.helper2.search.SourceContext;
import ai.helper2.search.SourceDescriptor;
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

public class AddCuratedResource {

  static final int MAX_TITLE = CatalogLimits.TITLE_CHARS;
  static final int MAX_URL = CanonicalUrl.MAX_LENGTH;

  public record Outcome(UUID id, String title, boolean added) {}

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

  public Outcome add(String title, String url, ResourceType type) {
    String name = checkedTitle(title);
    CanonicalUrl address = checkedUrl(url);
    if (!CuratedTypes.TYPES.contains(type)) {
      throw new BadRequestException("A relationship can point only at a tool or a model.");
    }
    Optional<Resources.Titled> known = resources.atUrl(address.getValue());
    if (known.isPresent()) {
      return new Outcome(known.get().id(), known.get().title(), false);
    }
    return new Outcome(index(name, address, type), name, true);
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
