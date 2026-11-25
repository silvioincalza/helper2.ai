package ai.searchavo.research.internal;

import ai.searchavo.research.ResearchModel;
import ai.searchavo.research.ResearchTool;
import java.util.List;

final class UnavailableResearchModel implements ResearchModel {

  @Override
  public String model() {
    return "none";
  }

  @Override
  public Turn next(List<Message> conversation, List<ResearchTool> tools) {
    throw new Unavailable("research is off or no model is configured");
  }
}
