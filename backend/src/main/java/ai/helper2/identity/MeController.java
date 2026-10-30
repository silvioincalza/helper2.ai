package ai.helper2.identity;

import ai.helper2.api.generated.MeApi;
import ai.helper2.api.generated.model.BookmarkDto;
import ai.helper2.api.generated.model.BookmarkRequestDto;
import ai.helper2.api.generated.model.MeDto;
import ai.helper2.api.generated.model.MeLoginProvidersInnerDto;
import ai.helper2.api.generated.model.ResourceTypeDto;
import ai.helper2.identity.internal.Bookmarks.Bookmark;
import ai.helper2.identity.usecase.AddBookmark;
import ai.helper2.identity.usecase.DeleteMyAccount;
import ai.helper2.identity.usecase.ListBookmarks;
import ai.helper2.identity.usecase.MergeLocalBookmarks;
import ai.helper2.identity.usecase.RemoveBookmark;
import ai.helper2.platform.BadRequestException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.web.authentication.logout.SecurityContextLogoutHandler;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

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
    private final ObjectProvider<ClientRegistrationRepository> registrations;
    private final HttpServletRequest request;
    private final HttpServletResponse response;

    MeController(CurrentUser currentUser, ListBookmarks listBookmarks, AddBookmark addBookmark, RemoveBookmark removeBookmark,
                 MergeLocalBookmarks mergeLocalBookmarks, DeleteMyAccount deleteMyAccount,
                 ObjectProvider<ClientRegistrationRepository> registrations, HttpServletRequest request,
                 HttpServletResponse response) {
        this.currentUser = currentUser;
        this.listBookmarks = listBookmarks;
        this.addBookmark = addBookmark;
        this.removeBookmark = removeBookmark;
        this.mergeLocalBookmarks = mergeLocalBookmarks;
        this.deleteMyAccount = deleteMyAccount;
        this.registrations = registrations;
        this.request = request;
        this.response = response;
    }

    @Override
    public ResponseEntity<MeDto> getMe() {
        Authentication a = SecurityContextHolder.getContext().getAuthentication();
        MeDto me = new MeDto().authenticated(false).roles(new ArrayList<>()).loginProviders(providers());
        if (a instanceof OAuth2AuthenticationToken t && t.isAuthenticated()) {
            currentUser.id();
            me.authenticated(true).displayName(CurrentUser.displayName(t));
            t.getAuthorities().forEach(g -> {
                if ("ROLE_ADMIN".equals(g.getAuthority())) {
                    me.addRolesItem(MeDto.RolesEnum.ADMIN);
                } else if ("ROLE_USER".equals(g.getAuthority())) {
                    me.addRolesItem(MeDto.RolesEnum.USER);
                }
            });
        }
        return ResponseEntity.ok(me);
    }

    @Override
    public ResponseEntity<Void> deleteMyAccount() {
        deleteMyAccount.delete(user());
        // The session would otherwise recreate the account on the next request (CurrentUser.id).
        new SecurityContextLogoutHandler().logout(request, response, SecurityContextHolder.getContext().getAuthentication());
        return ResponseEntity.noContent().build();
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
        return ResponseEntity.ok(mergeLocalBookmarks.merge(user(), body.stream().map(MeController::bookmark).toList())
            .stream().map(MeController::dto).toList());
    }

    @Override
    public ResponseEntity<Void> deleteBookmark(UUID resourceId) {
        removeBookmark.remove(user(), resourceId);
        return ResponseEntity.noContent().build();
    }

    private UUID user() {
        return currentUser.id().orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED));
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
        return new Bookmark(r.getResourceId(), r.getTitle(), r.getUrl(), r.getType() == null ? null : r.getType().getValue(), null);
    }

    private static BookmarkDto dto(Bookmark b) {
        return new BookmarkDto(b.resourceId(), b.title(), b.url(), b.savedAt().atOffset(ZoneOffset.UTC))
            .type(b.type() == null ? null : ResourceTypeDto.fromValue(b.type()));
    }
}
