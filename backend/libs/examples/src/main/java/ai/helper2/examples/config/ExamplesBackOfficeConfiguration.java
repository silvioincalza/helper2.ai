package ai.helper2.examples.config;

import ai.helper2.examples.DraftQueue;
import ai.helper2.examples.DraftSources;
import ai.helper2.examples.ExampleDrafter;
import ai.helper2.examples.ExampleOrigin;
import ai.helper2.examples.ExampleSearch;
import ai.helper2.examples.ExampleStatus;
import ai.helper2.examples.HomeExamples;
import ai.helper2.examples.ResourceTitles;
import ai.helper2.examples.UnitOfWork;
import ai.helper2.examples.internal.ExamplesBackOfficeAdapters;
import ai.helper2.examples.usecase.AcceptExampleDraft;
import ai.helper2.examples.usecase.AddHomeExample;
import ai.helper2.examples.usecase.ChangeHomeExample;
import ai.helper2.examples.usecase.CheckHomeExamples;
import ai.helper2.examples.usecase.CountHomeExamples;
import ai.helper2.examples.usecase.DraftHomeExamples;
import ai.helper2.examples.usecase.ListAdminHomeExamples;
import ai.helper2.examples.usecase.ReadExampleDraft;
import ai.helper2.examples.usecase.RemoveHomeExample;
import ai.helper2.platform.AuditTrail;
import ai.helper2.shared.AudiencePath;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.modulith.NamedInterface;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

@NamedInterface("backoffice")
@Import(ExamplesBackOfficeAdapters.class)
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
      UnitOfWork examplesUnitOfWork,
      ExamplesProperties properties) {
    return new DraftHomeExamples(
        examples, sources, drafter, queue, audit, examplesUnitOfWork, properties.draftSources());
  }

  @Bean
  AcceptExampleDraft acceptExampleDraft(DraftQueue drafts, AddHomeExample addHomeExample) {
    return new AcceptExampleDraft(drafts, addHomeExample);
  }

  @Bean
  UnitOfWork examplesUnitOfWork(PlatformTransactionManager transactions) {
    TransactionTemplate template = new TransactionTemplate(transactions);
    return work -> template.executeWithoutResult(status -> work.run());
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
