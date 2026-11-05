package ai.helper2.guide.internal;

import ai.helper2.guide.ClientQuota;
import ai.helper2.guide.usecase.AskGuide;
import ai.helper2.identity.VisitorLimits;
import ai.helper2.search.Asker;
import ai.helper2.search.Feature;
import ai.helper2.search.WebSearchesLeft;
import ai.helper2.search.usecase.SearchResources;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * The daily limits GET /me shows: the guide's own questions per client ({@link ClientQuota}, none
 * without a model: then the guide answers from search only and spends nothing) and the web searches
 * of search and the guide, as each answer shows them ({@link SearchResources#webSearchesLeft}).
 * Reads only; nothing is counted.
 */
@Component
class GuideVisitorLimits implements VisitorLimits {

  private final ClientQuota questions;
  private final AskGuide guide;
  private final SearchResources search;

  GuideVisitorLimits(ClientQuota questions, AskGuide guide, SearchResources search) {
    this.questions = questions;
    this.guide = guide;
    this.search = search;
  }

  @Override
  public Today of(String client, @Nullable UUID userId) {
    return new Today(
        guideQuestions(client),
        webSearches(new Asker(Feature.SEARCH, client, userId)),
        webSearches(new Asker(Feature.GUIDE, client, userId)));
  }

  private @Nullable Allowance guideQuestions(String client) {
    if (!guide.getAnswersWithModel()) {
      return null;
    }
    return new Allowance(questions.getLimit(), questions.left(client));
  }

  private @Nullable Allowance webSearches(Asker asker) {
    WebSearchesLeft left = search.webSearchesLeft(asker);
    return left == null ? null : new Allowance(left.getLimit(), left.getRemaining());
  }
}
