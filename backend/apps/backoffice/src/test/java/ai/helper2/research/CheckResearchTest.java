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
            "repository: acme/notetaker\nlicense: MIT\ndescription: Meeting notes",
            List.of("https://github.com/acme/notetaker")));
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

  private static ResearchSubject repository(String url) {
    return new ResearchSubject(
        UUID.randomUUID(), "RESOURCE_CANDIDATE", url, "notetaker", "LIBRARY", List.of(), null);
  }

  private ResearchResult checkLicence(ResearchSubject subject, String evidenceId, String excerpt) {
    return new CheckResearch()
        .check(
            subject,
            new ResearchAnswer(
                Verdict.KEEP,
                "a library",
                null,
                List.of(new Claim("license", "MIT", List.of(new Cite(evidenceId, excerpt))))),
            evidence);
  }

  @Test
  void aLicenceTheSubjectsOwnRepositoryRecordStatesIsConfirmed() {
    ResearchResult r =
        checkLicence(
            repository("https://github.com/Acme/notetaker/tree/main"),
            "e2",
            "license: MIT\ndescription: Meeting");

    assertThat(attribute(r, "license").status()).isEqualTo(Status.CONFIRMED);
    assertThat(attribute(r, "license").sources()).singleElement().matches(s -> s.authoritative());
  }

  @Test
  void aRepositoryWhoseHomepageNamesTheSubjectIsNotAuthoritativeForIt() {
    ResearchResult r =
        check(
            new Claim(
                "license", "MIT", List.of(new Cite("e2", "license: MIT\ndescription: Meeting"))));

    assertThat(attribute(r, "license").status()).isEqualTo(Status.SINGLE_SOURCE);
    assertThat(attribute(r, "license").sources()).noneMatch(ResearchResult.Source::authoritative);
  }

  @Test
  void aRecordOfAnUnrelatedRepositoryIsNotAuthoritative() {
    evidence.put(
        "e6",
        new Evidence(
            "e6",
            "github_repo",
            "https://github.com/other/recorder",
            "repository: other/recorder\nlicense: MIT\ndescription: Other",
            List.of("https://github.com/other/recorder")));

    ResearchResult r =
        check(
            new Claim(
                "license", "MIT", List.of(new Cite("e6", "license: MIT\ndescription: Other"))));

    assertThat(attribute(r, "license").status()).isEqualTo(Status.SINGLE_SOURCE);
    assertThat(attribute(r, "license").sources()).noneMatch(ResearchResult.Source::authoritative);
  }

  @Test
  void aPackageRecordIsNeverAuthoritativeBecauseItsSourceRepositoryIsSelfDeclared() {
    evidence.put(
        "e6",
        new Evidence(
            "e6",
            "find_package",
            "https://deps.dev/npm/notetaker",
            "package: notetaker\nlicense: MIT\nsource repository: github.com/acme/notetaker"));

    ResearchResult r =
        checkLicence(
            repository("https://github.com/acme/notetaker"),
            "e6",
            "package: notetaker\nlicense: MIT");

    assertThat(attribute(r, "license").status()).isEqualTo(Status.SINGLE_SOURCE);
  }

  @Test
  void aRecordWhosePathClimbsOrHidesASlashIsNotTheSubjectsOwn() {
    for (String record :
        List.of(
            "https://github.com/acme/notetaker/../../other/repo",
            "https://github.com/other/repo/../../acme/notetaker",
            "https://github.com/acme%2fnotetaker/x",
            "https://github.com/acme/notetaker%5c..%5c..%5cother",
            "https://github.com/%2e%2e/acme/notetaker")) {
      evidence.put(
          "e6",
          new Evidence(
              "e6",
              "github_repo",
              "https://github.com/other/repo",
              "repository: other/repo\nlicense: MIT\ndescription: Other",
              List.of(record)));

      ResearchResult r =
          checkLicence(
              repository("https://github.com/acme/notetaker"),
              "e6",
              "license: MIT\ndescription: Other");

      assertThat(attribute(r, "license").status()).as(record).isEqualTo(Status.SINGLE_SOURCE);
    }
  }

  @Test
  void aPageOnASharedHostIsTheItemsOwnSiteOnlyUnderTheSameOwner() {
    assertThat(
            CheckResearch.sameSite(
                "https://github.com/acme/notetaker/blob/main/README.md",
                "https://github.com/acme/notetaker"))
        .isTrue();
    assertThat(
            CheckResearch.sameSite(
                "https://github.com/acme/other", "https://github.com/acme/notetaker"))
        .isFalse();
    assertThat(
            CheckResearch.sameSite(
                "https://medium.com/@someone/acme-review", "https://medium.com/@acme/launch"))
        .isFalse();
    assertThat(
            CheckResearch.sameSite(
                "https://medium.com/@acme/pricing", "https://medium.com/@acme/launch"))
        .isTrue();
    assertThat(
            CheckResearch.sameSite(
                "https://huggingface.co/datasets/acme/x", "https://huggingface.co/datasets/acme/y"))
        .isFalse();
    assertThat(
            CheckResearch.sameSite(
                "https://huggingface.co/acme/model", "https://huggingface.co/acme/model/tree/main"))
        .isTrue();
    assertThat(CheckResearch.sameSite("https://docs.acme.dev/", "https://acme.dev/")).isFalse();
  }

  @Test
  void aPageOfAnotherAuthorOnASharedHostDoesNotConfirmAValue() {
    ResearchSubject post =
        new ResearchSubject(
            UUID.randomUUID(),
            "RESOURCE_CANDIDATE",
            "https://medium.com/@acme/notetaker-launch",
            "Notetaker launch",
            "ARTICLE",
            List.of(),
            null);
    evidence.put(
        "e6",
        new Evidence(
            "e6",
            "read_page",
            "https://medium.com/@someone/acme-notetaker-review",
            "Acme Notetaker records your meetings, says someone else."));

    ResearchResult r =
        new CheckResearch()
            .check(
                post,
                new ResearchAnswer(
                    Verdict.KEEP,
                    "an article",
                    null,
                    List.of(
                        new Claim(
                            "title",
                            "Acme Notetaker",
                            List.of(new Cite("e6", "Acme Notetaker records your meetings"))))),
                evidence);

    assertThat(attribute(r, "title").status()).isEqualTo(Status.SINGLE_SOURCE);
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
