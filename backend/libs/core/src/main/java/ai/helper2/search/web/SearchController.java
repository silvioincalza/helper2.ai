package ai.helper2.search.web;

import ai.helper2.api.common.SearchApi;
import ai.helper2.api.common.model.AudienceDto;
import ai.helper2.api.common.model.AudiencePathDto;
import ai.helper2.api.common.model.CoverageDto;
import ai.helper2.api.common.model.FacetValueDto;
import ai.helper2.api.common.model.FacetsDto;
import ai.helper2.api.common.model.NoticeDto;
import ai.helper2.api.common.model.PricingFilterDto;
import ai.helper2.api.common.model.QueryInterpretationDto;
import ai.helper2.api.common.model.ResourceTypeDto;
import ai.helper2.api.common.model.SearchResponseDto;
import ai.helper2.api.common.model.ServedFromDto;
import ai.helper2.api.common.model.SkipReasonDto;
import ai.helper2.api.common.model.SortOrderDto;
import ai.helper2.api.common.model.SourceModeDto;
import ai.helper2.api.common.model.SourceOutcomeDto;
import ai.helper2.api.common.model.SourceStatusDto;
import ai.helper2.api.common.model.TechLevelFilterDto;
import ai.helper2.api.common.model.TriStateDto;
import ai.helper2.platform.BadRequestException;
import ai.helper2.platform.ClientKey;
import ai.helper2.search.Asker;
import ai.helper2.search.FacetCount;
import ai.helper2.search.Feature;
import ai.helper2.search.SearchFilters;
import ai.helper2.search.SearchOutcome;
import ai.helper2.search.SearchRequest;
import ai.helper2.search.SortOrder;
import ai.helper2.search.SourceStatus;
import ai.helper2.search.usecase.SearchResources;
import ai.helper2.shared.Audience;
import ai.helper2.shared.AudiencePath;
import ai.helper2.shared.CategoryCatalog;
import ai.helper2.shared.ResourceType;
import ai.helper2.shared.UtcTimes;
import jakarta.servlet.http.HttpServletRequest;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
class SearchController implements SearchApi {

  static final String COVERAGE_NOTE =
      "Results come only from the sources listed here; helper2.ai does not search the whole web.";

  private final SearchResources search;
  private final CategoryCatalog categories;

  private final HttpServletRequest http;

  SearchController(SearchResources search, CategoryCatalog categories, HttpServletRequest http) {
    this.search = search;
    this.categories = categories;
    this.http = http;
  }

  @Override
  public ResponseEntity<SearchResponseDto> search(
      String q,
      @Nullable AudiencePathDto path,
      @Nullable List<ResourceTypeDto> type,
      @Nullable List<String> source,
      @Nullable List<String> license,
      @Nullable TriStateDto openSource,
      @Nullable List<PricingFilterDto> pricing,
      @Nullable List<String> platform,
      @Nullable List<String> useCase,
      @Nullable List<TechLevelFilterDto> techLevel,
      @Nullable List<AudienceDto> audience,
      @Nullable LocalDate updatedAfter,
      @Nullable List<String> category,
      @Nullable SortOrderDto sort,
      Integer page,
      Integer size) {
    if (q.isBlank()) {
      throw new BadRequestException("Query must not be blank");
    }
    Set<String> categories = categoryFilter(category);
    SearchFilters filters =
        new SearchFilters(
            set(type, t -> ResourceType.valueOf(t.getValue())),
            set(source, Function.identity()),
            set(license, l -> l.equalsIgnoreCase("unknown") ? "unknown" : l),
            openSource == null ? Set.of() : Set.of(openSource.getValue()),
            set(pricing, p -> p == PricingFilterDto.UNKNOWN ? "unknown" : p.getValue()),
            set(platform, Function.identity()),
            set(useCase, Function.identity()),
            set(techLevel, t -> t == TechLevelFilterDto.UNKNOWN ? "unknown" : t.getValue()),
            set(audience, a -> Audience.valueOf(a.getValue())),
            updatedAfter,
            null,
            null,
            categories);
    Asker asker = new Asker(Feature.SEARCH, ClientKey.of(http.getRemoteAddr()));
    SearchOutcome out =
        search.search(
            new SearchRequest(
                q,
                path == null ? null : AudiencePath.valueOf(path.getValue()),
                filters,
                sort == null ? SortOrder.RELEVANCE : SortOrder.valueOf(sort.getValue()),
                page,
                size,
                null,
                false,
                asker,
                null));
    return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(toDto(out));
  }

  private static SearchResponseDto toDto(SearchOutcome out) {
    SearchResponseDto dto =
        new SearchResponseDto()
            .query(out.getRequest().getText())
            .path(
                out.getRequest().getPath() == null
                    ? null
                    : AudiencePathDto.fromValue(out.getRequest().getPath().name()))
            .sort(SortOrderDto.fromValue(out.getRequest().getSort().name()))
            .executedAt(UtcTimes.odt(out.getExecutedAt()))
            .coverage(
                new CoverageDto()
                    .partial(out.getCoverage().getPartial())
                    .sourcesConfigured(out.getCoverage().getSourcesConfigured())
                    .sourcesQueried(out.getCoverage().getSourcesQueried())
                    .sourcesSucceeded(out.getCoverage().getSourcesSucceeded())
                    .note(COVERAGE_NOTE))
            .sources(out.getStatuses().stream().map(SearchController::status).toList())
            .results(out.getResults().stream().map(CardMapper::card).toList())
            .facets(facets(out.getFacets()))
            .total(out.getTotal())
            .page(out.getRequest().getPage())
            .size(out.getRequest().getSize())
            .notices(
                out.getNotices().stream()
                    .map(n ->
                        new NoticeDto(
                            NoticeDto.CodeEnum.fromValue(n.getCode().name()), n.getMessage()))
                    .toList())
            .interpretation(
                new QueryInterpretationDto(
                        out.getInterpretation().getLexiconTerms(),
                        List.copyOf(out.getInterpretation().getUseCases()))
                    .translatedQuery(out.getInterpretation().getTranslation()))
            .webSearches(CardMapper.webSearches(out.getWebSearches()));
    return dto;
  }

  private static SourceStatusDto status(SourceStatus s) {
    return new SourceStatusDto(
            s.getSource().getId(),
            s.getSource().getName(),
            SourceModeDto.fromValue(s.getSource().getMode().name()),
            SourceOutcomeDto.fromValue(s.getOutcome().name()),
            ServedFromDto.fromValue(s.getServedFrom().name()),
            s.getResultCount(),
            UtcTimes.odt(s.getCheckedAt()))
        .latencyMs(s.getLatency() == null ? null : s.getLatency().toMillis())
        .cachedAt(UtcTimes.odt(s.getCachedAt()))
        .message(s.getMessage())
        .skipReason(
            s.getSkipReason() == null ? null : SkipReasonDto.fromValue(s.getSkipReason().name()));
  }

  private static FacetsDto facets(Map<String, List<FacetCount>> f) {
    Function<String, List<FacetValueDto>> get =
        name ->
            f.getOrDefault(name, List.of()).stream()
                .map(c -> new FacetValueDto(c.getValue(), c.getCount()))
                .toList();
    return new FacetsDto(
        get.apply("type"),
        get.apply("source"),
        get.apply("license"),
        get.apply("openSource"),
        get.apply("pricing"),
        get.apply("platform"),
        get.apply("useCase"),
        get.apply("techLevel"),
        get.apply("audience"),
        get.apply("category"));
  }

  private Set<String> categoryFilter(@Nullable List<String> category) {
    Set<String> selected = set(category, Function.identity());
    List<String> unknown = categories.notFilterValues(selected);
    if (!unknown.isEmpty()) {
      throw new BadRequestException("Unknown category: " + String.join(", ", unknown));
    }
    return selected;
  }

  private static <T, R> Set<R> set(@Nullable Collection<T> in, Function<T, R> f) {
    return in == null ? Set.of() : in.stream().map(f).collect(Collectors.toUnmodifiableSet());
  }
}
