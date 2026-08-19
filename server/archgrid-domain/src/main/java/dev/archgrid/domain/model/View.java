package dev.archgrid.domain.model;

import dev.archgrid.domain.shared.Slug;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;

/**
 * A named diagram over the repository: a context view of one system, the modules of another,
 * the course of a process.
 *
 * <p>Holds a selection of elements, not copies of them. The same {@code kafka} appears in as
 * many views as it belongs to and remains one object, which is what makes "where is this used"
 * answer for the whole landscape (ADR-0009).
 *
 * <p>Relationships are deliberately absent. A view shows every relationship whose two endpoints
 * are both on it — that is what an architect expects after dropping two elements next to each
 * other, and it removes the question of what a listed relationship should do when one of its
 * endpoints leaves the view.
 *
 * @param viewpoint a viewpoint code from the metamodel. It drives the palette; it does not
 *     forbid anything. ArchiMate viewpoints are guidance about what is relevant, and a tool
 *     that refuses an element because of one fights its user for no gain.
 * @param filters conditions on what is drawn, not on what belongs. Versioned with the rest of the
 *     view definition (ADR-0003): "this diagram is about the technology layer" is a documentation
 *     decision, and removing the filter brings the elements back where they were.
 */
public record View(Slug key, String name, String viewpoint, Set<Slug> elements, Set<ViewFilter> filters) {

    public static final int MAX_NAME_LENGTH = 200;

    public View {
        Objects.requireNonNull(key, "key must not be null");
        Objects.requireNonNull(name, "name must not be null");
        Objects.requireNonNull(viewpoint, "viewpoint must not be null");
        Objects.requireNonNull(elements, "elements must not be null");
        if (name.isBlank()) {
            throw new IllegalArgumentException("view name must not be blank: " + key);
        }
        if (name.length() > MAX_NAME_LENGTH) {
            throw new IllegalArgumentException("view name must be at most " + MAX_NAME_LENGTH + " characters: " + key);
        }
        Objects.requireNonNull(filters, "filters must not be null");
        elements = Collections.unmodifiableSet(new LinkedHashSet<>(elements));
        filters = Collections.unmodifiableSet(new LinkedHashSet<>(filters));
    }

    public static View of(Slug key, String name, String viewpoint) {
        return new View(key, name, viewpoint, Set.of(), Set.of());
    }

    public boolean contains(Slug elementKey) {
        return elements.contains(elementKey);
    }

    public View withName(String newName) {
        return new View(key, newName, viewpoint, elements, filters);
    }

    public View withViewpoint(String newViewpoint) {
        return new View(key, name, newViewpoint, elements, filters);
    }

    public View withFilters(Set<ViewFilter> newFilters) {
        return new View(key, name, viewpoint, elements, newFilters);
    }

    public View with(Slug elementKey) {
        Set<Slug> updated = new LinkedHashSet<>(elements);
        updated.add(elementKey);
        return new View(key, name, viewpoint, updated, filters);
    }

    public View without(Slug elementKey) {
        Set<Slug> updated = new LinkedHashSet<>(elements);
        updated.remove(elementKey);
        return new View(key, name, viewpoint, updated, filters);
    }
}
