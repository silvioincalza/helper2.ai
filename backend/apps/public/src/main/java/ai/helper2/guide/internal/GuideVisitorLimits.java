package ai.helper2.guide.internal;

import ai.helper2.guide.ClientQuota;
import ai.helper2.guide.usecase.AskGuide;
import ai.helper2.identity.VisitorLimits;
import ai.helper2.search.Asker;
import ai.helper2.search.Feature;
import ai.helper2.search.WebSearchesLeft;
import ai.helper2.search.usecase.SearchResources;
import ai.helper2.setup.SetupGuidesLeft;
import ai.helper2.setup.usecase.ShowSetupGuidesLeft;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

@Component
class GuideVisitorLimits implements VisitorLimits {

  private final ClientQuota questions;
  private final AskGuide guide;
  private final SearchResources search;
  private final ShowSetupGuidesLeft setupGuides;

  GuideVisitorLimits(
      ClientQuota questions,
      AskGuide guide,
      SearchResources search,
      ShowSetupGuidesLeft setupGuides) {
    this.questions = questions;
    this.guide = guide;
    this.search = search;
    this.setupGuides = setupGuides;
  }

  @Override
  public Today of(String client) {
    return new Today(
        guideQuestions(client),
        setupGuides(client),
        webSearches(new Asker(Feature.SEARCH, client)),
        webSearches(new Asker(Feature.GUIDE, client)));
  }

  private @Nullable Allowance guideQuestions(String client) {
    if (!guide.getAnswersWithModel()) {
      return null;
    }
    return new Allowance(questions.getLimit(), questions.left(client));
  }

  private @Nullable Allowance setupGuides(String client) {
    SetupGuidesLeft left = setupGuides.of(client);
    return left == null ? null : new Allowance(left.getLimit(), left.getRemaining());
  }

  private @Nullable Allowance webSearches(Asker asker) {
    WebSearchesLeft left = search.webSearchesLeft(asker);
    return left == null ? null : new Allowance(left.getLimit(), left.getRemaining());
  }
}
