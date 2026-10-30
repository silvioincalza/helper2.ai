package ai.helper2.api;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oauth2Login;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
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
