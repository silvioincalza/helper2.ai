package ai.helper2.identity.config;

import ai.helper2.identity.AuditLog;
import ai.helper2.identity.Bookmarks;
import ai.helper2.identity.Users;
import ai.helper2.identity.usecase.AddBookmark;
import ai.helper2.identity.usecase.ChangePreferences;
import ai.helper2.identity.usecase.DeleteMyAccount;
import ai.helper2.identity.usecase.ListAuditEntries;
import ai.helper2.identity.usecase.ListBookmarks;
import ai.helper2.identity.usecase.MergeLocalBookmarks;
import ai.helper2.identity.usecase.RemoveBookmark;
import ai.helper2.identity.usecase.ShowPreferences;
import ai.helper2.shared.UnitOfWork;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
class IdentityUseCaseConfiguration {

  @Bean
  AddBookmark addBookmark(Bookmarks bookmarks, UnitOfWork unitOfWork) {
    return new AddBookmark(bookmarks, unitOfWork);
  }

  @Bean
  MergeLocalBookmarks mergeLocalBookmarks(Bookmarks bookmarks, UnitOfWork unitOfWork) {
    return new MergeLocalBookmarks(bookmarks, unitOfWork);
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
  DeleteMyAccount deleteMyAccount(Users users) {
    return new DeleteMyAccount(users);
  }

  @Bean
  ListAuditEntries listAuditEntries(AuditLog log) {
    return new ListAuditEntries(log);
  }
}
