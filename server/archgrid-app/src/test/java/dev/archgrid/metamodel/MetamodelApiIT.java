package dev.archgrid.metamodel;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.archgrid.support.PostgresContainerConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(
        properties = "spring.security.oauth2.resourceserver.jwt.jwk-set-uri=http://localhost/jwks-not-used")
@AutoConfigureMockMvc
@Import(PostgresContainerConfig.class)
@EnabledIf(value = "dev.archgrid.support.Docker#isAvailable", disabledReason = "Docker is not available")
class MetamodelApiIT {

    @Autowired
    private MockMvc mockMvc;

    @Test
    @DisplayName("the editor gets the whole language, matrix included, in one call")
    void serves_the_metamodel() throws Exception {
        mockMvc.perform(get("/api/metamodel").with(jwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value("3.2"))
                .andExpect(jsonPath("$.elementTypes.length()").value(62))
                .andExpect(jsonPath("$.relationshipTypes.length()").value(11))
                .andExpect(jsonPath("$.viewpoints.length()").value(25))
                // The letter encoding, so the matrix stays about a tenth of its expanded size.
                .andExpect(jsonPath("$.permitted.ApplicationComponent.SystemSoftware").exists())
                .andExpect(jsonPath("$.elementTypes[?(@.concept=='ApplicationComponent')].layer").value("application"))
                .andExpect(jsonPath("$.elementTypes[?(@.concept=='ApplicationComponent')].c4Shape").value("box"))
                .andExpect(jsonPath("$.elementTypes[?(@.concept=='Goal')].c4Shape").value("omitted"));
    }

    @Test
    @DisplayName("a viewpoint palette comes back expanded and without the matrix pseudo-concepts")
    void serves_an_expanded_palette() throws Exception {
        mockMvc.perform(get("/api/metamodel/viewpoints/{code}/palette", "application_cooperation")
                        .with(jwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Application Cooperation"))
                .andExpect(jsonPath("$.concepts[?(@.concept=='ApplicationComponent')]").exists())
                .andExpect(jsonPath("$.concepts[?(@.concept=='Junction')]").doesNotExist())
                .andExpect(jsonPath("$.concepts[?(@.concept=='Relationship')]").doesNotExist());
    }

    @Test
    @DisplayName("the Layered viewpoint offers every real concept, which is what its empty palette means")
    void layered_viewpoint_offers_everything() throws Exception {
        mockMvc.perform(get("/api/metamodel/viewpoints/{code}/palette", "layered").with(jwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.concepts.length()").value(60));
    }

    @Test
    void unknown_viewpoint_is_not_found() throws Exception {
        mockMvc.perform(get("/api/metamodel/viewpoints/{code}/palette", "nope").with(jwt()))
                .andExpect(status().isNotFound());
    }

    @Test
    void the_metamodel_requires_authentication() throws Exception {
        mockMvc.perform(get("/api/metamodel")).andExpect(status().isUnauthorized());
    }
}
