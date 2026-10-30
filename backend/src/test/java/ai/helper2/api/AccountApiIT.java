package ai.helper2.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oauth2Login;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ai.helper2.shared.CanonicalUrl;
import ai.helper2.shared.ResourceId;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.OAuth2LoginRequestPostProcessor;

/** A signed-in user's bookmarks (one use case per action) and deleting the account. */
class AccountApiIT extends ApiTestBase {

    @Autowired
    JdbcClient jdbc;

    final String subject = "user-" + UUID.randomUUID();
    final OAuth2LoginRequestPostProcessor user = oauth2Login().attributes(a -> {
        a.put("sub", subject);
        a.put("name", "Tester");
    });

    static String bookmark(UUID id, String url) {
        return "{\"resourceId\":\"" + id + "\",\"title\":\"T\",\"url\":\"" + url + "\"}";
    }

    long rows(String sql) {
        return jdbc.sql(sql).param("s", subject).query(Long.class).single();
    }

    @Test
    void bookmarksNeedSignInAndCsrf() throws Exception {
        UUID id = UUID.randomUUID();
        mvc.perform(get("/api/v1/me/bookmarks")).andExpect(status().isUnauthorized());
        mvc.perform(put("/api/v1/me/bookmarks/" + id).with(user).contentType(MediaType.APPLICATION_JSON)
            .content(bookmark(id, "https://example.com"))).andExpect(status().isForbidden());
        mvc.perform(delete("/api/v1/me").with(user)).andExpect(status().isForbidden());
        mvc.perform(delete("/api/v1/me").with(csrf())).andExpect(status().isUnauthorized());
    }

    @Test
    void addingTheSameResourceTwiceKeepsOneBookmarkAndRemovingIsIdempotent() throws Exception {
        UUID id = UUID.randomUUID();
        String first = mvc.perform(put("/api/v1/me/bookmarks/" + id).with(user).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content(bookmark(id, "https://example.com"))).andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();
        ContractValidator.assertMatches("Bookmark", first);
        mvc.perform(put("/api/v1/me/bookmarks/" + id).with(user).with(csrf()).contentType(MediaType.APPLICATION_JSON)
            .content(bookmark(id, "https://example.com"))).andExpect(status().isOk());
        mvc.perform(get("/api/v1/me/bookmarks").with(user)).andExpect(jsonPath("$.length()").value(1));

        mvc.perform(delete("/api/v1/me/bookmarks/" + id).with(user).with(csrf())).andExpect(status().isNoContent());
        mvc.perform(delete("/api/v1/me/bookmarks/" + id).with(user).with(csrf())).andExpect(status().isNoContent());
        mvc.perform(get("/api/v1/me/bookmarks").with(user)).andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void aBookmarkMustLinkToAWebPageAndMatchItsPath() throws Exception {
        UUID id = UUID.randomUUID();
        mvc.perform(put("/api/v1/me/bookmarks/" + id).with(user).with(csrf()).contentType(MediaType.APPLICATION_JSON)
            .content(bookmark(id, "javascript:alert(1)"))).andExpect(status().isBadRequest());
        mvc.perform(put("/api/v1/me/bookmarks/" + UUID.randomUUID()).with(user).with(csrf()).contentType(MediaType.APPLICATION_JSON)
            .content(bookmark(id, "https://example.com"))).andExpect(status().isBadRequest());
    }

    @Test
    void mergingLocalBookmarksIsIdempotentAndAllOrNothing() throws Exception {
        String one = bookmark(UUID.randomUUID(), "https://example.com/one");
        mvc.perform(post("/api/v1/me/bookmarks").with(user).with(csrf()).contentType(MediaType.APPLICATION_JSON).content("[" + one + "]"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(1));
        mvc.perform(post("/api/v1/me/bookmarks").with(user).with(csrf()).contentType(MediaType.APPLICATION_JSON).content("[" + one + "]"))
            .andExpect(jsonPath("$.length()").value(1));
        String invalid = bookmark(UUID.randomUUID(), "ftp://example.com");
        mvc.perform(post("/api/v1/me/bookmarks").with(user).with(csrf()).contentType(MediaType.APPLICATION_JSON)
            .content("[" + bookmark(UUID.randomUUID(), "https://example.com/two") + "," + invalid + "]"))
            .andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/me/bookmarks").with(user)).andExpect(jsonPath("$.length()").value(1));
    }

    @Test
    void anAccountKeepsAtMostFiveHundredBookmarks() throws Exception {
        for (int batch = 0; batch < 3; batch++) {
            StringBuilder body = new StringBuilder("[");
            for (int i = 0; i < 200; i++) {
                body.append(i == 0 ? "" : ",").append(bookmark(UUID.randomUUID(), "https://example.com"));
            }
            body.append(']');
            var result = mvc.perform(post("/api/v1/me/bookmarks").with(user).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(body.toString()));
            result.andExpect(batch < 2 ? status().isOk() : status().isBadRequest());
        }
        mvc.perform(get("/api/v1/me/bookmarks").with(user)).andExpect(jsonPath("$.length()").value(400));
        UUID one = UUID.randomUUID();
        mvc.perform(put("/api/v1/me/bookmarks/" + one).with(user).with(csrf()).contentType(MediaType.APPLICATION_JSON)
            .content(bookmark(one, "https://example.com"))).andExpect(status().isOk());
    }

    @Test
    void deletingTheAccountRemovesItsBookmarksAndKeepsItsReportsWithoutTheReporter() throws Exception {
        String deepl = ResourceId.of(CanonicalUrl.of("https://deepl.com/translator")).toString();
        UUID saved = UUID.randomUUID();
        mvc.perform(put("/api/v1/me/bookmarks/" + saved).with(user).with(csrf()).contentType(MediaType.APPLICATION_JSON)
            .content(bookmark(saved, "https://example.com"))).andExpect(status().isOk());
        String report = mvc.perform(post("/api/v1/resources/" + deepl + "/reports").with(user).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content("{\"kind\":\"WRONG\",\"message\":\"Wrong licence here\"}"))
            .andExpect(status().isAccepted()).andReturn().getResponse().getContentAsString();
        UUID reportId = UUID.fromString(com.jayway.jsonpath.JsonPath.read(report, "$.reportId"));
        assertThat(rows("SELECT count(*) FROM app_user WHERE subject = :s")).isOne();

        mvc.perform(delete("/api/v1/me").with(user).with(csrf())).andExpect(status().isNoContent());

        assertThat(rows("SELECT count(*) FROM app_user WHERE subject = :s")).isZero();
        assertThat(jdbc.sql("SELECT count(*) FROM bookmark WHERE resource_id = :r").param("r", saved).query(Long.class).single()).isZero();
        assertThat(jdbc.sql("SELECT reporter_user_id IS NULL FROM issue_report WHERE id = :id").param("id", reportId)
            .query(Boolean.class).single()).isTrue();
    }
}
