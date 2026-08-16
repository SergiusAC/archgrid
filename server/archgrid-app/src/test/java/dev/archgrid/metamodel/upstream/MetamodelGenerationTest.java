package dev.archgrid.metamodel.upstream;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Makes drift between the vendored upstream sources and the committed metamodel impossible.
 *
 * <p>Without this the generated file is a snapshot nobody re-derives, and a hand edit or a
 * half-applied upstream update survives indefinitely. With it, the only way to change the
 * metamodel is to change an input and regenerate — which produces a diff a human has to read.
 */
class MetamodelGenerationTest {

    private static final Path ROOT = ArchiMetamodelGenerator.repositoryRoot();
    private static final Path UPSTREAM = ROOT.resolve("metamodel/upstream/archi");
    private static final Path GENERATED = ROOT.resolve("metamodel/archimate-3.2.yaml");

    @Test
    @DisplayName("the committed metamodel is exactly what the generator produces from upstream")
    void committed_file_matches_regeneration() throws IOException {
        String regenerated = new ArchiMetamodelGenerator(UPSTREAM).generate();
        String committed = Files.readString(GENERATED, StandardCharsets.UTF_8);

        assertThat(regenerated)
                .describedAs("metamodel/archimate-3.2.yaml is stale or was edited by hand; "
                        + "run ./gradlew generateMetamodel and review the diff")
                .isEqualTo(committed);
    }

    @Test
    @DisplayName("the upstream sources still hash to what the metamodel header records")
    void upstream_checksums_match_the_generated_header() throws IOException {
        String committed = Files.readString(GENERATED, StandardCharsets.UTF_8);
        Map<String, String> expected = Map.of(
                "archimate.ecore", sha256(UPSTREAM.resolve("archimate.ecore")),
                "relationships-keys.xml", sha256(UPSTREAM.resolve("relationships-keys.xml")),
                "relationships.xml", sha256(UPSTREAM.resolve("relationships.xml")),
                "viewpoints.xml", sha256(UPSTREAM.resolve("viewpoints.xml")));

        expected.forEach((file, hash) -> assertThat(committed)
                .describedAs("checksum recorded for %s", file)
                .contains("    " + file + ": " + hash));
    }

    @Test
    @DisplayName("the generated file stays pure ASCII")
    void generated_file_is_ascii_only() throws IOException {
        String committed = Files.readString(GENERATED, StandardCharsets.UTF_8);
        assertThat(committed.chars().filter(c -> c > 0x7F).count())
                .describedAs("non-ASCII characters in a file that is diffed, parsed and shipped")
                .isZero();
    }

    @Test
    @DisplayName("camel-case concepts become readable display names")
    void display_names_are_derived_mechanically() {
        assertThat(ArchiMetamodelGenerator.displayName("ApplicationComponent")).isEqualTo("Application Component");
        assertThat(ArchiMetamodelGenerator.displayName("SystemSoftware")).isEqualTo("System Software");
        assertThat(ArchiMetamodelGenerator.displayName("Node")).isEqualTo("Node");
        // The one concept where a mechanical split reads wrong, so it carries an override.
        assertThat(ArchiMetamodelGenerator.displayName("CourseOfAction")).isEqualTo("Course of Action");
    }

    private static String sha256(Path file) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(Files.readAllBytes(file))).toLowerCase(Locale.ROOT);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is unavailable", ex);
        }
    }
}
