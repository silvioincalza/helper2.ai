package ai.helper2.identity.config;

import ai.helper2.identity.AuditLog;
import ai.helper2.identity.Bookmarks;
import ai.helper2.identity.Bookmarks.Bookmark;
import ai.helper2.identity.Users;
import ai.helper2.identity.usecase.AddBookmark;
import ai.helper2.identity.usecase.ChangePreferences;
import ai.helper2.identity.usecase.DeleteMyAccount;
import ai.helper2.identity.usecase.ListAuditEntries;
import ai.helper2.identity.usecase.ListBookmarks;
import ai.helper2.identity.usecase.MergeLocalBookmarks;
import ai.helper2.identity.usecase.PurgeExpiredData;
import ai.helper2.identity.usecase.RemoveBookmark;
import ai.helper2.identity.usecase.ShowPreferences;
import ai.helper2.platform.quota.QuotaCounter;
import ai.helper2.platform.quota.QuotaKeys;
import java.time.Clock;
import java.util.List;
import java.util.UUID;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.annotation.Transactional;

/**
 * Identity's use cases: plain classes over the module's ports (ADR 0002). A use case that must be
 * all or nothing gets its transaction here, on a subclass, so the use case itself stays free of
 * Spring.
 */
@Configuration(proxyBeanMethods = false)
class IdentityUseCaseConfiguration {

  @Bean
  AddBookmark addBookmark(Bookmarks bookmarks) {
    return new TransactionalAddBookmark(bookmarks);
  }

  @Bean
  MergeLocalBookmarks mergeLocalBookmarks(Bookmarks bookmarks) {
    return new TransactionalMergeLocalBookmarks(bookmarks);
  }

  @Bean
  ListBookmarks listBookmarks(Bookmarks bookmarks) {
    return new ListBookmarks(bookmarks);
  }

  @Bean
  RemoveBookmark removeBookmark(Bookmarks bookmarks) {
    return new RemoveBookmark(bookmarks);
  }

  @Bean
  ShowPreferences showPreferences(Users users) {
    return new ShowPreferences(users);
  }

  @Bean
  ChangePreferences changePreferences(Users users) {
    return new ChangePreferences(users);
  }

  @Bean
  DeleteMyAccount deleteMyAccount(Users users, QuotaCounter quota, QuotaKeys quotaKeys) {
    return new DeleteMyAccount(users, quota, quotaKeys);
  }

  @Bean
  ListAuditEntries listAuditEntries(AuditLog log) {
    return new ListAuditEntries(log);
  }

  @Bean
  PurgeExpiredData identityPurgeExpiredData(
      Users users, AuditLog audit, Clock clock, IdentityRetentionProperties periods) {
    return new PurgeExpiredData(users, audit, clock, periods);
  }

  /** The insert and the cap check in one transaction: a refused add leaves nothing behind. */
  static class TransactionalAddBookmark extends AddBookmark {

    TransactionalAddBookmark(Bookmarks bookmarks) {
      super(bookmarks);
    }

    @Override
    @Transactional
    public Bookmark add(UUID userId, Bookmark bookmark) {
      return super.add(userId, bookmark);
    }
  }

  /** All or nothing, as {@link TransactionalAddBookmark}. */
  static class TransactionalMergeLocalBookmarks extends MergeLocalBookmarks {

    TransactionalMergeLocalBookmarks(Bookmarks bookmarks) {
      super(bookmarks);
    }

    @Override
    @Transactional
    public List<Bookmark> merge(UUID userId, List<Bookmark> local) {
      return super.merge(userId, local);
    }
  }
}
