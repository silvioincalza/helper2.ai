package ai.helper2.search.web;

import ai.helper2.api.generated.SearchApi;
import ai.helper2.api.generated.SourcesApi;
import ai.helper2.api.generated.model.AudienceDto;
import ai.helper2.api.generated.model.AudiencePathDto;
import ai.helper2.api.generated.model.CoverageDto;
import ai.helper2.api.generated.model.FacetValueDto;
import ai.helper2.api.generated.model.FacetsDto;
import ai.helper2.api.generated.model.NoticeDto;
import ai.helper2.api.generated.model.PricingFilterDto;
import ai.helper2.api.generated.model.QueryInterpretationDto;
import ai.helper2.api.generated.model.ResourceTypeDto;
import ai.helper2.api.generated.model.SearchResponseDto;
import ai.helper2.api.generated.model.ServedFromDto;
import ai.helper2.api.generated.model.SortOrderDto;
import ai.helper2.api.generated.model.SourceInfoDto;
import ai.helper2.api.generated.model.SourceModeDto;
import ai.helper2.api.generated.model.SourceOutcomeDto;
import ai.helper2.api.generated.model.SourceStatusDto;
import ai.helper2.api.generated.model.TechLevelFilterDto;
import ai.helper2.api.generated.model.TriStateDto;
import ai.helper2.platform.BadRequestException;
import ai.helper2.search.DescribedSource;
import ai.helper2.search.FacetCount;
import ai.helper2.search.usecase.SearchResources;
import ai.helper2.search.SearchFilters;
import ai.helper2.search.SearchOutcome;
import ai.helper2.search.SearchRequest;
import ai.helper2.search.SortOrder;
import ai.helper2.search.SourceDescriptor;
import ai.helper2.search.RecordedOutcomes;
import ai.helper2.search.SourceHealth;
import ai.helper2.search.SourceStatus;
import ai.helper2.shared.Audience;
import ai.helper2.shared.AudiencePath;
import ai.helper2.shared.ResourceType;
import java.time.LocalDate;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
class SearchController implements SearchApi, SourcesApi {

    private static final Logger log = LoggerFactory.getLogger(SearchController.class);

    static final String COVERAGE_NOTE =
        "Results come only from the sources listed here; Helper2.ai does not search the whole web.";

    private final SearchResources search;
    private final List<DescribedSource> sources;
    private final SourceHealth health;
    private final RecordedOutcomes recorded;

    SearchController(SearchResources search, List<DescribedSource> sources, SourceHealth health, RecordedOutcomes recorded) {
        this.search = search;
        this.sources = sources;
        this.health = health;
        this.recorded = recorded;
    }

    @Override
    public ResponseEntity<SearchResponseDto> search(String q, @Nullable AudiencePathDto path,
                                                    @Nullable List<ResourceTypeDto> type, @Nullable List<String> source,
                                                    @Nullable List<String> license, @Nullable TriStateDto openSource,
                                                    @Nullable List<PricingFilterDto> pricing, @Nullable List<String> platform,
                                                    @Nullable List<String> useCase, @Nullable List<TechLevelFilterDto> techLevel,
                                                    @Nullable List<AudienceDto> audience, @Nullable LocalDate updatedAfter,
                                                    @Nullable SortOrderDto sort, Integer page, Integer size) {
        if (q.isBlank()) {
            throw new BadRequestException("Query must not be blank");
        }
        SearchFilters filters = new SearchFilters(
            set(type, t -> ResourceType.valueOf(t.getValue())),
            set(source, Function.identity()),
            set(license, l -> l.equalsIgnoreCase("unknown") ? "unknown" : l),
            openSource == null ? Set.of() : Set.of(openSource.getValue()),
            set(pricing, p -> p == PricingFilterDto.UNKNOWN ? "unknown" : p.getValue()),
            set(platform, Function.identity()),
            set(useCase, Function.identity()),
            set(techLevel, t -> t == TechLevelFilterDto.UNKNOWN ? "unknown" : t.getValue()),
            set(audience, a -> Audience.valueOf(a.getValue())),
            updatedAfter);
        SearchOutcome out = search.search(new SearchRequest(
            q, path == null ? null : AudiencePath.valueOf(path.getValue()), filters,
            sort == null ? SortOrder.RELEVANCE : SortOrder.valueOf(sort.getValue()), page, size));
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(toDto(out));
    }

    @Override
    public ResponseEntity<List<SourceInfoDto>> listSources() {
        Map<String, SourceHealth.Entry> fromScout = recordedOutcomes();
        return ResponseEntity.ok(sources.stream().map(DescribedSource::getDescriptor)
            .sorted(Comparator.comparingInt(SourceDescriptor::getPriority).thenComparing(SourceDescriptor::getId))
            .map(d -> {
                SourceInfoDto dto = new SourceInfoDto(d.getId(), d.getName(), SourceModeDto.fromValue(d.getMode().name()),
                    d.getEnabled(), d.getHomepage(),
                    d.getResourceTypes().stream().map(t -> ResourceTypeDto.fromValue(t.name())).sorted().toList())
                    .termsUrl(d.getTermsUrl()).attribution(d.getAttribution());
                SourceHealth.Entry e = health.lastFor(d.getId(), fromScout.get(d.getId()));
                if (e != null) {
                    dto.lastOutcome(SourceOutcomeDto.fromValue(e.getOutcome().name())).lastCheckedAt(CardMapper.odt(e.getCheckedAt()));
                }
                return dto;
            }).toList());
    }

    /** The page must still render when the database is down: fall back to what this instance saw. */
    private Map<String, SourceHealth.Entry> recordedOutcomes() {
        try {
            return recorded.lastOutcomes();
        } catch (RuntimeException e) {
            log.warn("Recorded source outcomes unavailable: {}", e.toString());
            return Map.of();
        }
    }

    private static SearchResponseDto toDto(SearchOutcome out) {
        SearchResponseDto dto = new SearchResponseDto()
            .query(out.getRequest().getText())
            .path(out.getRequest().getPath() == null ? null : AudiencePathDto.fromValue(out.getRequest().getPath().name()))
            .sort(SortOrderDto.fromValue(out.getRequest().getSort().name()))
            .executedAt(CardMapper.odt(out.getExecutedAt()))
            .coverage(new CoverageDto()
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
            .notices(out.getNotices().stream()
                .map(n -> new NoticeDto(NoticeDto.CodeEnum.fromValue(n.getCode().name()), n.getMessage())).toList())
            .interpretation(new QueryInterpretationDto(out.getInterpretation().getExpandedTerms(),
                List.copyOf(out.getInterpretation().getUseCases())));
        return dto;
    }

    private static SourceStatusDto status(SourceStatus s) {
        return new SourceStatusDto(s.getSource().getId(), s.getSource().getName(),
            SourceModeDto.fromValue(s.getSource().getMode().name()), SourceOutcomeDto.fromValue(s.getOutcome().name()),
            ServedFromDto.fromValue(s.getServedFrom().name()), s.getResultCount(), CardMapper.odt(s.getCheckedAt()))
            .latencyMs(s.getLatency() == null ? null : s.getLatency().toMillis())
            .cachedAt(CardMapper.odt(s.getCachedAt()))
            .message(s.getMessage());
    }

    private static FacetsDto facets(Map<String, List<FacetCount>> f) {
        Function<String, List<FacetValueDto>> get = name -> f.getOrDefault(name, List.of()).stream()
            .map(c -> new FacetValueDto(c.getValue(), c.getCount())).toList();
        return new FacetsDto(get.apply("type"), get.apply("source"), get.apply("license"), get.apply("openSource"),
            get.apply("pricing"), get.apply("platform"), get.apply("useCase"), get.apply("techLevel"), get.apply("audience"));
    }

    private static <T, R> Set<R> set(@Nullable Collection<T> in, Function<T, R> f) {
        return in == null ? Set.of() : in.stream().map(f).collect(Collectors.toUnmodifiableSet());
    }
}
