package ai.helper2.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ai.helper2.search.SourceHit;
import ai.helper2.shared.DateFact;
import ai.helper2.shared.EventFormat;
import ai.helper2.shared.EventKind;
import ai.helper2.shared.ResourceType;
import com.jayway.jsonpath.JsonPath;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Home page "Next events": only events whose source states when they take place, soonest first, not ended. */
class UpcomingEventsApiIT extends ApiTestBase {

    private static SourceHit event(String slug, String title, Integer startInDays, Integer endInDays, int rank) {
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        SourceHit h = fakeHit("https://events.example.org/" + slug, title, "An AI event", ResourceType.EVENT, null, null, null, rank);
        return h.copy(h.getSourceId(), h.getExternalId(), h.getUrl(), h.getCanonicalUrl(), h.getRepositoryUrl(), h.getTitle(),
            h.getDescription(), h.getDescriptionOrigin(), h.getType(), h.getAudience(), h.getTechLevel(), h.getUseCases(), h.getTags(),
            h.getLicense(), h.getPricing(), h.getPlatforms(), h.getOpenSource(), h.getPublished(), h.getUpdated(), h.getPopularity(),
            h.getRetrievedAt(), h.getRank(),
            startInDays == null ? DateFact.unknown() : DateFact.scheduledDay(today.plusDays(startInDays), "fake-live"),
            endInDays == null ? DateFact.unknown() : DateFact.scheduledDay(today.plusDays(endInDays), "fake-live"),
            "Paris, France", EventKind.HACKATHON, EventFormat.ONLINE);
    }

    @Test
    void soonestFirstIncludingRunningAndNeverUndatedOrEnded() throws Exception {
        FAKE.set(q -> List.of(
            event("later", "evtconf Later Summit", 40, 42, 0),
            event("soon", "evtconf Soon Expo", 5, null, 1),
            event("running", "evtconf Running Week", -2, 2, 2),
            event("ended", "evtconf Ended Forum", -10, -8, 3),
            event("undated", "evtconf Undated Meetup", null, null, 4)));
        mvc.perform(get("/api/v1/search").param("q", "evtconf")).andExpect(status().isOk());

        // The industry- and community-event seeds are in the list too: look at this test's own events only.
        eventually(() -> {
            String upcoming = mvc.perform(get("/api/v1/events/upcoming").param("size", "60"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
            List<String> own = JsonPath.<List<String>>read(upcoming, "$.items[*].title").stream().filter(t -> t.startsWith("evtconf")).toList();
            assertThat(own).containsSubsequence("evtconf Running Week", "evtconf Soon Expo", "evtconf Later Summit");
        });
        String body = mvc.perform(get("/api/v1/events/upcoming").param("size", "60"))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        ContractValidator.assertMatches("UpcomingEventsResponse", body);

        List<String> titles = JsonPath.<List<String>>read(body, "$.items[*].title").stream().filter(t -> t.startsWith("evtconf")).toList();
        assertThat(titles).containsSubsequence("evtconf Running Week", "evtconf Soon Expo", "evtconf Later Summit")
            .doesNotContain("evtconf Ended Forum", "evtconf Undated Meetup");
        List<String> precision = JsonPath.read(body, "$.items[?(@.title=='evtconf Soon Expo')].timeline.eventStart.precision");
        assertThat(precision).containsExactly("DAY");
        List<String> location = JsonPath.read(body, "$.items[?(@.title=='evtconf Soon Expo')].location");
        assertThat(location).containsExactly("Paris, France");
        List<String> kind = JsonPath.read(body, "$.items[?(@.title=='evtconf Soon Expo')].eventKind");
        assertThat(kind).containsExactly("HACKATHON");
        List<String> format = JsonPath.read(body, "$.items[?(@.title=='evtconf Soon Expo')].eventFormat");
        assertThat(format).containsExactly("ONLINE");
    }
}
