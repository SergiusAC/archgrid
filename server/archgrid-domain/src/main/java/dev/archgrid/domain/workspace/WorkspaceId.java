package dev.archgrid.domain.workspace;

import java.util.Objects;
import java.util.UUID;

/**
 * Identity of a workspace — the isolation boundary for access control and for the
 * separation between production, sandbox and archived landscapes.
 *
 * <p>An on-premise installation usually holds a single workspace, but every table in
 * the schema carries {@code workspace_id} from day one: retrofitting a tenancy boundary
 * into a schema that never had one requires rewriting every query.
 */
public record WorkspaceId(UUID value) {

    public WorkspaceId {
        Objects.requireNonNull(value, "workspace id must not be null");
    }

    public static WorkspaceId of(UUID value) {
        return new WorkspaceId(value);
    }

    public static WorkspaceId generate() {
        return new WorkspaceId(UUID.randomUUID());
    }

    @Override
    public String toString() {
        return value.toString();
    }
}
