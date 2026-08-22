package dev.archgrid.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.archgrid.domain.shared.Id;
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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Views and folders (ADR-0009).
 *
 * <p>The point being pinned throughout: an element belongs to the repository, a view merely
 * shows it, and a folder merely files it. Neither a view nor a folder owns anything.
 */
@SpringBootTest(
        properties = "spring.security.oauth2.resourceserver.jwt.jwk-set-uri=http://localhost/jwks-not-used")
@AutoConfigureMockMvc
@Import(PostgresContainerConfig.class)
@EnabledIf(value = "dev.archgrid.support.Docker#isAvailable", disabledReason = "Docker is not available")
class ViewsAndFoldersApiIT {

    private static final String WS = "acme";

    private static String named(String name) {
        return Id.named(name).toString();
    }

    private static final String PAYMENT_SERVICE = named("payment-service");
    private static final String KAFKA = named("kafka");
    private static final String NOTIFICATION_SERVICE = named("notification-service");
    private static final String PAYS_TO_KAFKA = named("pays-to-kafka");
    private static final String KAFKA_SERVES = named("kafka-serves");
    private static final String PAYMENTS_CONTEXT = named("payments-context");
    private static final String MESSAGING = named("messaging");
    private static final String CONTEXT = named("context");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private ObjectMapper json;

    private String head;

    @BeforeEach
    void seed() throws Exception {
        for (String table : new String[] {
            "view_layout_node", "view_element", "view_version", "view",
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
        jdbc.sql("insert into workspace (id, \"key\", name) values (:id, 'acme', 'Acme')")
                .param("id", UUID.randomUUID())
                .update();

        head = apply(
                        null,
                        """
                        [{"op":"create-element","key":"%1$s","type":"ApplicationComponent","name":"Payment Service"},
                         {"op":"create-element","key":"%2$s","type":"SystemSoftware","name":"Kafka"},
                         {"op":"create-element","key":"%3$s","type":"ApplicationComponent","name":"Notification Service"},
                         {"op":"add-relationship","key":"%4$s","type":"Flow","source":"%1$s","target":"%2$s"},
                         {"op":"add-relationship","key":"%5$s","type":"Serving","source":"%2$s","target":"%3$s"}]
                        """
                                .formatted(PAYMENT_SERVICE, KAFKA, NOTIFICATION_SERVICE, PAYS_TO_KAFKA, KAFKA_SERVES))
                .get("commitId")
                .asString();
    }

    private static MockHttpServletRequestBuilder asContributor(MockHttpServletRequestBuilder request) {
        return request.with(jwt().authorities(new SimpleGrantedAuthority("ROLE_CONTRIBUTOR")));
    }

    private JsonNode apply(String base, String commands) throws Exception {
        String body = """
                {"baseCommitId": %s, "message": "test", "commands": %s}
                """
                .formatted(base == null ? "null" : "\"" + base + "\"", commands);
        String response = mockMvc.perform(asContributor(post("/api/workspaces/{ws}/commands", WS)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(body)))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        return json.readTree(response);
    }

    @Test
    @DisplayName("the same element appears on two views and stays one object")
    void elements_are_reused_across_views() throws Exception {
        String next = apply(
                        head,
                        """
                        [{"op":"create-view","key":"%1$s","name":"Payments context","viewpoint":"layered"},
                         {"op":"create-view","key":"%2$s","name":"Messaging","viewpoint":"technology"},
                         {"op":"add-to-view","key":"%1$s","element":"%3$s"},
                         {"op":"add-to-view","key":"%1$s","element":"%4$s"},
                         {"op":"add-to-view","key":"%2$s","element":"%4$s"},
                         {"op":"add-to-view","key":"%2$s","element":"%5$s"}]
                        """
                                .formatted(PAYMENTS_CONTEXT, MESSAGING, PAYMENT_SERVICE, KAFKA, NOTIFICATION_SERVICE))
                .get("commitId")
                .asString();
        assertThat(next).isNotBlank();

        mockMvc.perform(get("/api/workspaces/{ws}/views", WS).with(jwt()))
                .andExpect(jsonPath("$.length()").value(2));

        mockMvc.perform(get("/api/workspaces/{ws}/views/{v}", WS, PAYMENTS_CONTEXT).with(jwt()))
                .andExpect(jsonPath("$.elements.length()").value(2))
                // Both endpoints are present, so the relationship between them shows without
                // anyone adding it.
                .andExpect(jsonPath("$.relationships.length()").value(1))
                .andExpect(jsonPath("$.relationships[0].key").value(PAYS_TO_KAFKA));

        mockMvc.perform(get("/api/workspaces/{ws}/views/{v}", WS, MESSAGING).with(jwt()))
                .andExpect(jsonPath("$.elements[?(@.key=='%s')]".formatted(KAFKA)).exists())
                .andExpect(jsonPath("$.relationships[0].key").value(KAFKA_SERVES));

        // One Kafka in the repository, shown twice.
        Integer kafkaRows = jdbc.sql("select count(*) from element where id = :id")
                .param("id", UUID.fromString(KAFKA))
                .query(Integer.class)
                .single();
        assertThat(kafkaRows).isEqualTo(1);
    }

    @Test
    @DisplayName("a relationship appears only when both of its endpoints are on the view")
    void relationships_follow_membership() throws Exception {
        apply(
                head,
                """
                [{"op":"create-view","key":"%1$s","name":"Partial","viewpoint":"layered"},
                 {"op":"add-to-view","key":"%1$s","element":"%2$s"}]
                """
                        .formatted(named("partial"), PAYMENT_SERVICE));

        mockMvc.perform(get("/api/workspaces/{ws}/views/{v}", WS, named("partial")).with(jwt()))
                .andExpect(jsonPath("$.elements.length()").value(1))
                .andExpect(jsonPath("$.relationships.length()").value(0));
    }

    @Test
    @DisplayName("deleting a view leaves the repository untouched")
    void deleting_a_view_keeps_the_elements() throws Exception {
        String afterCreate = apply(
                        head,
                        """
                        [{"op":"create-view","key":"%1$s","name":"Doomed","viewpoint":"layered"},
                         {"op":"add-to-view","key":"%1$s","element":"%2$s"}]
                        """
                                .formatted(named("doomed"), KAFKA))
                .get("commitId")
                .asString();

        apply(afterCreate, """
                [{"op":"delete-view","key":"%s"}]
                """.formatted(named("doomed")));

        mockMvc.perform(get("/api/workspaces/{ws}/views", WS).with(jwt()))
                .andExpect(jsonPath("$.length()").value(0));
        mockMvc.perform(get("/api/workspaces/{ws}/elements", WS).with(jwt()))
                .andExpect(jsonPath("$.length()").value(3));
    }

    @Test
    @DisplayName("deleting an element takes it off every view that showed it")
    void deleting_an_element_clears_it_from_views() throws Exception {
        String afterCreate = apply(
                        head,
                        """
                        [{"op":"create-view","key":"%1$s","name":"Context","viewpoint":"layered"},
                         {"op":"add-to-view","key":"%1$s","element":"%2$s"}]
                        """
                                .formatted(CONTEXT, NOTIFICATION_SERVICE))
                .get("commitId")
                .asString();

        // Its relationship goes first; the element still contains nothing, so it can go.
        apply(
                afterCreate,
                """
                [{"op":"delete-relationship","key":"%s"},
                 {"op":"delete-element","key":"%s"}]
                """
                        .formatted(KAFKA_SERVES, NOTIFICATION_SERVICE));

        mockMvc.perform(get("/api/workspaces/{ws}/views/{v}", WS, CONTEXT).with(jwt()))
                .andExpect(jsonPath("$.elements.length()").value(0));
    }

    @Test
    @DisplayName("an unknown viewpoint is refused with an explanation")
    void rejects_an_unknown_viewpoint() throws Exception {
        mockMvc.perform(asContributor(post("/api/workspaces/{ws}/commands", WS)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("""
                                        {"baseCommitId":"%s","commands":[
                                          {"op":"create-view","key":"%s","name":"Bad","viewpoint":"nonsense"}]}
                                        """
                                        .formatted(head, named("bad")))))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.violations[0].code").value("unknown-type"))
                .andExpect(jsonPath("$.violations[0].detail")
                        .value(org.hamcrest.Matchers.containsString("not a viewpoint")));
    }

    @Test
    @DisplayName("two views cannot share a name, like two elements")
    void rejects_a_duplicate_view_name() throws Exception {
        String next = apply(head, """
                [{"op":"create-view","key":"%s","name":"Context","viewpoint":"layered"}]
                """.formatted(CONTEXT))
                .get("commitId")
                .asString();

        mockMvc.perform(asContributor(post("/api/workspaces/{ws}/commands", WS)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("""
                                        {"baseCommitId":"%s","commands":[
                                          {"op":"create-view","key":"%s","name":"context","viewpoint":"layered"}]}
                                        """
                                        .formatted(next, named("context-2")))))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.violations[0].code").value("duplicate-name"))
                .andExpect(jsonPath("$.violations[0].subject").value(named("context-2")));
    }

    @Test
    @DisplayName("layout is saved outside the commit log")
    void layout_persists_without_a_commit() throws Exception {
        apply(
                head,
                """
                [{"op":"create-view","key":"%1$s","name":"Context","viewpoint":"layered"},
                 {"op":"add-to-view","key":"%1$s","element":"%2$s"}]
                """
                        .formatted(CONTEXT, KAFKA));
        Integer commitsBefore =
                jdbc.sql("select count(*) from model_commit").query(Integer.class).single();

        mockMvc.perform(asContributor(put("/api/workspaces/{ws}/views/{v}/layout", WS, CONTEXT)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("""
                                        {"nodes":[{"element":"%s","x":120.5,"y":240.0,"width":180.0,"height":80.0}]}
                                        """
                                        .formatted(KAFKA))))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/workspaces/{ws}/views/{v}", WS, CONTEXT).with(jwt()))
                .andExpect(jsonPath("$.layout[0].element").value(KAFKA))
                .andExpect(jsonPath("$.layout[0].x").value(120.5));

        // The whole point of ADR-0003: moving a box produced no commit.
        Integer commitsAfter =
                jdbc.sql("select count(*) from model_commit").query(Integer.class).single();
        assertThat(commitsAfter).isEqualTo(commitsBefore);
    }

    @Test
    @DisplayName("folders nest, and deleting one keeps its contents by moving them up")
    void folders_nest_and_never_own_their_contents() throws Exception {
        String outer = folderId(createFolder("elements", "Platform", null));
        String inner = folderId(createFolder("elements", "Messaging", outer));

        mockMvc.perform(asContributor(put("/api/workspaces/{ws}/folders/assign", WS)
                                .param("kind", "elements")
                                .param("key", KAFKA)
                                .param("folder", inner)))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/workspaces/{ws}/folders", WS).param("kind", "elements").with(jwt()))
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[?(@.name=='Messaging')].parent").value(outer));
        // Filing is read alongside the graph: the listing says where Kafka is.
        mockMvc.perform(get("/api/workspaces/{ws}/elements", WS).with(jwt()))
                .andExpect(jsonPath("$[?(@.key=='%s')].folder".formatted(KAFKA)).value(inner));

        mockMvc.perform(asContributor(delete("/api/workspaces/{ws}/folders/{id}", WS, inner)))
                .andExpect(status().isNoContent());

        // The folder is gone; Kafka is not, and it has moved up rather than been orphaned.
        mockMvc.perform(get("/api/workspaces/{ws}/elements", WS).with(jwt()))
                .andExpect(jsonPath("$.length()").value(3));
        String folderOfKafka = jdbc.sql("select folder_id::text from element where id = :id")
                .param("id", UUID.fromString(KAFKA))
                .query(String.class)
                .single();
        assertThat(folderOfKafka).isEqualTo(outer);
    }

    @Test
    @DisplayName("a folder cannot be put inside itself")
    void folder_cycles_are_refused() throws Exception {
        String outer = folderId(createFolder("elements", "Outer", null));
        String inner = folderId(createFolder("elements", "Inner", outer));

        mockMvc.perform(asContributor(put("/api/workspaces/{ws}/folders/{id}", WS, outer)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"name\":\"Outer\",\"parent\":\"%s\"}".formatted(inner))))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.detail")
                        .value(org.hamcrest.Matchers.containsString("inside itself")));
    }

    @Test
    @DisplayName("element folders and view folders are separate trees")
    void the_two_trees_are_separate() throws Exception {
        createFolder("elements", "Systems", null);
        createFolder("views", "Context diagrams", null);

        mockMvc.perform(get("/api/workspaces/{ws}/folders", WS).param("kind", "elements").with(jwt()))
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].name").value("Systems"));
        mockMvc.perform(get("/api/workspaces/{ws}/folders", WS).param("kind", "views").with(jwt()))
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].name").value("Context diagrams"));
    }

    private String createFolder(String kind, String name, String parent) throws Exception {
        String body = parent == null
                ? "{\"name\":\"%s\"}".formatted(name)
                : "{\"name\":\"%s\",\"parent\":\"%s\"}".formatted(name, parent);
        return mockMvc.perform(asContributor(post("/api/workspaces/{ws}/folders", WS)
                                .param("kind", kind)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(body)))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
    }

    private String folderId(String response) {
        return json.readTree(response).get("id").asString();
    }
}
