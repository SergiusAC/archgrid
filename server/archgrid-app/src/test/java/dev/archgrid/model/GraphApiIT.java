package dev.archgrid.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * The workspace's graph, end to end.
 *
 * <p>A workspace holds exactly one connected graph (ADR-0008), so these paths carry no model
 * segment and the graph is provisioned on first access rather than created by a user.
 */
@SpringBootTest(
        properties = "spring.security.oauth2.resourceserver.jwt.jwk-set-uri=http://localhost/jwks-not-used")
@AutoConfigureMockMvc
@Import(PostgresContainerConfig.class)
@EnabledIf(value = "dev.archgrid.support.Docker#isAvailable", disabledReason = "Docker is not available")
class GraphApiIT {

    private static final String WORKSPACE = "acme";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private ObjectMapper json;

    @BeforeEach
    void seedWorkspace() {
        jdbc.sql("delete from relationship_version").update();
        jdbc.sql("delete from element_version").update();
        jdbc.sql("delete from relationship").update();
        jdbc.sql("delete from element").update();
        jdbc.sql("update model set head_commit_id = null").update();
        jdbc.sql("delete from model_commit").update();
        jdbc.sql("delete from profile").update();
        jdbc.sql("delete from model").update();
        jdbc.sql("delete from workspace").update();

        jdbc.sql("insert into workspace (id, \"key\", name) values (:id, :key, :name)")
                .param("id", UUID.randomUUID())
                .param("key", WORKSPACE)
                .param("name", "Acme")
                .update();
    }

    private static MockHttpServletRequestBuilder asContributor(MockHttpServletRequestBuilder request) {
        return request.with(jwt().authorities(new SimpleGrantedAuthority("ROLE_CONTRIBUTOR")));
    }

    private JsonNode applyCommands(String baseCommitId, String message, String commandsJson) throws Exception {
        String base = baseCommitId == null ? "null" : "\"" + baseCommitId + "\"";
        String response = mockMvc.perform(asContributor(post("/api/workspaces/{ws}/commands", WORKSPACE)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("""
                                        {"baseCommitId": %s, "message": "%s", "commands": %s}
                                        """
                                        .formatted(base, message, commandsJson))))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        return json.readTree(response);
    }

    private static final String PAYMENT_FLOW =
            """
            [
              {"op":"create-element","key":"payment-service","type":"ApplicationComponent","name":"Payment Service"},
              {"op":"create-element","key":"kafka","type":"SystemSoftware","name":"Kafka"},
              {"op":"create-element","key":"notification-service","type":"ApplicationComponent","name":"Notification Service"},
              {"op":"add-relationship","key":"payment-publishes","type":"Flow","source":"payment-service","target":"kafka"},
              {"op":"add-relationship","key":"kafka-serves-notification","type":"Serving","source":"kafka","target":"notification-service"}
            ]
            """;

    @Test
    @DisplayName("the graph exists on first read, without anyone creating it")
    void provisions_the_graph_lazily() throws Exception {
        mockMvc.perform(get("/api/workspaces/{ws}", WORKSPACE).with(jwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.workspace").value(WORKSPACE))
                .andExpect(jsonPath("$.elements").value(0))
                .andExpect(jsonPath("$.relationships").value(0))
                .andExpect(jsonPath("$.headCommitId").doesNotExist());

        Integer graphs = jdbc.sql("select count(*) from model").query(Integer.class).single();
        assertThat(graphs).isEqualTo(1);
    }

    @Test
    @DisplayName("a workspace can never hold a second graph")
    void one_graph_per_workspace_is_enforced_by_the_schema() throws Exception {
        mockMvc.perform(get("/api/workspaces/{ws}", WORKSPACE).with(jwt()));
        UUID workspaceId = jdbc.sql("select id from workspace where \"key\" = :key")
                .param("key", WORKSPACE)
                .query(UUID.class)
                .single();

        // The constraint is what makes "where is this used" trustworthy: an answer computed
        // over one of several graphs would look complete and not be.
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> jdbc.sql(
                        "insert into model (id, workspace_id, \"key\", name) values (:id, :ws, 'second', 'Second')")
                .param("id", UUID.randomUUID())
                .param("ws", workspaceId)
                .update())
                .isInstanceOf(org.springframework.dao.DuplicateKeyException.class);
    }

    @Test
    @DisplayName("Payment Service -> Kafka -> Notification Service through the workspace-scoped API")
    void builds_the_canonical_model() throws Exception {
        JsonNode commit = applyCommands(null, "Add the payment notification flow", PAYMENT_FLOW);
        assertThat(commit.get("elements").asInt()).isEqualTo(3);

        mockMvc.perform(get("/api/workspaces/{ws}/elements", WORKSPACE).with(jwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(3))
                .andExpect(jsonPath("$[?(@.key=='kafka')].conceptType").value("SystemSoftware"));

        mockMvc.perform(get("/api/workspaces/{ws}/relationships", WORKSPACE).with(jwt()))
                .andExpect(jsonPath("$.length()").value(2));

        mockMvc.perform(get("/api/workspaces/{ws}", WORKSPACE).with(jwt()))
                .andExpect(jsonPath("$.elements").value(3))
                .andExpect(jsonPath("$.relationships").value(2));
    }

    @Test
    @DisplayName("every element is reachable from one place, which is what makes 'where used' honest")
    void the_whole_landscape_is_one_graph() throws Exception {
        JsonNode first = applyCommands(null, "Payments", PAYMENT_FLOW);
        // A second area of the landscape, added later, connects to the same Kafka rather than
        // to a second copy of it.
        applyCommands(
                first.get("commitId").asString(),
                "Billing",
                """
                [{"op":"create-element","key":"billing-service","type":"ApplicationComponent","name":"Billing Service"},
                 {"op":"add-relationship","key":"billing-publishes","type":"Flow","source":"billing-service","target":"kafka"}]
                """);

        mockMvc.perform(get("/api/workspaces/{ws}/relationships", WORKSPACE).with(jwt()))
                .andExpect(jsonPath("$.length()").value(3))
                .andExpect(jsonPath("$[?(@.target=='kafka')].source", org.hamcrest.Matchers.hasSize(2)));
    }

    @Test
    @DisplayName("an impermissible relationship is refused with 422 and an explanation")
    void refuses_an_impermissible_relationship() throws Exception {
        JsonNode first = applyCommands(null, "Base", PAYMENT_FLOW);

        mockMvc.perform(asContributor(post("/api/workspaces/{ws}/commands", WORKSPACE)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("""
                                        {"baseCommitId":"%s","message":"bad","commands":[
                                          {"op":"create-element","key":"payments-data","type":"DataObject","name":"Payments"},
                                          {"op":"add-relationship","key":"bad","type":"Serving","source":"payment-service","target":"payments-data"}
                                        ]}
                                        """
                                        .formatted(first.get("commitId").asString()))))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.violations[0].code").value("relationship-not-permitted"))
                .andExpect(jsonPath("$.violations[0].detail")
                        .value(org.hamcrest.Matchers.containsString("Permitted instead:")));

        mockMvc.perform(get("/api/workspaces/{ws}/elements", WORKSPACE).with(jwt()))
                .andExpect(jsonPath("$.length()").value(3));
    }

    @Test
    @DisplayName("history records one commit per accepted change")
    void records_history() throws Exception {
        JsonNode first = applyCommands(null, "Add the payment notification flow", PAYMENT_FLOW);
        applyCommands(
                first.get("commitId").asString(),
                "Document the payment service",
                """
                [{"op":"set-element-documentation","key":"payment-service","documentation":"Authorises card payments."}]
                """);

        mockMvc.perform(get("/api/workspaces/{ws}/commits", WORKSPACE).with(jwt()))
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].seq").value(2))
                .andExpect(jsonPath("$[0].message").value("Document the payment service"));
    }

    @Test
    @DisplayName("a stale baseCommitId gets 409 with both commit ids")
    void detects_a_stale_base_commit() throws Exception {
        JsonNode first = applyCommands(null, "Base", PAYMENT_FLOW);
        String head = first.get("commitId").asString();
        applyCommands(head, "Rename", """
                [{"op":"rename-element","key":"kafka","name":"Kafka cluster"}]
                """);

        mockMvc.perform(asContributor(post("/api/workspaces/{ws}/commands", WORKSPACE)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("""
                                        {"baseCommitId":"%s","message":"stale","commands":[
                                          {"op":"rename-element","key":"kafka","name":"Something else"}
                                        ]}
                                        """
                                        .formatted(head))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.expectedBaseCommitId").value(head))
                .andExpect(jsonPath("$.currentHeadCommitId").exists());
    }

    @Test
    @DisplayName("a profile defined in the repository is usable as an element type")
    void creates_an_element_from_a_profile() throws Exception {
        mockMvc.perform(get("/api/workspaces/{ws}", WORKSPACE).with(jwt()));
        UUID graphId = jdbc.sql("select id from model").query(UUID.class).single();
        jdbc.sql("insert into profile (id, model_id, name, concept_type, attributes) "
                        + "values (:id, :graph, 'Microservice', 'ApplicationComponent', "
                        + "'[{\"key\":\"owner-team\",\"name\":\"Owning team\",\"type\":\"string\"}]'::jsonb)")
                .param("id", UUID.randomUUID())
                .param("graph", graphId)
                .update();

        applyCommands(
                null,
                "Add a microservice",
                """
                [{"op":"create-element","key":"payment-service","type":"Microservice","name":"Payment Service"}]
                """);

        mockMvc.perform(get("/api/workspaces/{ws}/profiles", WORKSPACE).with(jwt()))
                .andExpect(jsonPath("$[0].name").value("Microservice"))
                .andExpect(jsonPath("$[0].attributes[0].key").value("owner-team"));

        mockMvc.perform(get("/api/workspaces/{ws}/elements", WORKSPACE).with(jwt()))
                .andExpect(jsonPath("$[0].profile").value("Microservice"))
                .andExpect(jsonPath("$[0].conceptType").value("ApplicationComponent"));
    }

    @Test
    @DisplayName("a profile can be defined over the API, and refuses what the language forbids")
    void defines_and_refuses_profiles() throws Exception {
        mockMvc.perform(get("/api/workspaces/{ws}", WORKSPACE).with(jwt()));

        mockMvc.perform(asContributor(post("/api/workspaces/{ws}/profiles", WORKSPACE)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(
                                """
                                {"name":"Microservice","concept":"ApplicationComponent",
                                 "attributes":[{"key":"owner-team","name":"Owning team","type":"string"}]}
                                """)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("Microservice"));

        // Defined, therefore usable as a type — which is the whole point of a profile.
        applyCommands(
                null,
                "Add a microservice",
                """
                [{"op":"create-element","key":"payments","type":"Microservice","name":"Payments"}]
                """);
        mockMvc.perform(get("/api/workspaces/{ws}/elements", WORKSPACE).with(jwt()))
                .andExpect(jsonPath("$[0].profile").value("Microservice"))
                .andExpect(jsonPath("$[0].conceptType").value("ApplicationComponent"));

        // The same name twice: a palette entry and a DSL identifier must mean one thing.
        mockMvc.perform(asContributor(post("/api/workspaces/{ws}/profiles", WORKSPACE)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Microservice","concept":"Node"}
                                """)))
                .andExpect(status().isUnprocessableContent());

        // A name the language already uses would shadow a concept.
        mockMvc.perform(asContributor(post("/api/workspaces/{ws}/profiles", WORKSPACE)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"ApplicationComponent","concept":"ApplicationComponent"}
                                """)))
                .andExpect(status().isUnprocessableContent());

        // And a concept that cannot be specialised is refused by the matrix, not by a list here.
        mockMvc.perform(asContributor(post("/api/workspaces/{ws}/profiles", WORKSPACE)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Special","concept":"Relationship"}
                                """)))
                .andExpect(status().isUnprocessableContent());

        // In use, so it cannot be removed: the element would be left claiming a type nothing
        // defines. The database says so through `on delete restrict`.
        mockMvc.perform(asContributor(delete("/api/workspaces/{ws}/profiles/{name}", WORKSPACE, "Microservice")))
                .andExpect(status().isUnprocessableContent());
    }

    @Test
    @DisplayName("a relationship carries properties, and a command can set them")
    void sets_a_relationship_property() throws Exception {
        // Each call builds on the commit the last one made: passing a stale base is refused, which
        // is the point of the optimistic check (ADR-0004).
        String head = applyCommands(
                        null,
                        "Two elements and a line",
                        """
                        [{"op":"create-element","key":"a","type":"ApplicationComponent","name":"A"},
                         {"op":"create-element","key":"b","type":"ApplicationComponent","name":"B"},
                         {"op":"add-relationship","key":"a-b","type":"Flow","source":"a","target":"b"}]
                        """)
                .get("commitId")
                .asString();

        head = applyCommands(
                        head,
                        "Annotate it",
                        """
                        [{"op":"set-relationship-property","key":"a-b","property":"protocol","value":"AMQP"}]
                        """)
                .get("commitId")
                .asString();
        mockMvc.perform(get("/api/workspaces/{ws}/relationships", WORKSPACE).with(jwt()))
                .andExpect(jsonPath("$[0].properties.protocol").value("AMQP"));

        // Null removes it, the same reading as on an element.
        applyCommands(
                head,
                "Take it back",
                """
                [{"op":"set-relationship-property","key":"a-b","property":"protocol","value":null}]
                """);
        mockMvc.perform(get("/api/workspaces/{ws}/relationships", WORKSPACE).with(jwt()))
                .andExpect(jsonPath("$[0].properties.protocol").doesNotExist());
    }

    @Test
    @DisplayName("a reader without the contributor role cannot change the graph")
    void enforces_authorisation_on_mutations() throws Exception {
        mockMvc.perform(post("/api/workspaces/{ws}/commands", WORKSPACE)
                        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_VIEWER")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"commands":[{"op":"create-element","key":"a","type":"ApplicationComponent","name":"A"}]}
                                """))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("an unknown workspace is 404, not 500")
    void unknown_workspace_is_not_found() throws Exception {
        mockMvc.perform(get("/api/workspaces/{ws}/elements", "no-such-workspace").with(jwt()))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("an unknown operation is a 400 that lists what is supported")
    void rejects_an_unknown_operation() throws Exception {
        mockMvc.perform(asContributor(post("/api/workspaces/{ws}/commands", WORKSPACE)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("""
                                        {"commands":[{"op":"frobnicate","key":"a"}]}
                                        """)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail")
                        .value(org.hamcrest.Matchers.containsString("Unknown operation 'frobnicate'")));
    }
}
