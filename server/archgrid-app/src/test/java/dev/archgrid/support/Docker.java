package dev.archgrid.support;

import org.testcontainers.DockerClientFactory;

/**
 * Guard for tests that need a container runtime.
 *
 * <p>Tests that require Docker are skipped rather than failed when it is absent, so that
 * {@code gradlew build} stays usable on a machine where Docker Desktop is not running.
 * CI has Docker, so nothing is silently lost there — but a developer who cannot run the
 * build at all stops running it.
 */
public final class Docker {

    private Docker() {}

    public static boolean isAvailable() {
        try {
            return DockerClientFactory.instance().isDockerAvailable();
        } catch (RuntimeException ex) {
            return false;
        }
    }
}
