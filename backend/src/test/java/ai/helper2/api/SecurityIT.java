package ai.helper2.api;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oauth2Login;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

class SecurityIT extends ApiTestBase {

    @Test
    void anonymousSessionInfo() throws Exception {
        mvc.perform(get("/api/v1/me")).andExpect(status().isOk()).andExpect(jsonPath("$.authenticated").value(false));
    }

    @Test
    void adminEndpointsRequireAdmin() throws Exception {
        mvc.perform(get("/api/v1/admin/review-items")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/admin/review-items").with(oauth2Login())).andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/admin/review-items").with(oauth2Login().authorities(new SimpleGrantedAuthority("ROLE_ADMIN"))))
            .andExpect(status().isOk());
    }

    @Test
    void bookmarksNeedSignInAndCsrfAndMergeIdempotently() throws Exception {
        String body = "[{\"resourceId\":\"" + UUID.randomUUID() + "\",\"title\":\"T\",\"url\":\"https://example.com\"}]";
        mvc.perform(get("/api/v1/me/bookmarks")).andExpect(status().isUnauthorized());
        var login = oauth2Login().attributes(a -> {
            a.put("sub", "user-" + UUID.randomUUID());
            a.put("name", "Tester");
        });
        mvc.perform(post("/api/v1/me/bookmarks").with(login).contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/me/bookmarks").with(login).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(1));
        mvc.perform(post("/api/v1/me/bookmarks").with(login).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(jsonPath("$.length()").value(1));
        mvc.perform(delete("/api/v1/me/bookmarks/" + UUID.randomUUID()).with(login).with(csrf())).andExpect(status().isNoContent());
    }

    @Test
    void anAccountKeepsAtMostFiveHundredBookmarks() throws Exception {
        String subject = "user-" + UUID.randomUUID();
        var login = oauth2Login().attributes(a -> a.put("sub", subject));
        for (int batch = 0; batch < 3; batch++) {
            StringBuilder body = new StringBuilder("[");
            for (int i = 0; i < 200; i++) {
                body.append(i == 0 ? "" : ",").append("{\"resourceId\":\"").append(UUID.randomUUID())
                    .append("\",\"title\":\"T\",\"url\":\"https://example.com\"}");
            }
            body.append(']');
            var result = mvc.perform(post("/api/v1/me/bookmarks").with(login).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(body.toString()));
            if (batch < 2) {
                result.andExpect(status().isOk());
            } else {
                result.andExpect(status().isBadRequest());
            }
        }
        mvc.perform(get("/api/v1/me/bookmarks").with(login)).andExpect(jsonPath("$.length()").value(400));
    }

    @Test
    void unknownApiPathsAreDenied() throws Exception {
        mvc.perform(get("/api/v1/does-not-exist")).andExpect(status().isUnauthorized());
        mvc.perform(put("/api/v1/sources").with(csrf())).andExpect(status().isUnauthorized());
    }

    @Test
    void metricsAreNotPublicByDefault() throws Exception {
        mvc.perform(get("/actuator/prometheus")).andExpect(status().isUnauthorized());
        mvc.perform(get("/actuator/health")).andExpect(status().isOk());
    }
}
