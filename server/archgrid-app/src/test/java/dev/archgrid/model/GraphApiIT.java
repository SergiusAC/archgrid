package dev.archgrid.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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
 * The workspace's graph, end to end.
 *
 * <p>A workspace holds exactly one connected graph (ADR-0008), so these paths carry no model
 * segment and the graph is provisioned on first access rather than created by a user.
 *
 * <p>Keys on the wire are ids (docs/09). The scenarios below name them — {@code named("kafka")} is
 * the same UUID every time — so a test reads as a story and still sends what a client sends.
 */
@SpringBootTest(
        properties = "spring.security.oauth2.resourceserver.jwt.jwk-set-uri=http://localhost/jwks-not-used")
@AutoConfigureMockMvc
@Import(PostgresContainerConfig.class)
@EnabledIf(value = "dev.archgrid.support.Docker#isAvailable", disabledReason = "Docker is not available")
class GraphApiIT {

    private static final String WORKSPACE = "acme";

    private static String named(String name) {
        return Id.named(name).toString();
    }

    private static final String PAYMENT_SERVICE = named("payment-service");
    private static final String KAFKA = named("kafka");
    private static final String NOTIFICATION_SERVICE = named("notification-service");
    private static final String PAYMENT_PUBLISHES = named("payment-publishes");
    private static final String KAFKA_SERVES = named("kafka-serves-notification");

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
              {"op":"create-element","key":"%1$s","type":"ApplicationComponent","name":"Payment Service"},
              {"op":"create-element","key":"%2$s","type":"SystemSoftware","name":"Kafka"},
              {"op":"create-element","key":"%3$s","type":"ApplicationComponent","name":"Notification Service"},
              {"op":"add-relationship","key":"%4$s","type":"Flow","source":"%1$s","target":"%2$s"},
              {"op":"add-relationship","key":"%5$s","type":"Serving","source":"%2$s","target":"%3$s"}
            ]
            """
                    .formatted(PAYMENT_SERVICE, KAFKA, NOTIFICATION_SERVICE, PAYMENT_PUBLISHES, KAFKA_SERVES);

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
                .andExpect(jsonPath("$[?(@.key=='%s')].conceptType".formatted(KAFKA)).value("SystemSoftware"));

        mockMvc.perform(get("/api/workspaces/{ws}/relationships", WORKSPACE).with(jwt()))
                .andExpect(jsonPath("$.length()").value(2));

        mockMvc.perform(get("/api/workspaces/{ws}", WORKSPACE).with(jwt()))
                .andExpect(jsonPath("$.elements").value(3))
                .andExpect(jsonPath("$.relationships").value(2));

        // The id the client chose is the id the database holds: one identity, no translation.
        Integer kafkaRows = jdbc.sql("select count(*) from element where id = :id and name = 'Kafka'")
                .param("id", UUID.fromString(KAFKA))
                .query(Integer.class)
                .single();
        assertThat(kafkaRows).isEqualTo(1);
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
                [{"op":"create-element","key":"%s","type":"ApplicationComponent","name":"Billing Service"},
                 {"op":"add-relationship","key":"%s","type":"Flow","source":"%s","target":"%s"}]
                """
                        .formatted(named("billing-service"), named("billing-publishes"), named("billing-service"), KAFKA));

        mockMvc.perform(get("/api/workspaces/{ws}/relationships", WORKSPACE).with(jwt()))
                .andExpect(jsonPath("$.length()").value(3))
                .andExpect(jsonPath("$[?(@.target=='%s')].source".formatted(KAFKA), org.hamcrest.Matchers.hasSize(2)));
    }

    @Test
    @DisplayName("an impermissible relationship is refused with 422 and an explanation")
    void refuses_an_impermissible_relationship() throws Exception {
        JsonNode first = applyCommands(null, "Base", PAYMENT_FLOW);

        mockMvc.perform(asContributor(post("/api/workspaces/{ws}/commands", WORKSPACE)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("""
                                        {"baseCommitId":"%s","message":"bad","commands":[
                                          {"op":"create-element","key":"%s","type":"DataObject","name":"Payments"},
                                          {"op":"add-relationship","key":"%s","type":"Serving","source":"%s","target":"%s"}
                                        ]}
                                        """
                                        .formatted(
                                                first.get("commitId").asString(),
                                                named("payments-data"),
                                                named("bad"),
                                                PAYMENT_SERVICE,
                                                named("payments-data")))))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.violations[0].code").value("relationship-not-permitted"))
                .andExpect(jsonPath("$.violations[0].detail")
                        .value(org.hamcrest.Matchers.containsString("Permitted instead:")));

        mockMvc.perform(get("/api/workspaces/{ws}/elements", WORKSPACE).with(jwt()))
                .andExpect(jsonPath("$.length()").value(3));
    }

    @Test
    @DisplayName("a second element with a name already taken is refused, naming the holder")
    void refuses_a_duplicate_name() throws Exception {
        JsonNode first = applyCommands(null, "Base", PAYMENT_FLOW);

        // Case and surrounding whitespace do not make it a different name.
        mockMvc.perform(asContributor(post("/api/workspaces/{ws}/commands", WORKSPACE)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("""
                                        {"baseCommitId":"%s","message":"dup","commands":[
                                          {"op":"create-element","key":"%s","type":"Node","name":"  KAFKA "}
                                        ]}
                                        """
                                        .formatted(first.get("commitId").asString(), named("kafka-2")))))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.violations[0].code").value("duplicate-name"))
                .andExpect(jsonPath("$.violations[0].subject").value(named("kafka-2")))
                .andExpect(jsonPath("$.violations[0].detail").value(org.hamcrest.Matchers.containsString(KAFKA)));

        // A rename into a taken name is the same refusal.
        mockMvc.perform(asContributor(post("/api/workspaces/{ws}/commands", WORKSPACE)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("""
                                        {"baseCommitId":"%s","message":"dup","commands":[
                                          {"op":"rename-element","key":"%s","name":"Kafka"}
                                        ]}
                                        """
                                        .formatted(first.get("commitId").asString(), PAYMENT_SERVICE))))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.violations[0].code").value("duplicate-name"));

        // Swapping two names in one batch passes through a duplicate state and is fine: the rule
        // holds for the result, not for every step.
        applyCommands(
                first.get("commitId").asString(),
                "Swap",
                """
                [{"op":"rename-element","key":"%s","name":"Kafka"},
                 {"op":"rename-element","key":"%s","name":"Payment Service"}]
                """
                        .formatted(PAYMENT_SERVICE, KAFKA));
        mockMvc.perform(get("/api/workspaces/{ws}/elements", WORKSPACE).with(jwt()))
                .andExpect(jsonPath("$[?(@.key=='%s')].name".formatted(KAFKA)).value("Payment Service"));
    }

    @Test
    @DisplayName("a deleted element gives its name up, so the next one may take it")
    void a_deleted_element_frees_its_name() throws Exception {
        String head = applyCommands(null, "Base", PAYMENT_FLOW).get("commitId").asString();
        head = applyCommands(head, "Drop Kafka", """
                [{"op":"delete-element","key":"%s"}]
                """.formatted(KAFKA))
                .get("commitId")
                .asString();

        applyCommands(head, "A new Kafka", """
                [{"op":"create-element","key":"%s","type":"SystemSoftware","name":"Kafka"}]
                """.formatted(named("kafka-again")));

        mockMvc.perform(get("/api/workspaces/{ws}/elements", WORKSPACE).with(jwt()))
                .andExpect(jsonPath("$[?(@.name=='Kafka')].key").value(named("kafka-again")));
        // The old identity row is still there for the history, and no longer holds the name.
        String oldName = jdbc.sql("select name from element where id = :id")
                .param("id", UUID.fromString(KAFKA))
                .query(String.class)
                .optional()
                .orElse(null);
        assertThat(oldName).isNull();
    }

    @Test
    @DisplayName("one relationship of a type between two elements: a second is refused, a named one is fine")
    void refuses_a_duplicate_relationship() throws Exception {
        JsonNode first = applyCommands(null, "Base", PAYMENT_FLOW);

        mockMvc.perform(asContributor(post("/api/workspaces/{ws}/commands", WORKSPACE)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("""
                                        {"baseCommitId":"%s","message":"dup","commands":[
                                          {"op":"add-relationship","key":"%s","type":"Flow","source":"%s","target":"%s","name":"invoices"}
                                        ]}
                                        """
                                        .formatted(
                                                first.get("commitId").asString(),
                                                named("second-flow"),
                                                PAYMENT_SERVICE,
                                                KAFKA))))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.violations[0].code").value("duplicate-relationship"))
                .andExpect(jsonPath("$.violations[0].detail").value(org.hamcrest.Matchers.containsString(PAYMENT_PUBLISHES)));

        // The other direction is a different statement, and so is another type.
        applyCommands(
                first.get("commitId").asString(),
                "Back and sideways",
                """
                [{"op":"add-relationship","key":"%s","type":"Flow","source":"%s","target":"%s"},
                 {"op":"add-relationship","key":"%s","type":"Serving","source":"%s","target":"%s"}]
                """
                        .formatted(named("back"), KAFKA, PAYMENT_SERVICE, named("sideways"), PAYMENT_SERVICE, KAFKA));
        mockMvc.perform(get("/api/workspaces/{ws}/relationships", WORKSPACE).with(jwt()))
                .andExpect(jsonPath("$.length()").value(4));
    }

    @Test
    @DisplayName("a relationship can be named after the fact, and unnamed again")
    void renames_a_relationship() throws Exception {
        String head = applyCommands(null, "Base", PAYMENT_FLOW).get("commitId").asString();

        head = applyCommands(head, "Name it", """
                [{"op":"rename-relationship","key":"%s","name":"payment events"}]
                """.formatted(PAYMENT_PUBLISHES))
                .get("commitId")
                .asString();
        mockMvc.perform(get("/api/workspaces/{ws}/relationships", WORKSPACE).with(jwt()))
                .andExpect(jsonPath("$[?(@.key=='%s')].name".formatted(PAYMENT_PUBLISHES)).value("payment events"));

        applyCommands(head, "Unname it", """
                [{"op":"rename-relationship","key":"%s","name":null}]
                """.formatted(PAYMENT_PUBLISHES));
        JsonNode relationships = json.readTree(mockMvc.perform(
                        get("/api/workspaces/{ws}/relationships", WORKSPACE).with(jwt()))
                .andReturn()
                .getResponse()
                .getContentAsString());
        boolean found = false;
        for (JsonNode relationship : relationships) {
            if (relationship.get("key").asString().equals(PAYMENT_PUBLISHES)) {
                assertThat(relationship.get("name").isNull()).isTrue();
                found = true;
            }
        }
        assertThat(found).isTrue();
    }

    @Test
    @DisplayName("the database refuses a duplicate name behind the validator's back — the last line of defence")
    void the_schema_refuses_a_duplicate_name() throws Exception {
        applyCommands(null, "Base", PAYMENT_FLOW);
        UUID modelId = jdbc.sql("select id from model").query(UUID.class).single();

        // A write that bypasses the pipeline entirely, as a hand-made fix in production might.
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> jdbc.sql(
                        "insert into element (id, model_id, name) values (:id, :model, 'kafka ')")
                .param("id", UUID.randomUUID())
                .param("model", modelId)
                .update())
                .isInstanceOf(org.springframework.dao.DuplicateKeyException.class)
                .hasMessageContaining("element_name_unique_per_model");

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> jdbc.sql(
                        "insert into relationship (id, model_id, concept_type, source_id, target_id) "
                                + "values (:id, :model, 'Flow', :source, :target)")
                .param("id", UUID.randomUUID())
                .param("model", modelId)
                .param("source", UUID.fromString(PAYMENT_SERVICE))
                .param("target", UUID.fromString(KAFKA))
                .update())
                .isInstanceOf(org.springframework.dao.DuplicateKeyException.class)
                .hasMessageContaining("relationship_triple_unique_per_model");
    }

    @Test
    @DisplayName("a key that is not an id — a slug from a stale client — is a 400 that says what was expected")
    void rejects_a_slug_as_key() throws Exception {
        mockMvc.perform(asContributor(post("/api/workspaces/{ws}/commands", WORKSPACE)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("""
                                        {"commands":[{"op":"create-element","key":"payment-service","type":"ApplicationComponent","name":"A"}]}
                                        """)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("UUID")));

        mockMvc.perform(get("/api/workspaces/{ws}/elements/{key}/usage", WORKSPACE, "payment-service").with(jwt()))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("history records one commit per accepted change")
    void records_history() throws Exception {
        JsonNode first = applyCommands(null, "Add the payment notification flow", PAYMENT_FLOW);
        applyCommands(
                first.get("commitId").asString(),
                "Document the payment service",
                """
                [{"op":"set-element-documentation","key":"%s","documentation":"Authorises card payments."}]
                """
                        .formatted(PAYMENT_SERVICE));

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
                [{"op":"rename-element","key":"%s","name":"Kafka cluster"}]
                """.formatted(KAFKA));

        mockMvc.perform(asContributor(post("/api/workspaces/{ws}/commands", WORKSPACE)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("""
                                        {"baseCommitId":"%s","message":"stale","commands":[
                                          {"op":"rename-element","key":"%s","name":"Something else"}
                                        ]}
                                        """
                                        .formatted(head, KAFKA))))
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
                [{"op":"create-element","key":"%s","type":"Microservice","name":"Payment Service"}]
                """
                        .formatted(PAYMENT_SERVICE));

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
                [{"op":"create-element","key":"%s","type":"Microservice","name":"Payments"}]
                """
                        .formatted(named("payments")));
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
                        [{"op":"create-element","key":"%s","type":"ApplicationComponent","name":"A"},
                         {"op":"create-element","key":"%s","type":"ApplicationComponent","name":"B"},
                         {"op":"add-relationship","key":"%s","type":"Flow","source":"%s","target":"%s"}]
                        """
                                .formatted(named("a"), named("b"), named("a-b"), named("a"), named("b")))
                .get("commitId")
                .asString();

        head = applyCommands(
                        head,
                        "Annotate it",
                        """
                        [{"op":"set-relationship-property","key":"%s","property":"protocol","value":"AMQP"}]
                        """
                                .formatted(named("a-b")))
                .get("commitId")
                .asString();
        mockMvc.perform(get("/api/workspaces/{ws}/relationships", WORKSPACE).with(jwt()))
                .andExpect(jsonPath("$[0].properties.protocol").value("AMQP"));

        // Null removes it, the same reading as on an element.
        applyCommands(
                head,
                "Take it back",
                """
                [{"op":"set-relationship-property","key":"%s","property":"protocol","value":null}]
                """
                        .formatted(named("a-b")));
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
                                {"commands":[{"op":"create-element","key":"%s","type":"ApplicationComponent","name":"A"}]}
                                """
                                .formatted(named("a"))))
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
                                        {"commands":[{"op":"frobnicate","key":"%s"}]}
                                        """
                                        .formatted(named("a")))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail")
                        .value(org.hamcrest.Matchers.containsString("Unknown operation 'frobnicate'")));
    }
}
