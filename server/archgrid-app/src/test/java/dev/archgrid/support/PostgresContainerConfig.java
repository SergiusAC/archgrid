package dev.archgrid.support;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * The database used by integration tests.
 *
 * <p>Deliberately the same image as {@code deploy/docker-compose.yml}: pgvector rather
 * than stock PostgreSQL. Semantic search does not arrive until phase 7, but changing the
 * database image late means re-validating every migration against a different build of
 * PostgreSQL, and the extension costs nothing while unused.
 */
@TestConfiguration(proxyBeanMethods = false)
public class PostgresContainerConfig {

    private static final DockerImageName IMAGE =
            DockerImageName.parse("pgvector/pgvector:pg18").asCompatibleSubstituteFor("postgres");

    @Bean
    @ServiceConnection
    PostgreSQLContainer postgres() {
        return new PostgreSQLContainer(IMAGE)
                .withDatabaseName("archgrid")
                .withUsername("archgrid")
                .withPassword("archgrid");
    }
}
