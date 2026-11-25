package ai.searchavo.examples.config;

import ai.searchavo.examples.DraftQueue;
import ai.searchavo.examples.DraftSources;
import ai.searchavo.examples.ExampleDrafter;
import ai.searchavo.examples.ExampleOrigin;
import ai.searchavo.examples.ExampleSearch;
import ai.searchavo.examples.ExampleStatus;
import ai.searchavo.examples.HomeExamples;
import ai.searchavo.examples.ResourceTitles;
import ai.searchavo.examples.usecase.AcceptExampleDraft;
import ai.searchavo.examples.usecase.AddHomeExample;
import ai.searchavo.examples.usecase.ChangeHomeExample;
import ai.searchavo.examples.usecase.CheckHomeExamples;
import ai.searchavo.examples.usecase.CountHomeExamples;
import ai.searchavo.examples.usecase.DraftHomeExamples;
import ai.searchavo.examples.usecase.ListAdminHomeExamples;
import ai.searchavo.examples.usecase.ReadExampleDraft;
import ai.searchavo.examples.usecase.RemoveHomeExample;
import ai.searchavo.platform.AuditTrail;
import ai.searchavo.shared.AudiencePath;
import ai.searchavo.shared.UnitOfWork;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.context.annotation.Bean;
import org.springframework.modulith.NamedInterface;
import org.springframework.transaction.annotation.Transactional;

@NamedInterface("backoffice")
@EnableExamplesBackOfficeAdapters
public class ExamplesBackOfficeConfiguration {

  @Bean
  ListAdminHomeExamples listAdminHomeExamples(HomeExamples examples, ResourceTitles titles) {
    return new ListAdminHomeExamples(examples, titles);
  }

  @Bean
  CountHomeExamples countHomeExamples(HomeExamples examples) {
    return new CountHomeExamples(examples);
  }

  @Bean
  ReadExampleDraft readExampleDraft(DraftQueue drafts) {
    return new ReadExampleDraft(drafts);
  }

  @Bean
  AddHomeExample addHomeExample(HomeExamples examples, ResourceTitles titles, AuditTrail audit) {
    return new TransactionalAddHomeExample(examples, titles, audit);
  }

  @Bean
  ChangeHomeExample changeHomeExample(
      HomeExamples examples, ResourceTitles titles, AuditTrail audit) {
    return new TransactionalChangeHomeExample(examples, titles, audit);
  }

  @Bean
  RemoveHomeExample removeHomeExample(HomeExamples examples, AuditTrail audit) {
    return new TransactionalRemoveHomeExample(examples, audit);
  }

  @Bean
  CheckHomeExamples checkHomeExamples(
      HomeExamples examples, ExampleSearch search, Clock clock, MeterRegistry meters) {
    return new CheckHomeExamples(examples, search, clock, meters);
  }

  @Bean
  DraftHomeExamples draftHomeExamples(
      HomeExamples examples,
      DraftSources sources,
      ExampleDrafter drafter,
      DraftQueue queue,
      AuditTrail audit,
      UnitOfWork unitOfWork,
      ExamplesProperties properties) {
    return new DraftHomeExamples(
        examples, sources, drafter, queue, audit, unitOfWork, properties.draftSources());
  }

  @Bean
  AcceptExampleDraft acceptExampleDraft(DraftQueue drafts, AddHomeExample addHomeExample) {
    return new AcceptExampleDraft(drafts, addHomeExample);
  }

  static class TransactionalAddHomeExample extends AddHomeExample {

    TransactionalAddHomeExample(HomeExamples examples, ResourceTitles titles, AuditTrail audit) {
      super(examples, titles, audit);
    }

    @Override
    @Transactional
    public Outcome add(
        String adminId, String label, String query, AudiencePath path, List<UUID> expected) {
      return super.add(adminId, label, query, path, expected);
    }

    @Override
    @Transactional
    public Outcome add(
        String adminId,
        String label,
        String query,
        AudiencePath path,
        List<UUID> expected,
        ExampleOrigin origin,
        @Nullable String model) {
      return super.add(adminId, label, query, path, expected, origin, model);
    }
  }

  static class TransactionalChangeHomeExample extends ChangeHomeExample {

    TransactionalChangeHomeExample(HomeExamples examples, ResourceTitles titles, AuditTrail audit) {
      super(examples, titles, audit);
    }

    @Override
    @Transactional
    public Outcome change(
        String adminId,
        UUID id,
        String label,
        String query,
        AudiencePath path,
        List<UUID> expected,
        ExampleStatus status,
        int version) {
      return super.change(adminId, id, label, query, path, expected, status, version);
    }
  }

  static class TransactionalRemoveHomeExample extends RemoveHomeExample {

    TransactionalRemoveHomeExample(HomeExamples examples, AuditTrail audit) {
      super(examples, audit);
    }

    @Override
    @Transactional
    public boolean remove(String adminId, UUID id) {
      return super.remove(adminId, id);
    }
  }
}
