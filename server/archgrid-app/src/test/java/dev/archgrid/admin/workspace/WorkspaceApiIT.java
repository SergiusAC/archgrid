package dev.archgrid.admin.workspace;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.archgrid.support.PostgresContainerConfig;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;

/**
 * End-to-end check of the phase 0 acceptance criterion: the application starts against a
 * real PostgreSQL, Flyway applies the schema, and an authenticated call reads it back.
 */
@SpringBootTest(
        properties = {
            // A JWKS URI rather than an issuer URI: the issuer form performs OIDC discovery
            // while the decoder bean is being created, which would make every test depend on
            // a running identity provider. Tokens here are injected directly, so the decoder
            // is never actually invoked.
            "spring.security.oauth2.resourceserver.jwt.jwk-set-uri=http://localhost/jwks-not-used"
        })
@AutoConfigureMockMvc
@Import(PostgresContainerConfig.class)
@EnabledIf(value = "dev.archgrid.support.Docker#isAvailable", disabledReason = "Docker is not available")
class WorkspaceApiIT {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcClient jdbc;

    @BeforeEach
    void seed() {
        jdbc.sql("delete from workspace").update();
        jdbc.sql("insert into workspace (id, \"key\", name) values (:id, :key, :name)")
                .param("id", UUID.randomUUID())
                .param("key", "default")
                .param("name", "Default workspace")
                .update();
    }

    @Test
    @DisplayName("Flyway created the schema the domain expects")
    void schema_is_migrated() {
        Integer tables = jdbc.sql(
                        "select count(*) from information_schema.tables "
                                + "where table_schema = 'public' and table_name in ('workspace', 'model')")
                .query(Integer.class)
                .single();
        assertThat(tables).isEqualTo(2);
    }

    @Test
    @DisplayName("an authenticated caller reads workspaces from the database")
    void lists_workspaces_for_an_authenticated_caller() throws Exception {
        mockMvc.perform(get("/api/workspaces").with(jwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].key").value("default"))
                .andExpect(jsonPath("$[0].name").value("Default workspace"));
    }

    @Test
    @DisplayName("an unauthenticated caller gets 401, not a redirect")
    void rejects_anonymous_callers() throws Exception {
        mockMvc.perform(get("/api/workspaces")).andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("a malformed slug is a 400 with a problem document, not a 500")
    void rejects_a_malformed_workspace_key() throws Exception {
        mockMvc.perform(get("/api/workspaces/Not_A_Slug").with(jwt()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Invalid request"));
    }

    @Test
    @DisplayName("liveness and readiness stay reachable without a token")
    void health_probes_are_public() throws Exception {
        mockMvc.perform(get("/actuator/health/liveness")).andExpect(status().isOk());
        mockMvc.perform(get("/actuator/health/readiness")).andExpect(status().isOk());
    }
}
