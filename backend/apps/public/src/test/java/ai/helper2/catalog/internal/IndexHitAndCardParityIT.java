package ai.helper2.catalog.internal;

import static org.assertj.core.api.Assertions.assertThat;

import ai.helper2.TestcontainersConfiguration;
import ai.helper2.catalog.CatalogWriter;
import ai.helper2.catalog.DataOrigin;
import ai.helper2.catalog.usecase.GetResourceDetail;
import ai.helper2.search.MergedResult;
import ai.helper2.search.ResultMerger;
import ai.helper2.search.ServedFrom;
import ai.helper2.search.SourceContext;
import ai.helper2.search.SourceDescriptor;
import ai.helper2.search.SourceHit;
import ai.helper2.search.SourceQuery;
import ai.helper2.search.Vitality;
import ai.helper2.shared.Audience;
import ai.helper2.shared.CanonicalUrl;
import ai.helper2.shared.DateFact;
import ai.helper2.shared.EventFormat;
import ai.helper2.shared.EventKind;
import ai.helper2.shared.Pricing;
import ai.helper2.shared.ResourceId;
import ai.helper2.shared.ResourceType;
import ai.helper2.shared.SourceMode;
import ai.helper2.shared.ValueOrigin;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
class IndexHitAndCardParityIT {

  static final List<String> THE_ROW_KEEPS_THE_TITLE_OF_THE_LAST_WRITE =
      List.of("two titles, reversed: title is Parity alpha in the index, Parity beta on the card");

  List<String> knownGaps() {
    return THE_ROW_KEEPS_THE_TITLE_OF_THE_LAST_WRITE;
  }

  private static final Set<String> SOURCES = Set.of("github", "npm");
  private static final String AS_WRITTEN = "as written";
  private static final String REVERSED = "reversed";

  @Autowired
  @Qualifier("indexSearchSource")
  CatalogSearchSource index;

  @Autowired CatalogWriter writer;
  @Autowired GetResourceDetail details;
  @Autowired JdbcClient jdbc;

  private final String run = UUID.randomUUID().toString().substring(0, 8);
  private final String word = "parityword" + UUID.randomUUID().toString().replaceAll("[^a-f]", "");
  private final Instant now = Instant.now();

  private static final class Says {
    private final String source;
    private DataOrigin origin = DataOrigin.INDEXED;
    private String title = "Parity tool";
    private ResourceType type = ResourceType.LIBRARY;
    private String licence;
    private Pricing pricing;
    private Boolean openSource;
    private LocalDate starts;
    private LocalDate ends;
    private String place;
    private EventKind kind;
    private EventFormat format;
    private Vitality vitality = Vitality.UNKNOWN;

    private Says(String source) {
      this.source = source;
    }

    Says observed() {
      origin = DataOrigin.OBSERVED;
      return this;
    }

    Says title(String stated) {
      title = stated;
      return this;
    }

    Says type(ResourceType stated) {
      type = stated;
      return this;
    }

    Says licence(String stated) {
      licence = stated;
      return this;
    }

    Says pricing(Pricing stated) {
      pricing = stated;
      return this;
    }

    Says openSource(boolean stated) {
      openSource = stated;
      return this;
    }

    Says vitality(Vitality stated) {
      vitality = stated;
      return this;
    }

    Says event() {
      type = ResourceType.EVENT;
      return this;
    }

    Says eventDays(String first, String last) {
      starts = LocalDate.parse(first);
      ends = LocalDate.parse(last);
      return event();
    }

    Says place(String stated) {
      place = stated;
      return this;
    }

    Says kind(EventKind stated) {
      kind = stated;
      return this;
    }

    Says format(EventFormat stated) {
      format = stated;
      return this;
    }
  }

  private static Says npm() {
    return new Says("npm");
  }

  private static Says github() {
    return new Says("github");
  }

  private record Scenario(String name, List<Says> writes) {}

  private static Scenario scenario(String name, Says... writes) {
    return new Scenario(name, List.of(writes));
  }

  private static final List<Scenario> SCENARIOS =
      List.of(
          scenario("two titles", npm().title("Parity alpha"), github().title("Parity beta")),
          scenario(
              "two types",
              npm().type(ResourceType.LIBRARY),
              github().type(ResourceType.MCP_SERVER)),
          scenario(
              "an observed record states a more specific type",
              npm().type(ResourceType.LIBRARY),
              github().observed().type(ResourceType.MCP_SERVER)),
          scenario("one licence, one spelling", npm().licence("MIT"), github().licence("MIT")),
          scenario(
              "one licence, two spellings", npm().licence("MIT"), github().licence("MIT License")),
          scenario(
              "one licence, two letter cases",
              npm().licence("CC-BY-4.0"),
              github().licence("cc-by-4.0")),
          scenario(
              "one licence spelled out, the other source silent",
              npm().licence("Apache License 2.0"),
              github()),
          scenario("a licence the source does not assert", npm().licence("NOASSERTION"), github()),
          scenario("two licences", npm().licence("MIT"), github().licence("Apache-2.0")),
          scenario(
              "an observed record states another licence",
              npm().licence("MIT"),
              github().observed().licence("Apache-2.0")),
          scenario("one pricing, the other source silent", npm().pricing(Pricing.FREE), github()),
          scenario("two pricings", npm().pricing(Pricing.FREE), github().pricing(Pricing.PAID)),
          scenario(
              "a pricing its source states no more", npm().pricing(Pricing.FREE), github(), npm()),
          scenario(
              "an observed record states another pricing",
              npm().pricing(Pricing.FREE),
              github().observed().pricing(Pricing.PAID)),
          scenario("open source, the other source silent", npm().openSource(true), github()),
          scenario(
              "open source for one source, closed for the other",
              npm().openSource(true),
              github().openSource(false)),
          scenario(
              "open source its source states no more", npm().openSource(true), github(), npm()),
          scenario(
              "an observed record states it is closed",
              npm().openSource(true),
              github().observed().openSource(false)),
          scenario(
              "two event calendars",
              npm().eventDays("2027-03-12", "2027-03-14"),
              github().eventDays("2027-03-13", "2027-03-15")),
          scenario(
              "archived for one source, the other silent",
              npm().vitality(new Vitality(true)),
              github()),
          scenario(
              "archived for one source, alive for the other",
              npm().vitality(new Vitality(true, false)),
              github().vitality(new Vitality(false, false))),
          scenario(
              "an observed record states it is deprecated",
              npm().vitality(new Vitality(false, false, false)),
              github().observed().vitality(new Vitality(null, null, true))),
          scenario(
              "a fork that runs for one source, not running for the other",
              npm().vitality(new Vitality(null, true, null, true)),
              github().vitality(new Vitality(null, null, null, false))),
          scenario(
              "an event place, kind and format one source states",
              npm()
                  .eventDays("2027-03-12", "2027-03-14")
                  .place("Zurich")
                  .kind(EventKind.CONFERENCE)
                  .format(EventFormat.HYBRID),
              github().eventDays("2027-03-12", "2027-03-14")),
          scenario(
              "two event places, kinds and formats",
              npm()
                  .eventDays("2027-03-12", "2027-03-14")
                  .place("Zurich")
                  .kind(EventKind.CONFERENCE)
                  .format(EventFormat.IN_PERSON),
              github()
                  .eventDays("2027-03-12", "2027-03-14")
                  .place("Lisbon")
                  .kind(EventKind.HACKATHON)
                  .format(EventFormat.ONLINE)),
          scenario(
              "an event place a source that states no days gives",
              npm().eventDays("2027-03-12", "2027-03-14"),
              github().event().place("Lisbon").kind(EventKind.MEETUP).format(EventFormat.ONLINE)),
          scenario(
              "an event without days, its place, kind and format",
              npm().event().place("Zurich").kind(EventKind.TALK).format(EventFormat.IN_PERSON),
              github().event()));

  private SourceHit hit(Says says, String url) {
    return new SourceHit(
            says.source,
            says.source + ":" + url,
            URI.create(url),
            CanonicalUrl.of(url),
            null,
            says.title,
            "An AI tool " + word,
            ValueOrigin.SOURCE_PROVIDED,
            says.type,
            Audience.BUILD_WITH_AI,
            null,
            Set.of(),
            Set.of(),
            says.licence,
            says.pricing,
            Set.of(),
            says.openSource,
            DateFact.unknown(),
            DateFact.unknown(),
            List.of(),
            now,
            0,
            DateFact.scheduledDay(says.starts, says.source),
            DateFact.scheduledDay(says.ends, says.source),
            says.place,
            says.kind,
            says.format)
        .withVitality(says.vitality);
  }

  private static List<MergedResult> merged(SourceHit hit) {
    Map<String, SourceContext> contexts =
        SOURCES.stream().collect(Collectors.toMap(id -> id, IndexHitAndCardParityIT::context));
    return new ResultMerger().merge(List.of(hit), contexts);
  }

  private static SourceContext context(String id) {
    return new SourceContext(
        new SourceDescriptor(
            id,
            id,
            id,
            true,
            SourceMode.LIVE,
            URI.create("https://" + id + ".example"),
            null,
            null,
            EnumSet.allOf(ResourceType.class),
            Duration.ofSeconds(1),
            Duration.ZERO),
        ServedFrom.LIVE);
  }

  private UUID written(List<Says> writes, String url) {
    writes.forEach(says -> writer.upsert(merged(hit(says, url)), says.origin, SOURCES));
    return ResourceId.of(CanonicalUrl.of(url)).getValue();
  }

  private Map<UUID, SourceHit> indexHits() {
    SourceQuery query =
        new SourceQuery(
            word,
            List.of(),
            null,
            500,
            List.of(word),
            List.of(),
            null,
            null,
            null,
            false,
            Set.of());
    return index.search(query).stream()
        .collect(Collectors.toMap(h -> UUID.fromString(h.getExternalId()), h -> h));
  }

  private String storedEventDays(UUID id) {
    return jdbc.sql(
            "SELECT to_char(event_starts_at AT TIME ZONE 'UTC', 'YYYY-MM-DD') AS s,"
                + " to_char(event_ends_at AT TIME ZONE 'UTC', 'YYYY-MM-DD') AS e"
                + " FROM resource WHERE id = :id")
        .param("id", id)
        .query((rs, i) -> days(day(rs.getString("s")), day(rs.getString("e"))))
        .single();
  }

  private static String eventDays(MergedResult card) {
    return days(day(card.getEventStart()), day(card.getEventEnd()));
  }

  private static String days(String first, String last) {
    return first + " to " + last;
  }

  private static String day(String stored) {
    return Objects.toString(stored, "none");
  }

  private static String day(DateFact fact) {
    return fact.getValue() == null
        ? "none"
        : fact.getValue().atOffset(ZoneOffset.UTC).toLocalDate().toString();
  }

  private static Vitality statedByTheRecordsOf(MergedResult card) {
    List<Vitality> stated = card.getHits().stream().map(SourceHit::getVitality).toList();
    return new Vitality(
        anyStates(stated.stream().map(Vitality::getArchived)),
        anyStates(stated.stream().map(Vitality::getFork)),
        anyStates(stated.stream().map(Vitality::getDeprecated)),
        everyOneStates(stated.stream().map(Vitality::getRunning)));
  }

  private static Boolean anyStates(Stream<Boolean> answers) {
    return answers.filter(Objects::nonNull).reduce(Boolean::logicalOr).orElse(null);
  }

  private static Boolean everyOneStates(Stream<Boolean> answers) {
    return answers.filter(Objects::nonNull).reduce(Boolean::logicalAnd).orElse(null);
  }

  private List<String> differences(String where, UUID id, SourceHit hit, MergedResult card) {
    Map<String, List<Object>> said = new LinkedHashMap<>();
    said.put("title", pair(hit.getTitle(), card.getTitle()));
    said.put("type", pair(hit.getType(), card.getType()));
    said.put("licence", pair(hit.getLicense(), card.getLicense()));
    said.put("pricing", pair(hit.getPricing(), card.getPricing()));
    said.put("open source", pair(hit.getOpenSource(), card.getOpenSource()));
    said.put(
        "event days of the hit",
        pair(days(day(hit.getEventStart()), day(hit.getEventEnd())), eventDays(card)));
    said.put("event days of the row", pair(storedEventDays(id), eventDays(card)));
    said.put("vitality", pair(hit.getVitality(), statedByTheRecordsOf(card)));
    said.put("event place", pair(hit.getLocation(), card.getLocation()));
    said.put("event kind", pair(hit.getEventKind(), card.getEventKind()));
    said.put("event format", pair(hit.getEventFormat(), card.getEventFormat()));
    said.put(
        "whether the sources agree on the event days",
        pair(
            List.of(hit.getEventStart().getStatus(), hit.getEventEnd().getStatus()),
            List.of(card.getEventStart().getStatus(), card.getEventEnd().getStatus())));
    List<String> differences = new ArrayList<>();
    said.forEach((field, sides) -> {
      if (!Objects.equals(sides.get(0), sides.get(1))) {
        differences.add(
            "%s: %s is %s in the index, %s on the card"
                .formatted(where, field, sides.get(0), sides.get(1)));
      }
    });
    return differences;
  }

  private static List<Object> pair(Object index, Object card) {
    return List.of(Objects.toString(index, "none"), Objects.toString(card, "none"));
  }

  @Test
  void theIndexHitAndTheCardSayTheSameAboutAResourceOfTwoSourcesExceptForTheKnownGaps() {
    Map<UUID, String> resources = new LinkedHashMap<>();
    int n = 0;
    for (Scenario scenario : SCENARIOS) {
      String url = "https://github.com/acme/parity-" + run + "-" + n++;
      resources.put(written(scenario.writes(), url), scenario.name() + ", " + AS_WRITTEN);
      resources.put(
          written(scenario.writes().reversed(), url + "-reversed"),
          scenario.name() + ", " + REVERSED);
    }

    Map<UUID, SourceHit> hits = indexHits();
    List<String> differences = new ArrayList<>();
    resources.forEach((id, where) -> {
      assertThat(hits).as("the index finds %s", where).containsKey(id);
      MergedResult card = details.find(id).orElseThrow().card();
      differences.addAll(differences(where, id, hits.get(id), card));
    });

    assertThat(differences)
        .as("a new difference is a regression; a closed one leaves the list of known gaps")
        .containsExactlyInAnyOrderElementsOf(knownGaps());
  }
}
