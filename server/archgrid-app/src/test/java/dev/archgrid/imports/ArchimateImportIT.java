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
 * 18 diagrams, Russian names, junctions, duplicate names — everything ADR-0012, ADR-0015 and
 * docs/09 were written about, exercised in one request.
 *
 * <p>The numbers below come from the file itself: 850 elements of which 118 share a name and a
 * type with an earlier one and 21 share a name with something of another type; 1113 relationships
 * of which 24 restate another once their ends have been merged; 676 element nodes on 18 views.
 */
@SpringBootTest(
        properties = "spring.security.oauth2.resourceserver.jwt.jwk-set-uri=http://localhost/jwks-not-used")
@AutoConfigureMockMvc
@Import(PostgresContainerConfig.class)
@EnabledIf(value = "dev.archgrid.support.Docker#isAvailable", disabledReason = "Docker is not available")
class ArchimateImportIT {

    private static final String WS = "bank";

    /** The file's first element, as Archi wrote its identifier and as we hold it. */
    private static final String FIRST_ELEMENT_IN_FILE = "cfeed7b0-4817-4c23-b2c6-d207fda5f130";

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

    private JsonNode importFile(byte[] file) throws Exception {
        return json.readTree(mockMvc.perform(post("/api/workspaces/{ws}/import/archimate", WS)
                        .contentType(MediaType.APPLICATION_XML)
                        .content(file)
                        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_CONTRIBUTOR"))))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString());
    }

    private JsonNode getJson(String path, Object... variables) throws Exception {
        return json.readTree(mockMvc.perform(get(path, variables).with(jwt()))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString());
    }

    @Test
    @DisplayName("the sample bank repository imports whole: model, diagrams, folders, and an exact report")
    void imports_the_sample_file() throws Exception {
        byte[] file = getClass().getResourceAsStream("/imports/bbank_schemas.xml").readAllBytes();

        JsonNode report = importFile(file);

        // 850 elements in the file, 118 of them a second "QazID" or "B-Connect" of the same type —
        // merged into the first (docs/09), so 732 arrive. The tolerant pipeline may still discard
        // what the matrix forbids, by name, in the report.
        assertThat(report.get("commitId").isNull()).isFalse();
        assertThat(report.get("elements").asInt()).isBetween(720, 740);
        assertThat(report.get("relationships").asInt()).isBetween(1000, 1095);
        assertThat(report.get("views").asInt()).isEqualTo(18);
        assertThat(report.get("folders").asInt()).isGreaterThanOrEqualTo(8);
        for (JsonNode discarded : report.get("discarded")) {
            assertThat(discarded.get("detail").asString()).isNotBlank();
        }

        // The merge is in the report, by name: what was folded and into how many, what was renamed
        // because something of another type held the name, and nothing skipped — the repository
        // was empty.
        int folded = 0;
        for (JsonNode count : report.get("merged")) {
            folded += count.asInt();
        }
        assertThat(folded + report.get("elements").asInt() + report.get("renamed").size())
                .as("merged + created + renamed accounts for the 850 elements in the file, minus discards")
                .isBetween(840, 850 + report.get("renamed").size());
        assertThat(folded).isBetween(100, 130);
        assertThat(report.get("merged").has("QazID (ApplicationComponent)")).isTrue();
        assertThat(report.get("renamed").size()).isBetween(10, 30);
        assertThat(report.get("renamed").get(0).asString()).contains(" → ");
        assertThat(report.get("skipped")).isEmpty();

        // The graph the import left behind is the graph the API serves.
        JsonNode graph = getJson("/api/workspaces/{ws}", WS);
        assertThat(graph.get("elements").asInt()).isEqualTo(report.get("elements").asInt());
        assertThat(graph.get("relationships").asInt())
                .isEqualTo(report.get("relationships").asInt());

        // Identity came from the file: Archi's `id-<32 hex>` is the UUID we hold, so the first
        // element of the file is findable by the identifier Archi gave it.
        JsonNode elements = getJson("/api/workspaces/{ws}/elements", WS);
        assertThat(elements).isNotEmpty();
        for (JsonNode element : elements) {
            assertThat(element.get("key").asString())
                    .matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
        }
        JsonNode usage = getJson("/api/workspaces/{ws}/elements/{id}/usage", WS, FIRST_ELEMENT_IN_FILE);
        assertThat(usage.get("relationships")).isNotNull();
        // Names are unique now, and the file's Cyrillic names arrived as they were, not
        // transliterated into anything.
        boolean firstByName = false;
        for (JsonNode element : elements) {
            if (element.get("key").asString().equals(FIRST_ELEMENT_IN_FILE)) {
                assertThat(element.get("name").asString()).isEqualTo("Клиент ФЛ");
                firstByName = true;
            }
        }
        assertThat(firstByName).isTrue();

        // Every view arrived with an arrangement, not just a membership: placements with real
        // geometry (ADR-0014 — a node is a placement) and, on the views that draw connections,
        // lines anchored to those placements. Merging elements loses no boxes: a second drawing of
        // "QazID" is a second placement of the one element.
        JsonNode views = getJson("/api/workspaces/{ws}/views", WS);
        assertThat(views).hasSize(18);

        int placements = 0;
        int lines = 0;
        for (JsonNode summary : views) {
            JsonNode view = getJson("/api/workspaces/{ws}/views/{key}", WS, summary.get("key").asString());
            assertThat(view.get("layout").size())
                    .as("view %s has placements", summary.get("name").asString())
                    .isGreaterThan(0);
            placements += view.get("layout").size();
            lines += view.get("layoutLinks").size();
        }
        // 676 element nodes in the file; a handful may vanish with a discarded element.
        assertThat(placements).isBetween(650, 676);
        assertThat(lines).isGreaterThan(400);

        // A second import of the same file finds everything it made the first time — the ids are
        // the file's — and adds nothing. The report says so by name: every object skipped, nothing
        // created, the repository exactly as it was.
        JsonNode second = importFile(file);
        assertThat(second.get("elements").asInt()).isZero();
        // Zero here also pins the cycle cut: the file's composition cycles are broken by cutting
        // what the file brought, so the second import cuts the same edges again rather than
        // trading one for a neighbour the repository already held.
        assertThat(second.get("relationships").asInt()).isZero();
        java.util.Set<String> firstKeys = new java.util.HashSet<>();
        elements.forEach(element -> firstKeys.add(element.get("key").asString()));
        java.util.Set<String> secondKeys = new java.util.HashSet<>();
        getJson("/api/workspaces/{ws}/elements", WS).forEach(element -> secondKeys.add(element.get("key").asString()));
        assertThat(secondKeys).isEqualTo(firstKeys);
        assertThat(second.get("views").asInt()).isZero();
        assertThat(second.get("skipped").size()).isGreaterThanOrEqualTo(report.get("elements").asInt() + 18);

        JsonNode unchanged = getJson("/api/workspaces/{ws}", WS);
        assertThat(unchanged.get("elements").asInt()).isEqualTo(report.get("elements").asInt());
        assertThat(unchanged.get("relationships").asInt()).isEqualTo(report.get("relationships").asInt());
        assertThat(getJson("/api/workspaces/{ws}/views", WS)).hasSize(18);
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
