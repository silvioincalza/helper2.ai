package ai.helper2.identity.web;

import ai.helper2.api.generated.MeApi;
import ai.helper2.api.generated.model.BookmarkDto;
import ai.helper2.api.generated.model.BookmarkRequestDto;
import ai.helper2.api.generated.model.DailyAllowanceDto;
import ai.helper2.api.generated.model.LimitsDto;
import ai.helper2.api.generated.model.LimitsWebSearchesDto;
import ai.helper2.api.generated.model.MeDto;
import ai.helper2.api.generated.model.MeLoginProvidersInnerDto;
import ai.helper2.api.generated.model.MonetisationDto;
import ai.helper2.api.generated.model.PreferencesChangeDto;
import ai.helper2.api.generated.model.PreferencesDto;
import ai.helper2.api.generated.model.ResourceTypeDto;
import ai.helper2.api.generated.model.WebSearchesDto;
import ai.helper2.identity.Bookmarks.Bookmark;
import ai.helper2.identity.Users.Preferences;
import ai.helper2.identity.VisitorLimits;
import ai.helper2.identity.internal.CurrentUser;
import ai.helper2.identity.usecase.AddBookmark;
import ai.helper2.identity.usecase.ChangePreferences;
import ai.helper2.identity.usecase.DeleteMyAccount;
import ai.helper2.identity.usecase.ListBookmarks;
import ai.helper2.identity.usecase.MergeLocalBookmarks;
import ai.helper2.identity.usecase.RemoveBookmark;
import ai.helper2.identity.usecase.ShowPreferences;
import ai.helper2.monetisation.Monetisation;
import ai.helper2.platform.ApiProblem;
import ai.helper2.platform.BadRequestException;
import ai.helper2.platform.ClientKey;
import ai.helper2.platform.ProblemType;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.web.authentication.logout.SecurityContextLogoutHandler;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** /api/v1/me: maps requests to the identity use cases, one per action. */
@RestController
@RequestMapping("/api/v1")
class MeController implements MeApi {

  private final CurrentUser currentUser;
  private final ListBookmarks listBookmarks;
  private final AddBookmark addBookmark;
  private final RemoveBookmark removeBookmark;
  private final MergeLocalBookmarks mergeLocalBookmarks;
  private final DeleteMyAccount deleteMyAccount;
  private final ShowPreferences showPreferences;
  private final ChangePreferences changePreferences;
  private final ObjectProvider<ClientRegistrationRepository> registrations;
  private final VisitorLimits limits;
  private final Monetisation monetisation;
  private final Clock clock;
  private final HttpServletRequest request;
  private final HttpServletResponse response;

  MeController(
      CurrentUser currentUser,
      ListBookmarks listBookmarks,
      AddBookmark addBookmark,
      RemoveBookmark removeBookmark,
      MergeLocalBookmarks mergeLocalBookmarks,
      DeleteMyAccount deleteMyAccount,
      ShowPreferences showPreferences,
      ChangePreferences changePreferences,
      ObjectProvider<ClientRegistrationRepository> registrations,
      VisitorLimits limits,
      Monetisation monetisation,
      Clock clock,
      HttpServletRequest request,
      HttpServletResponse response) {
    this.currentUser = currentUser;
    this.listBookmarks = listBookmarks;
    this.addBookmark = addBookmark;
    this.removeBookmark = removeBookmark;
    this.mergeLocalBookmarks = mergeLocalBookmarks;
    this.deleteMyAccount = deleteMyAccount;
    this.showPreferences = showPreferences;
    this.changePreferences = changePreferences;
    this.registrations = registrations;
    this.limits = limits;
    this.monetisation = monetisation;
    this.clock = clock;
    this.request = request;
    this.response = response;
  }

  @Override
  public ResponseEntity<MeDto> getMe() {
    Authentication a = SecurityContextHolder.getContext().getAuthentication();
    MeDto me =
        new MeDto().authenticated(false).roles(new ArrayList<>()).loginProviders(providers());
    if (a instanceof OAuth2AuthenticationToken t && t.isAuthenticated()) {
      currentUser.id().flatMap(showPreferences::of).ifPresent(p -> me.preferences(dto(p)));
      me.authenticated(true).displayName(CurrentUser.displayName(t));
      t.getAuthorities()
          .forEach(g -> {
            if ("ROLE_ADMIN".equals(g.getAuthority())) {
              me.addRolesItem(MeDto.RolesEnum.ADMIN);
            } else if ("ROLE_USER".equals(g.getAuthority())) {
              me.addRolesItem(MeDto.RolesEnum.USER);
            }
          });
    }
    return ResponseEntity.ok(me.limits(limitsToday()).monetisation(monetisation(monetisation)));
  }

  /** What the site offers to fund itself (ADR 0030). The back office has its own /me, without. */
  static MonetisationDto monetisation(Monetisation shown) {
    return new MonetisationDto(
        shown.githubSponsors().orElse(null),
        shown.stripePayment().orElse(null),
        shown.ga4MeasurementId().orElse(null),
        shown.ethicalAdsPublisher().orElse(null),
        shown.adsenseOn() ? shown.adsenseClient().orElse(null) : null,
        shown.adsenseOn() ? shown.adsenseSlot().orElse(null) : null);
  }

  /** Counted per client address or user, so read after the user is known (CurrentUser.id). */
  private LimitsDto limitsToday() {
    VisitorLimits.Today today =
        limits.of(ClientKey.of(request.getRemoteAddr()), currentUser.id().orElse(null));
    Instant nextUtcMidnight = clock.instant().truncatedTo(ChronoUnit.DAYS).plus(1, ChronoUnit.DAYS);
    return new LimitsDto(
        allowance(today.guideQuestions()),
        new LimitsWebSearchesDto(
            webSearches(today.searchWebSearches()), webSearches(today.guideWebSearches())),
        nextUtcMidnight.atOffset(ZoneOffset.UTC));
  }

  private static @Nullable DailyAllowanceDto allowance(VisitorLimits.@Nullable Allowance a) {
    return a == null ? null : new DailyAllowanceDto(a.limit(), a.remaining());
  }

  private static @Nullable WebSearchesDto webSearches(VisitorLimits.@Nullable Allowance a) {
    return a == null ? null : new WebSearchesDto(a.limit(), a.remaining());
  }

  @Override
  public ResponseEntity<Void> deleteMyAccount() {
    UUID userId = user();
    // The session ends first: one that outlived the row would recreate the account on the next
    // request (CurrentUser.id). If ending it fails, nothing is deleted and the user retries.
    new SecurityContextLogoutHandler()
        .logout(request, response, SecurityContextHolder.getContext().getAuthentication());
    deleteMyAccount.delete(userId);
    return ResponseEntity.noContent().build();
  }

  @Override
  public ResponseEntity<PreferencesDto> changePreferences(PreferencesChangeDto body) {
    return ResponseEntity.ok(dto(changePreferences.change(user(), body.getRadarMotion())));
  }

  @Override
  public ResponseEntity<List<BookmarkDto>> listBookmarks() {
    return ResponseEntity.ok(listBookmarks.list(user()).stream().map(MeController::dto).toList());
  }

  @Override
  public ResponseEntity<BookmarkDto> addBookmark(UUID resourceId, BookmarkRequestDto body) {
    if (!resourceId.equals(body.getResourceId())) {
      throw new BadRequestException("resourceId in the path and the body differ");
    }
    return ResponseEntity.ok(dto(addBookmark.add(user(), bookmark(body))));
  }

  @Override
  public ResponseEntity<List<BookmarkDto>> mergeLocalBookmarks(List<BookmarkRequestDto> body) {
    return ResponseEntity.ok(
        mergeLocalBookmarks
            .merge(user(), body.stream().map(MeController::bookmark).toList())
            .stream()
            .map(MeController::dto)
            .toList());
  }

  @Override
  public ResponseEntity<Void> deleteBookmark(UUID resourceId) {
    removeBookmark.remove(user(), resourceId);
    return ResponseEntity.noContent().build();
  }

  private UUID user() {
    return currentUser.id().orElseThrow(() -> new ApiProblem(ProblemType.SIGN_IN_REQUIRED, null));
  }

  private List<MeLoginProvidersInnerDto> providers() {
    List<MeLoginProvidersInnerDto> out = new ArrayList<>();
    ClientRegistrationRepository repo = registrations.getIfAvailable();
    if (repo instanceof Iterable<?> it) {
      for (Object o : it) {
        ClientRegistration r = (ClientRegistration) o;
        out.add(new MeLoginProvidersInnerDto(r.getRegistrationId(), r.getClientName()));
      }
    }
    return out;
  }

  private static Bookmark bookmark(BookmarkRequestDto r) {
    return new Bookmark(
        r.getResourceId(),
        r.getTitle(),
        r.getUrl(),
        r.getType() == null ? null : r.getType().getValue(),
        null);
  }

  private static PreferencesDto dto(Preferences p) {
    return new PreferencesDto(p.radarMotion());
  }

  private static BookmarkDto dto(Bookmark b) {
    return new BookmarkDto(b.resourceId(), b.title(), b.url(), b.savedAt().atOffset(ZoneOffset.UTC))
        .type(b.type() == null ? null : ResourceTypeDto.fromValue(b.type()));
  }
}
