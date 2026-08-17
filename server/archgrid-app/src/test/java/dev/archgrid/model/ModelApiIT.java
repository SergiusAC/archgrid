package dev.archgrid.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
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
 * Phase 2 acceptance, end to end: build the model from the product brief through the API, have
 * an impermissible relationship refused with an explanation, and see the history.
 */
@SpringBootTest(
        properties = "spring.security.oauth2.resourceserver.jwt.jwk-set-uri=http://localhost/jwks-not-used")
@AutoConfigureMockMvc
@Import(PostgresContainerConfig.class)
@EnabledIf(value = "dev.archgrid.support.Docker#isAvailable", disabledReason = "Docker is not available")
class ModelApiIT {

    private static final String WORKSPACE = "acme";
    private static final String MODEL = "payments";

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

    private static MockHttpServletRequestBuilder asOwner(MockHttpServletRequestBuilder request) {
        return request.with(jwt().authorities(
                new SimpleGrantedAuthority("ROLE_OWNER"), new SimpleGrantedAuthority("ROLE_CONTRIBUTOR")));
    }

    private String createModel() throws Exception {
        String body = mockMvc.perform(asOwner(post("/api/workspaces/{ws}/models", WORKSPACE)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"key\":\"%s\",\"name\":\"Payments platform\"}".formatted(MODEL))))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "/api/workspaces/acme/models/payments"))
                .andReturn()
                .getResponse()
                .getContentAsString();
        return json.readTree(body).get("id").asString();
    }

    private JsonNode applyCommands(String baseCommitId, String message, String commandsJson) throws Exception {
        String base = baseCommitId == null ? "null" : "\"" + baseCommitId + "\"";
        String body = """
                {"baseCommitId": %s, "message": "%s", "commands": %s}
                """.formatted(base, message, commandsJson);
        String response = mockMvc.perform(asOwner(post("/api/workspaces/{ws}/models/{m}/commands", WORKSPACE, MODEL)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(body)))
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
    @DisplayName("Payment Service -> Kafka -> Notification Service, built and read back through the API")
    void builds_the_canonical_model() throws Exception {
        createModel();
        JsonNode commit = applyCommands(null, "Add the payment notification flow", PAYMENT_FLOW);

        assertThat(commit.get("elements").asInt()).isEqualTo(3);
        assertThat(commit.get("relationships").asInt()).isEqualTo(2);

        mockMvc.perform(get("/api/workspaces/{ws}/models/{m}/elements", WORKSPACE, MODEL).with(jwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(3))
                .andExpect(jsonPath("$[?(@.key=='kafka')].conceptType").value("SystemSoftware"))
                .andExpect(jsonPath("$[?(@.key=='payment-service')].name").value("Payment Service"));

        mockMvc.perform(get("/api/workspaces/{ws}/models/{m}/relationships", WORKSPACE, MODEL).with(jwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[?(@.key=='payment-publishes')].type").value("Flow"));
    }

    @Test
    @DisplayName("an impermissible relationship is refused with 422 and an explanation naming the alternatives")
    void refuses_an_impermissible_relationship() throws Exception {
        createModel();
        JsonNode first = applyCommands(null, "Base", PAYMENT_FLOW);
        String head = first.get("commitId").asString();

        mockMvc.perform(asOwner(post("/api/workspaces/{ws}/models/{m}/commands", WORKSPACE, MODEL)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        """
                                        {"baseCommitId":"%s","message":"bad","commands":[
                                          {"op":"create-element","key":"payments-data","type":"DataObject","name":"Payments"},
                                          {"op":"add-relationship","key":"bad","type":"Serving","source":"payment-service","target":"payments-data"}
                                        ]}
                                        """
                                                .formatted(head))))
                // isUnprocessableContent, not isUnprocessableEntity: RFC 9110 renamed 422 and
                // Spring 7 deprecated the old spelling. Same status code.
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.title").value("The change would make the model invalid"))
                .andExpect(jsonPath("$.violations[0].code").value("relationship-not-permitted"))
                .andExpect(jsonPath("$.violations[0].subject").value("bad"))
                .andExpect(jsonPath("$.violations[0].detail")
                        .value(org.hamcrest.Matchers.containsString(
                                "Serving is not permitted from Application Component to Data Object")))
                .andExpect(jsonPath("$.violations[0].detail")
                        .value(org.hamcrest.Matchers.containsString("Permitted instead:")));

        // The refused batch changed nothing, not even the element it could legally have created.
        mockMvc.perform(get("/api/workspaces/{ws}/models/{m}/elements", WORKSPACE, MODEL).with(jwt()))
                .andExpect(jsonPath("$.length()").value(3));
    }

    @Test
    @DisplayName("history records one commit per accepted change, with author and message")
    void records_history() throws Exception {
        createModel();
        JsonNode first = applyCommands(null, "Add the payment notification flow", PAYMENT_FLOW);
        applyCommands(
                first.get("commitId").asString(),
                "Document the payment service",
                """
                [{"op":"set-element-documentation","key":"payment-service","documentation":"Authorises and captures card payments."}]
                """);

        mockMvc.perform(get("/api/workspaces/{ws}/models/{m}/commits", WORKSPACE, MODEL).with(jwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].seq").value(2))
                .andExpect(jsonPath("$[0].message").value("Document the payment service"))
                .andExpect(jsonPath("$[1].seq").value(1));

        mockMvc.perform(get("/api/workspaces/{ws}/models/{m}/elements", WORKSPACE, MODEL).with(jwt()))
                .andExpect(jsonPath("$[?(@.key=='payment-service')].documentation")
                        .value("Authorises and captures card payments."));
    }

    @Test
    @DisplayName("a stale baseCommitId gets 409 with both commit ids, not a silent overwrite")
    void detects_a_stale_base_commit() throws Exception {
        createModel();
        JsonNode first = applyCommands(null, "Base", PAYMENT_FLOW);
        String head = first.get("commitId").asString();
        applyCommands(
                head,
                "Rename",
                """
                [{"op":"rename-element","key":"kafka","name":"Kafka cluster"}]
                """);

        mockMvc.perform(asOwner(post("/api/workspaces/{ws}/models/{m}/commands", WORKSPACE, MODEL)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        """
                                        {"baseCommitId":"%s","message":"stale","commands":[
                                          {"op":"rename-element","key":"kafka","name":"Something else"}
                                        ]}
                                        """
                                                .formatted(head))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.title").value("The model has changed"))
                .andExpect(jsonPath("$.expectedBaseCommitId").value(head))
                .andExpect(jsonPath("$.currentHeadCommitId").exists());
    }

    @Test
    @DisplayName("a profile defined in the model is usable as an element type")
    void creates_an_element_from_a_profile() throws Exception {
        String modelId = createModel();
        jdbc.sql("insert into profile (id, model_id, name, concept_type, attributes) "
                        + "values (:id, :modelId, 'Microservice', 'ApplicationComponent', "
                        + "'[{\"key\":\"owner-team\",\"name\":\"Owning team\",\"type\":\"string\"}]'::jsonb)")
                .param("id", UUID.randomUUID())
                .param("modelId", UUID.fromString(modelId))
                .update();

        applyCommands(
                null,
                "Add a microservice",
                """
                [{"op":"create-element","key":"payment-service","type":"Microservice","name":"Payment Service"},
                 {"op":"set-element-property","key":"payment-service","property":"owner-team","value":"payments"}]
                """);

        mockMvc.perform(get("/api/workspaces/{ws}/models/{m}/elements", WORKSPACE, MODEL).with(jwt()))
                .andExpect(jsonPath("$[0].type").value("Microservice"))
                .andExpect(jsonPath("$[0].conceptType").value("ApplicationComponent"))
                .andExpect(jsonPath("$[0].profile").value("Microservice"))
                .andExpect(jsonPath("$[0].properties['owner-team']").value("payments"));
    }

    @Test
    @DisplayName("a reader without the contributor role cannot change the model")
    void enforces_authorisation_on_mutations() throws Exception {
        createModel();

        mockMvc.perform(post("/api/workspaces/{ws}/models/{m}/commands", WORKSPACE, MODEL)
                        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_VIEWER")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(
                                """
                                {"commands":[{"op":"create-element","key":"a","type":"ApplicationComponent","name":"A"}]}
                                """))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("an unknown model is 404, not 500 — the catch-all must not swallow status-carrying exceptions")
    void unknown_model_is_not_found() throws Exception {
        mockMvc.perform(get("/api/workspaces/{ws}/models/{m}/elements", WORKSPACE, "nope").with(jwt()))
                .andExpect(status().isNotFound());

        mockMvc.perform(get("/api/workspaces/{ws}/models", "no-such-workspace").with(jwt()))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("an unknown operation is a 400 that lists what is supported")
    void rejects_an_unknown_operation() throws Exception {
        createModel();

        mockMvc.perform(asOwner(post("/api/workspaces/{ws}/models/{m}/commands", WORKSPACE, MODEL)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        """
                                        {"commands":[{"op":"frobnicate","key":"a"}]}
                                        """)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail")
                        .value(org.hamcrest.Matchers.containsString("Unknown operation 'frobnicate'")));
    }
}
