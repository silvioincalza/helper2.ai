package ai.helper2.sources.liveweb.scout;

import static org.assertj.core.api.Assertions.assertThat;

import ai.helper2.shared.CanonicalUrl;
import org.junit.jupiter.api.Test;

class LinkedInPostsTest {

  @Test
  void a_post_or_a_feed_update_on_linkedin_is_a_post() {
    assertThat(
            LinkedInPosts.isPost(
                CanonicalUrl.of(
                    "https://www.linkedin.com/posts/aimlevents_agentic-days-activity-1")))
        .isTrue();
    assertThat(
            LinkedInPosts.isPost(
                CanonicalUrl.of("https://www.linkedin.com/feed/update/urn:li:activity:1")))
        .isTrue();
    assertThat(LinkedInPosts.isPost(CanonicalUrl.of("https://www.linkedin.com/company/acme")))
        .isFalse();
    assertThat(LinkedInPosts.isPost(CanonicalUrl.of("https://www.agenticdays.com/posts/x")))
        .isFalse();
  }

  @Test
  void the_one_link_that_is_not_social_is_the_event_site() {
    String post =
        "Join us! [Agentic Days](https://www.agenticdays.com/). Tickets: https://lnkd.in/abc"
            + " Follow https://x.com/agentic and https://www.linkedin.com/company/acme.";

    assertThat(LinkedInPosts.eventSite(post))
        .hasValueSatisfying(u -> assertThat(u).hasToString("https://www.agenticdays.com/"));
  }

  @Test
  void two_different_sites_or_none_give_no_event_site() {
    assertThat(LinkedInPosts.eventSite("See https://a.example/ and https://b.example/")).isEmpty();
    assertThat(LinkedInPosts.eventSite("Only https://lnkd.in/abc here")).isEmpty();
    assertThat(LinkedInPosts.eventSite("http://plain.example/ is not https")).isEmpty();
  }

  @Test
  void the_same_site_twice_is_one_site() {
    assertThat(
            LinkedInPosts.eventSite(
                "https://www.agenticdays.com/ and again https://www.agenticdays.com/."))
        .isPresent();
  }

  @Test
  void the_event_title_is_the_part_before_the_first_bar() {
    assertThat(
            LinkedInPosts.eventTitle(
                "Agentic Engineering Days Zurich 2026 | The AI Coding & Agents Conference | AI & ML Events"))
        .isEqualTo("Agentic Engineering Days Zurich 2026");
    assertThat(LinkedInPosts.eventTitle("Plain title")).isEqualTo("Plain title");
  }
}
