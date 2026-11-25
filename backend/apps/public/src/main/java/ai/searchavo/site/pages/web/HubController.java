package ai.searchavo.site.pages.web;

import ai.searchavo.api.PublicCache;
import ai.searchavo.api.common.model.ResourceTypeDto;
import ai.searchavo.api.generated.HubsApi;
import ai.searchavo.api.generated.model.HubResponseDto;
import ai.searchavo.platform.ApiProblem;
import ai.searchavo.platform.ProblemType;
import ai.searchavo.search.web.CardMapper;
import ai.searchavo.shared.ResourceType;
import ai.searchavo.shared.UtcTimes;
import ai.searchavo.site.pages.Hub;
import ai.searchavo.site.pages.internal.CatalogSiteLists;
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
