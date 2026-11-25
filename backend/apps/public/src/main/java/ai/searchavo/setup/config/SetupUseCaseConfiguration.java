package ai.searchavo.setup.config;

import ai.searchavo.catalog.usecase.GetResourceDetail;
import ai.searchavo.setup.SetupGuides;
import ai.searchavo.setup.ToolGuides;
import ai.searchavo.setup.usecase.GetResourceSetup;
import ai.searchavo.setup.usecase.GetToolGuide;
import ai.searchavo.setup.usecase.ShowSetupGuidesLeft;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
class SetupUseCaseConfiguration {

  @Bean
  GetResourceSetup getResourceSetup(GetResourceDetail details, SetupGuides guides) {
    return new GetResourceSetup(details, guides);
  }

  @Bean
  GetToolGuide getToolGuide(GetResourceDetail details, ToolGuides toolGuides) {
    return new GetToolGuide(details, toolGuides);
  }

  @Bean
  ShowSetupGuidesLeft showSetupGuidesLeft(ToolGuides toolGuides) {
    return new ShowSetupGuidesLeft(toolGuides);
  }
}
