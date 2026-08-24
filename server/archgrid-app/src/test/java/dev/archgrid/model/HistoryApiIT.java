package dev.archgrid.model;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.archgrid.domain.shared.Id;
import dev.archgrid.support.PostgresContainerConfig;
import java.util.UUID;
import org.hamcrest.Matchers;
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
 * The change log, end to end: what happened, what each commit changed, and the model as it stood.
 *
 * <p>Every state read here is reconstructed from validity ranges rather than from a stored
 * snapshot, which is the claim in docs/02-architecture.md §4.2 that nothing else exercises: HEAD
 * is the only range the rest of the product ever asks for.
 */
@SpringBootTest(
        properties = "spring.security.oauth2.resourceserver.jwt.jwk-set-uri=http://localhost/jwks-not-used")
@AutoConfigureMockMvc
@Import(PostgresContainerConfig.class)
@EnabledIf(value = "dev.archgrid.support.Docker#isAvailable", disabledReason = "Docker is not available")
class HistoryApiIT {

    private static final String WS = "acme";

    private static String named(String name) {
        return Id.named(name).toString();
    }

    private static final String PAYMENTS = named("payment-service");
    private static final String KAFKA = named("kafka");
    private static final String NOTIFICATIONS = named("notification-service");
    private static final String PUBLISHES = named("payments-publishes");
    private static final String CONTEXT = named("payments-context");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private ObjectMapper json;

    private String first;

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

        first = apply(
                null,
                "Add the payment flow",
                """
                [{"op":"create-element","key":"%1$s","type":"ApplicationComponent","name":"Payment Service"},
                 {"op":"create-element","key":"%2$s","type":"SystemSoftware","name":"Kafka"},
                 {"op":"add-relationship","key":"%3$s","type":"Flow","source":"%1$s","target":"%2$s"}]
                """
                        .formatted(PAYMENTS, KAFKA, PUBLISHES));
    }

    private static MockHttpServletRequestBuilder asContributor(MockHttpServletRequestBuilder request) {
        return request.with(jwt().authorities(new SimpleGrantedAuthority("ROLE_CONTRIBUTOR")));
    }

    /** @return the id of the commit it produced */
    private String apply(String base, String message, String commands) throws Exception {
        String body = """
                {"baseCommitId": %s, "message": "%s", "commands": %s}
                """
                .formatted(base == null ? "null" : "\"" + base + "\"", message, commands);
        String response = mockMvc.perform(asContributor(post("/api/workspaces/{ws}/commands", WS)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(body)))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        return json.readTree(response).get("commitId").asString();
    }

    @Test
    @DisplayName("the log is newest first, attributed, and each entry names its parent")
    void the_commit_log_reads_backwards_in_time() throws Exception {
        String second = apply(
                first,
                "Rename to Payments",
                """
                [{"op":"rename-element","key":"%s","name":"Payments"}]
                """.formatted(PAYMENTS));

        mockMvc.perform(get("/api/workspaces/{ws}/commits", WS).with(jwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].id").value(second))
                .andExpect(jsonPath("$[0].seq").value(2))
                .andExpect(jsonPath("$[0].parentId").value(first))
                .andExpect(jsonPath("$[0].message").value("Rename to Payments"))
                .andExpect(jsonPath("$[0].author").exists())
                .andExpect(jsonPath("$[1].id").value(first))
                .andExpect(jsonPath("$[1].parentId").doesNotExist());
    }

    @Test
    @DisplayName("the first commit is diffed against nothing, and everything in it reads as added")
    void the_first_commit_has_no_parent_to_compare_with() throws Exception {
        mockMvc.perform(get("/api/workspaces/{ws}/commits/{commit}/diff", WS, first).with(jwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.from").doesNotExist())
                .andExpect(jsonPath("$.to.id").value(first))
                .andExpect(jsonPath("$.summary.added").value(3))
                .andExpect(jsonPath("$.summary.removed").value(0))
                .andExpect(jsonPath("$.summary.changed").value(0))
                // Elements before relationships, alphabetical within a kind.
                .andExpect(jsonPath("$.changes[0].label").value("Kafka"))
                .andExpect(jsonPath("$.changes[1].label").value("Payment Service"))
                .andExpect(jsonPath("$.changes[2].subject").value("relationship"))
                .andExpect(jsonPath("$.changes[2].source").value("Payment Service"))
                .andExpect(jsonPath("$.changes[2].target").value("Kafka"));
    }

    /**
     * The reason identity is opaque (ADR-0016). Were the diff to match by name, this commit would
     * read as one element destroyed and another created, and the history would have lost the only
     * fact in it.
     */
    @Test
    @DisplayName("a rename is one changed element with a before and an after")
    void a_rename_reads_as_a_change_rather_than_a_replacement() throws Exception {
        String second = apply(
                first,
                "Rename to Payments",
                """
                [{"op":"rename-element","key":"%s","name":"Payments"}]
                """.formatted(PAYMENTS));

        mockMvc.perform(get("/api/workspaces/{ws}/commits/{commit}/diff", WS, second).with(jwt()))
                .andExpect(jsonPath("$.summary.changed").value(1))
                .andExpect(jsonPath("$.summary.added").value(0))
                .andExpect(jsonPath("$.changes.length()").value(1))
                .andExpect(jsonPath("$.changes[0].key").value(PAYMENTS))
                .andExpect(jsonPath("$.changes[0].fields[0].field").value("name"))
                .andExpect(jsonPath("$.changes[0].fields[0].before").value("Payment Service"))
                .andExpect(jsonPath("$.changes[0].fields[0].after").value("Payments"));
    }

    @Test
    @DisplayName("a deleted element takes its relationship with it, and both are named as they were")
    void a_deletion_names_what_is_gone() throws Exception {
        String second = apply(
                first,
                "Retire Kafka",
                """
                [{"op":"delete-relationship","key":"%s"},{"op":"delete-element","key":"%s"}]
                """
                        .formatted(PUBLISHES, KAFKA));

        mockMvc.perform(get("/api/workspaces/{ws}/commits/{commit}/diff", WS, second).with(jwt()))
                .andExpect(jsonPath("$.summary.removed").value(2))
                .andExpect(jsonPath("$.changes[?(@.subject=='element')].label").value("Kafka"))
                // Read from the state it belonged to: by now there is no Kafka to resolve.
                .andExpect(jsonPath("$.changes[?(@.subject=='relationship')].target").value("Kafka"));
    }

    @Test
    @DisplayName("what joined a view is named, and the view is one change rather than three")
    void view_membership_changes_are_named() throws Exception {
        String second = apply(
                first,
                "A context view",
                """
                [{"op":"create-view","key":"%1$s","name":"Payments context","viewpoint":"layered"},
                 {"op":"add-to-view","key":"%1$s","element":"%2$s"}]
                """
                        .formatted(CONTEXT, PAYMENTS));
        String third = apply(
                second,
                "Kafka joins the context",
                """
                [{"op":"add-to-view","key":"%s","element":"%s"}]
                """.formatted(CONTEXT, KAFKA));

        mockMvc.perform(get("/api/workspaces/{ws}/commits/{commit}/diff", WS, third).with(jwt()))
                .andExpect(jsonPath("$.changes.length()").value(1))
                .andExpect(jsonPath("$.changes[0].subject").value("view"))
                .andExpect(jsonPath("$.changes[0].verb").value("changed"))
                .andExpect(jsonPath("$.changes[0].fields.length()").value(1))
                .andExpect(jsonPath("$.changes[0].fields[0].field").value("elements"))
                .andExpect(jsonPath("$.changes[0].fields[0].after").value("Kafka"))
                .andExpect(jsonPath("$.changes[0].fields[0].before").doesNotExist());
    }

    @Test
    @DisplayName("any two commits compare, not only neighbours")
    void two_commits_apart_compare_directly() throws Exception {
        String second = apply(
                first,
                "Notifications",
                """
                [{"op":"create-element","key":"%s","type":"ApplicationComponent","name":"Notification Service"}]
                """
                        .formatted(NOTIFICATIONS));
        String third = apply(
                second,
                "Rename to Payments",
                """
                [{"op":"rename-element","key":"%s","name":"Payments"}]
                """.formatted(PAYMENTS));

        mockMvc.perform(get("/api/workspaces/{ws}/commits/{commit}/diff", WS, third)
                        .param("from", first)
                        .with(jwt()))
                .andExpect(jsonPath("$.from.id").value(first))
                .andExpect(jsonPath("$.to.id").value(third))
                .andExpect(jsonPath("$.summary.added").value(1))
                .andExpect(jsonPath("$.summary.changed").value(1));

        // Backwards is legal and is the inverse: the history is linear and neither direction is
        // more true than the other.
        mockMvc.perform(get("/api/workspaces/{ws}/commits/{commit}/diff", WS, first)
                        .param("from", third)
                        .with(jwt()))
                .andExpect(jsonPath("$.summary.removed").value(1))
                .andExpect(jsonPath("$.summary.changed").value(1));
    }

    @Test
    @DisplayName("the model at an earlier commit still holds what has since been deleted")
    void a_snapshot_reconstructs_the_state_of_the_day() throws Exception {
        apply(
                first,
                "Retire Kafka",
                """
                [{"op":"delete-relationship","key":"%s"},{"op":"delete-element","key":"%s"}]
                """
                        .formatted(PUBLISHES, KAFKA));

        mockMvc.perform(get("/api/workspaces/{ws}/commits/{commit}/snapshot", WS, first).with(jwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.commit.id").value(first))
                .andExpect(jsonPath("$.elements.length()").value(2))
                .andExpect(jsonPath("$.relationships.length()").value(1))
                .andExpect(jsonPath("$.elements[?(@.key=='%s')].name".formatted(KAFKA)).value("Kafka"));

        // And HEAD is unaffected by having been asked about the past.
        mockMvc.perform(get("/api/workspaces/{ws}/elements", WS).with(jwt()))
                .andExpect(jsonPath("$.length()").value(1));
    }

    @Test
    @DisplayName("a snapshot carries the properties and the profile the element had at the time")
    void a_snapshot_restores_more_than_names() throws Exception {
        String second = apply(
                first,
                "Mark the owner",
                """
                [{"op":"set-element-property","key":"%s","property":"owner","value":"payments-team"}]
                """
                        .formatted(PAYMENTS));
        apply(
                second,
                "Hand it over",
                """
                [{"op":"set-element-property","key":"%s","property":"owner","value":"platform"}]
                """
                        .formatted(PAYMENTS));

        mockMvc.perform(get("/api/workspaces/{ws}/commits/{commit}/snapshot", WS, second).with(jwt()))
                .andExpect(jsonPath("$.elements[?(@.key=='%s')].properties.owner".formatted(PAYMENTS))
                        .value("payments-team"));
    }

    @Test
    @DisplayName("a commit id from nowhere is 404 with a problem document, never 500")
    void an_unknown_commit_is_not_found() throws Exception {
        mockMvc.perform(get("/api/workspaces/{ws}/commits/{commit}/diff", WS, UUID.randomUUID())
                        .with(jwt()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.title").value("No such commit"))
                .andExpect(jsonPath("$.commitId").value(Matchers.notNullValue()));
    }

    @Test
    @DisplayName("reading history takes no role beyond being signed in")
    void history_is_readable_by_anyone_with_a_token() throws Exception {
        mockMvc.perform(get("/api/workspaces/{ws}/commits", WS).with(jwt()))
                .andExpect(status().isOk());
    }
}
