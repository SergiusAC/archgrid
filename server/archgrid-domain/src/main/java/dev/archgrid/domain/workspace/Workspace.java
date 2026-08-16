package dev.archgrid.domain.workspace;

import dev.archgrid.domain.shared.Slug;
import java.util.Objects;

/**
 * A workspace groups architecture models and forms the access-control boundary.
 *
 * <p>Timestamps and audit columns are deliberately absent: they are properties of the
 * stored row, not of the concept, and keeping them out of the domain record is what lets
 * this module stay free of persistence concerns.
 */
public record Workspace(WorkspaceId id, Slug key, String name) {

    public static final int MAX_NAME_LENGTH = 200;

    public Workspace {
        Objects.requireNonNull(id, "workspace id must not be null");
        Objects.requireNonNull(key, "workspace key must not be null");
        Objects.requireNonNull(name, "workspace name must not be null");
        if (name.isBlank()) {
            throw new IllegalArgumentException("workspace name must not be blank");
        }
        if (name.length() > MAX_NAME_LENGTH) {
            throw new IllegalArgumentException(
                    "workspace name must be at most " + MAX_NAME_LENGTH + " characters, was " + name.length());
        }
    }
}
