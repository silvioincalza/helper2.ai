package ai.helper2.research.internal;

import ai.helper2.research.ResearchModel;
import ai.helper2.research.ResearchTool;
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
