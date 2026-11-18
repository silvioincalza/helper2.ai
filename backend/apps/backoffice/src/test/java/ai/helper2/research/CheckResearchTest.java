package ai.helper2.research;

import static org.assertj.core.api.Assertions.assertThat;

import ai.helper2.research.ResearchAnswer.Cite;
import ai.helper2.research.ResearchAnswer.Claim;
import ai.helper2.research.ResearchAnswer.Verdict;
import ai.helper2.research.ResearchResult.Attribute;
import ai.helper2.research.ResearchResult.Status;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class CheckResearchTest {

  final ResearchSubject subject =
      new ResearchSubject(
          UUID.randomUUID(),
          "RESOURCE_CANDIDATE",
          "https://www.acme.dev/notetaker",
          "AI Notetaker | Transcripts | Acme",
          "PRODUCT",
          List.of("meetings"),
          null);

  final Map<String, Evidence> evidence = new LinkedHashMap<>();

  CheckResearchTest() {
    evidence.put(
        "e1",
        new Evidence(
            "e1",
            "read_page",
            "https://acme.dev/notetaker",
            "Acme Notetaker records your meetings. Plans: a free plan for small teams and Pro for $12."));
    evidence.put(
        "e2",
        new Evidence(
            "e2",
            "github_repo",
            "https://github.com/acme/notetaker",
            "repository: acme/notetaker\nlicense: MIT\ndescription: Meeting notes"));
    evidence.put(
        "e3",
        new Evidence(
            "e3",
            "read_page",
            "https://news.example/acme",
            "Acme Notetaker is a tool that records your meetings and writes notes."));
    evidence.put(
        "e4",
        new Evidence(
            "e4",
            "read_page",
            "https://blog.example/acme",
            "We tried Acme Notetaker records your meetings for a week."));
  }

  private ResearchResult check(Claim... claims) {
    return new CheckResearch()
        .check(
            subject,
            new ResearchAnswer(Verdict.KEEP, "a product", null, List.of(claims)),
            evidence);
  }

  private static Attribute attribute(ResearchResult r, String name) {
    return r.attributes().stream().filter(a -> a.name().equals(name)).findFirst().orElseThrow();
  }

  @Test
  void aLicenceTheRepositoryRecordStatesIsConfirmed() {
    ResearchResult r =
        check(
            new Claim(
                "license", "MIT", List.of(new Cite("e2", "license: MIT\ndescription: Meeting"))));

    assertThat(attribute(r, "license").status()).isEqualTo(Status.CONFIRMED);
    assertThat(attribute(r, "license").sources()).singleElement().matches(s -> s.authoritative());
  }

  @Test
  void anExcerptThatIsNotInTheEvidenceIsDroppedAndTheValueIsNotFound() {
    ResearchResult r =
        check(
            new Claim("license", "MIT", List.of(new Cite("e2", "Released under the MIT licence"))));

    assertThat(r.attributes()).noneMatch(a -> a.name().equals("license"));
    assertThat(r.dropped()).singleElement().asString().contains("excerpt not found");
  }

  @Test
  void anExcerptMatchesEvidenceWhoseAngleBracketsWereEscapedForTheModel() {
    evidence.put(
        "e5",
        new Evidence(
            "e5",
            "read_page",
            "https://acme.dev/docs",
            "Acme Notetaker works for x ‹ y teams today."));

    ResearchResult r =
        check(
            new Claim(
                "title",
                "Acme Notetaker",
                List.of(new Cite("e5", "Acme Notetaker works for x < y teams"))));

    assertThat(r.dropped()).isEmpty();
    assertThat(attribute(r, "title").sources()).hasSize(1);
  }

  @Test
  void aStatedFieldNeedsAnExcerptThatStatesTheValue() {
    ResearchResult r =
        check(
            new Claim(
                "license",
                "Apache-2.0",
                List.of(new Cite("e2", "license: MIT\ndescription: Meeting"))));

    assertThat(r.dropped()).singleElement().asString().contains("does not state Apache-2.0");
  }

  @Test
  void aValueTheCatalogDoesNotAllowIsDropped() {
    ResearchResult r =
        check(
            new Claim("pricing", "CHEAP", List.of(new Cite("e1", "a free plan for small teams"))));

    assertThat(r.dropped()).singleElement().asString().contains("not an allowed value");
  }

  @Test
  void theProductsOwnPageConfirmsItsTitle() {
    ResearchResult r =
        check(
            new Claim(
                "title",
                "Acme Notetaker",
                List.of(new Cite("e1", "Acme Notetaker records your meetings"))));

    Attribute title = attribute(r, "title");
    assertThat(title.status()).isEqualTo(Status.CONFIRMED);
    assertThat(title.current()).isEqualTo("AI Notetaker | Transcripts | Acme");
    assertThat(title.proposed()).containsExactly("Acme Notetaker");
  }

  @Test
  void oneOtherSiteIsASingleSourceAndTwoOtherSitesConfirm() {
    Claim one =
        new Claim(
            "tags",
            "meetings, notes",
            List.of(new Cite("e3", "records your meetings and writes notes")));
    assertThat(attribute(check(one), "tags").status()).isEqualTo(Status.SINGLE_SOURCE);

    Claim two =
        new Claim(
            "tags",
            "meetings, notes",
            List.of(
                new Cite("e3", "records your meetings and writes notes"),
                new Cite("e4", "Acme Notetaker records your meetings")));
    assertThat(attribute(check(two), "tags").status()).isEqualTo(Status.CONFIRMED);
  }

  @Test
  void twoSupportedValuesAreAConflict() {
    ResearchResult r =
        check(
            new Claim(
                "title",
                "Acme Notetaker",
                List.of(new Cite("e1", "Acme Notetaker records your meetings"))),
            new Claim(
                "title",
                "Notetaker",
                List.of(new Cite("e3", "Acme Notetaker is a tool that records"))));

    Attribute title = attribute(r, "title");
    assertThat(title.status()).isEqualTo(Status.CONFLICT);
    assertThat(title.proposed()).containsExactly("Acme Notetaker", "Notetaker");
  }

  @Test
  void anUnknownEvidenceIdAndAnUnknownAttributeAreDropped() {
    ResearchResult r =
        check(
            new Claim(
                "title", "Acme", List.of(new Cite("e9", "Acme Notetaker records your meetings"))),
            new Claim("stars", "12", List.of()));

    assertThat(r.dropped()).hasSize(2);
    assertThat(attribute(r, "title").status()).isEqualTo(Status.NOT_FOUND);
  }
}
