package ai.helper2.pages.usecase;

import ai.helper2.catalog.ResourceDetail;
import ai.helper2.catalog.usecase.GetResourceDetail;
import ai.helper2.pages.Link;
import ai.helper2.pages.PageAddress;
import ai.helper2.pages.PageHead;
import ai.helper2.pages.PageKind;
import ai.helper2.pages.Pages;
import ai.helper2.pages.Related;
import ai.helper2.pages.ResourceView;
import ai.helper2.pages.config.PagesProperties;
import ai.helper2.search.MergedResult;
import ai.helper2.shared.Verification;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;

/**
 * What the server answers for an address of the app (ADR 0050 §2): the head to write into the shell
 * and the status, or a redirect for a resource whose id moved.
 */
@Service
public class RenderPage {

  /** [head] is null for a redirect to [location]. */
  public record Page(int status, @Nullable PageHead head, @Nullable String location) {
    static Page ok(PageHead head) {
      return new Page(200, head, null);
    }

    static Page notFound(PageHead head) {
      return new Page(404, head, null);
    }

    static Page movedTo(String location) {
      return new Page(301, null, location);
    }
  }

  private final GetResourceDetail details;
  private final Pages pages;

  public RenderPage(GetResourceDetail details, PagesProperties properties) {
    this.details = details;
    this.pages = new Pages(properties.publicBaseUrl());
  }

  /** [hasQuery]: the home page with a search in its address is a search page. */
  public Page render(String path, boolean hasQuery) {
    return switch (PageAddress.Companion.of(path)) {
      case PageAddress.Resource r -> resource(r.getId());
      case PageAddress.Page p when p.getKind() == PageKind.HOME && hasQuery ->
          Page.ok(pages.of(PageKind.SEARCH));
      case PageAddress.Page p -> Page.ok(pages.of(p.getKind()));
      default -> Page.notFound(pages.pageNotFound());
    };
  }

  private Page resource(@Nullable UUID id) {
    Optional<ResourceDetail> detail = id == null ? Optional.empty() : details.find(id);
    if (detail.isEmpty()) {
      return Page.notFound(pages.resourceNotFound());
    }
    UUID owner = detail.get().card().getId().getValue();
    if (!owner.equals(id)) {
      return Page.movedTo("/resource/" + owner);
    }
    return Page.ok(pages.resource(view(detail.get())));
  }

  private static ResourceView view(ResourceDetail detail) {
    MergedResult card = detail.card();
    return new ResourceView(
        card.getId().getValue(),
        card.getTitle(),
        card.getType(),
        card.getDescription(),
        card.getDescriptionOrigin(),
        card.getUrl(),
        card.getLicense(),
        card.getPricing(),
        card.getPlatforms(),
        card.getPublished().getValue(),
        card.getEventStart().getValue(),
        card.getEventEnd().getValue(),
        card.getLocation(),
        card.getSources().stream().map(s -> new Link(s.getSourceName(), s.getUrl())).toList(),
        related(detail.relationships()));
  }

  /** Resources the catalog links to, once each, without the ones a person rejected. */
  private static List<Related> related(List<ResourceDetail.Relationship> relationships) {
    Map<UUID, Related> byId = new LinkedHashMap<>();
    for (ResourceDetail.Relationship r : relationships) {
      if (r.targetId() != null && r.verification() != Verification.REJECTED) {
        byId.putIfAbsent(r.targetId(), new Related(r.targetId(), r.targetTitle()));
      }
    }
    return List.copyOf(byId.values());
  }
}
