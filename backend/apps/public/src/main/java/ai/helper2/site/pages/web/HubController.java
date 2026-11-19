package ai.helper2.site.pages.web;

import ai.helper2.api.PublicCache;
import ai.helper2.api.common.model.ResourceTypeDto;
import ai.helper2.api.generated.HubsApi;
import ai.helper2.api.generated.model.HubResponseDto;
import ai.helper2.platform.ApiProblem;
import ai.helper2.platform.ProblemType;
import ai.helper2.search.web.CardMapper;
import ai.helper2.shared.ResourceType;
import ai.helper2.shared.UtcTimes;
import ai.helper2.site.pages.Hub;
import ai.helper2.site.pages.internal.CatalogSiteLists;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
class HubController implements HubsApi {

  private final CatalogSiteLists lists;

  HubController(CatalogSiteLists lists) {
    this.lists = lists;
  }

  @Override
  public ResponseEntity<HubResponseDto> listHub(ResourceTypeDto type) {
    ResourceType resourceType = ResourceType.valueOf(type.getValue());
    if (Hub.of(resourceType) == null) {
      throw new ApiProblem(ProblemType.NOT_FOUND, "There is no hub page for this type.");
    }
    CatalogSiteLists.Read read = lists.hubCards(resourceType);
    return ResponseEntity.ok()
        .cacheControl(PublicCache.SHORT)
        .body(
            new HubResponseDto(
                type,
                read.cards().stream().map(CardMapper::card).toList(),
                UtcTimes.odt(read.at())));
  }
}
