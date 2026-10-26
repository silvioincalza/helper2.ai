package ai.helper2.identity;

import ai.helper2.api.generated.MeApi;
import ai.helper2.api.generated.model.BookmarkDto;
import ai.helper2.api.generated.model.BookmarkRequestDto;
import ai.helper2.api.generated.model.MeDto;
import ai.helper2.api.generated.model.MeLoginProvidersInnerDto;
import ai.helper2.api.generated.model.ResourceTypeDto;
import ai.helper2.identity.internal.Bookmarks;
import ai.helper2.platform.BadRequestException;
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
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/v1")
class MeController implements MeApi {

    private final CurrentUser currentUser;
    private final Bookmarks bookmarks;
    private final ObjectProvider<ClientRegistrationRepository> registrations;

    MeController(CurrentUser currentUser, Bookmarks bookmarks, ObjectProvider<ClientRegistrationRepository> registrations) {
        this.currentUser = currentUser;
        this.bookmarks = bookmarks;
        this.registrations = registrations;
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
    public ResponseEntity<List<BookmarkDto>> listBookmarks() {
        return ResponseEntity.ok(bookmarks.list(user()).stream().map(MeController::dto).toList());
    }

    @Override
    public ResponseEntity<List<BookmarkDto>> addBookmarks(List<BookmarkRequestDto> request) {
        for (BookmarkRequestDto r : request) {
            String scheme = r.getUrl().getScheme();
            if (r.getUrl().toString().length() > 2000 || scheme == null || !(scheme.equals("https") || scheme.equals("http"))) {
                throw new BadRequestException("invalid bookmark url");
            }
        }
        UUID user = user();
        bookmarks.add(user, request.stream().map(r -> new Bookmarks.Bookmark(r.getResourceId(), r.getTitle(), r.getUrl(),
            r.getType() == null ? null : r.getType().getValue(), null)).toList());
        return ResponseEntity.ok(bookmarks.list(user).stream().map(MeController::dto).toList());
    }

    @Override
    public ResponseEntity<Void> deleteBookmark(UUID resourceId) {
        bookmarks.remove(user(), resourceId);
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

    private static BookmarkDto dto(Bookmarks.Bookmark b) {
        return new BookmarkDto(b.resourceId(), b.title(), b.url(), b.savedAt().atOffset(ZoneOffset.UTC))
            .type(b.type() == null ? null : ResourceTypeDto.fromValue(b.type()));
    }
}
