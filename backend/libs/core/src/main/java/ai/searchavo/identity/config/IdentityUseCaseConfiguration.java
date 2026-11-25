package ai.searchavo.identity.config;

import ai.searchavo.identity.AuditLog;
import ai.searchavo.identity.Bookmarks;
import ai.searchavo.identity.Users;
import ai.searchavo.identity.usecase.AddBookmark;
import ai.searchavo.identity.usecase.ChangePreferences;
import ai.searchavo.identity.usecase.DeleteMyAccount;
import ai.searchavo.identity.usecase.ListAuditEntries;
import ai.searchavo.identity.usecase.ListBookmarks;
import ai.searchavo.identity.usecase.MergeLocalBookmarks;
import ai.searchavo.identity.usecase.RemoveBookmark;
import ai.searchavo.identity.usecase.ShowPreferences;
import ai.searchavo.shared.UnitOfWork;
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
