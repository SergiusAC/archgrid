package dev.archgrid.imports;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.archgrid.support.PostgresContainerConfig;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * The whole importer against the file it was built for: a real bank's repository, 850 elements,
 * 18 diagrams, Russian names, junctions, duplicate names — everything ADR-0012 and ADR-0015 were
 * written about, exercised in one request.
 */
@SpringBootTest(
        properties = "spring.security.oauth2.resourceserver.jwt.jwk-set-uri=http://localhost/jwks-not-used")
@AutoConfigureMockMvc
@Import(PostgresContainerConfig.class)
@EnabledIf(value = "dev.archgrid.support.Docker#isAvailable", disabledReason = "Docker is not available")
class ArchimateImportIT {

    private static final String WS = "bank";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private ObjectMapper json;

    @BeforeEach
    void seed() {
        for (String table : new String[] {
            "view_layout_link", "view_hidden_relationship", "view_layout_node", "view_element", "view_version", "view",
            "relationship_version", "element_version", "relationship", "element"
        }) {
            jdbc.sql("delete from " + table).update();
        }
        jdbc.sql("update model set head_commit_id = null").update();
        jdbc.sql("delete from model_commit").update();
        jdbc.sql("delete from folder").update();
        jdbc.sql("delete from profile").update();
        jdbc.sql("delete from model").update();
        jdbc.sql("delete from workspace").update();
        jdbc.sql("insert into workspace (id, \"key\", name) values (:id, 'bank', 'Bank')")
                .param("id", UUID.randomUUID())
                .update();
    }

    @Test
    @DisplayName("the sample bank repository imports whole: model, diagrams, folders, and an exact report")
    void imports_the_sample_file() throws Exception {
        byte[] file = getClass().getResourceAsStream("/imports/bbank_schemas.xml").readAllBytes();

        String response = mockMvc.perform(post("/api/workspaces/{ws}/import/archimate", WS)
                        .contentType(MediaType.APPLICATION_XML)
                        .content(file)
                        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_CONTRIBUTOR"))))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        JsonNode report = json.readTree(response);

        // The file defines 850 elements and 1113 relationships. The tolerant pipeline may discard
        // what the matrix forbids — by name, in the report — but the bulk must arrive.
        assertThat(report.get("commitId").isNull()).isFalse();
        assertThat(report.get("elements").asInt()).isBetween(840, 850);
        assertThat(report.get("relationships").asInt()).isBetween(1000, 1113);
        assertThat(report.get("views").asInt()).isEqualTo(18);
        assertThat(report.get("folders").asInt()).isGreaterThanOrEqualTo(8);
        for (JsonNode discarded : report.get("discarded")) {
            assertThat(discarded.get("detail").asString()).isNotBlank();
        }

        // The graph the import left behind is the graph the API serves.
        JsonNode graph = json.readTree(mockMvc.perform(get("/api/workspaces/{ws}", WS)
                        .with(jwt()))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString());
        assertThat(graph.get("elements").asInt()).isEqualTo(report.get("elements").asInt());
        assertThat(graph.get("relationships").asInt())
                .isEqualTo(report.get("relationships").asInt());

        // Every view arrived with an arrangement, not just a membership: placements with real
        // geometry (ADR-0014 — a node is a placement) and, on the views that draw connections,
        // lines anchored to those placements.
        JsonNode views = json.readTree(mockMvc.perform(get("/api/workspaces/{ws}/views", WS)
                        .with(jwt()))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString());
        assertThat(views).hasSize(18);

        int placements = 0;
        int lines = 0;
        for (JsonNode summary : views) {
            JsonNode view = json.readTree(mockMvc.perform(get(
                                    "/api/workspaces/{ws}/views/{key}", WS, summary.get("key").asString())
                            .with(jwt()))
                    .andExpect(status().isOk())
                    .andReturn()
                    .getResponse()
                    .getContentAsString());
            assertThat(view.get("layout").size())
                    .as("view %s has placements", summary.get("key").asString())
                    .isGreaterThan(0);
            placements += view.get("layout").size();
            lines += view.get("layoutLinks").size();
        }
        // 676 element nodes in the file; a handful may vanish with a discarded element.
        assertThat(placements).isBetween(650, 676);
        assertThat(lines).isGreaterThan(400);

        // Cyrillic names became readable slugs, not hyphen soup.
        JsonNode elements = json.readTree(mockMvc.perform(get("/api/workspaces/{ws}/elements", WS)
                        .with(jwt()))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString());
        assertThat(elements).isNotEmpty();
        for (JsonNode element : elements) {
            assertThat(element.get("key").asString()).matches("[a-z0-9]+(-[a-z0-9]+)*");
        }

        // A second import duplicates under fresh slugs by design — it is a new commit, not a
        // merge (matching sources is phase 6c). What this pins is the report's meaning: its
        // counts are what the file added, so they stay the same into a repository that now holds
        // twice as much. Reporting totals here would say "1700 elements" for the same file.
        JsonNode second = json.readTree(mockMvc.perform(post("/api/workspaces/{ws}/import/archimate", WS)
                        .contentType(MediaType.APPLICATION_XML)
                        .content(file)
                        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_CONTRIBUTOR"))))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString());
        assertThat(second.get("elements").asInt()).isEqualTo(report.get("elements").asInt());
        assertThat(second.get("relationships").asInt())
                .isEqualTo(report.get("relationships").asInt());

        JsonNode grown = json.readTree(mockMvc.perform(get("/api/workspaces/{ws}", WS)
                        .with(jwt()))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString());
        assertThat(grown.get("elements").asInt())
                .isEqualTo(report.get("elements").asInt() + second.get("elements").asInt());
    }

    @Test
    @DisplayName("a reader cannot import")
    void readers_cannot_import() throws Exception {
        mockMvc.perform(post("/api/workspaces/{ws}/import/archimate", WS)
                        .contentType(MediaType.APPLICATION_XML)
                        .content("<model/>".getBytes())
                        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_READER"))))
                .andExpect(status().isForbidden());
    }
}
