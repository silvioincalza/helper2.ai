package ai.helper2.testing;

import java.lang.annotation.ElementType;
import java.lang.annotation.Inherited;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.springframework.test.context.TestPropertySource;

@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Inherited
@TestPropertySource(
    properties = {
      "helper2.sources.github.enabled=false",
      "helper2.sources.huggingface.enabled=false",
      "helper2.sources.npm.enabled=false",
      "helper2.sources.hacker-news.enabled=false",
      "helper2.sources.mcp-registry.enabled=false",
      "helper2.sources.arxiv.enabled=false",
      "helper2.sources.live-web.enabled=false",
      "helper2.sources.hf-trending.enabled=false",
      "helper2.sources.live-web-scout.enabled=false",
      "helper2.sources.course-scout.enabled=false",
      "helper2.sources.linkedin-scout.enabled=false",
      "helper2.sources.live-web-events-scout.enabled=false",
      "helper2.sources.discovery.enabled=false",
      "helper2.sources.github-topic-leaders.enabled=false",
      "helper2.sources.github-sdks.enabled=false",
      "helper2.sources.github-awesome.enabled=false",
      "helper2.sources.hf-provider-orgs.enabled=false",
      "helper2.sources.ai-deadlines.enabled=false",
      "helper2.sources.news-feeds.enabled=false",
      "helper2.sources.pypi.enabled=false",
    })
public @interface SourcesOff {}
