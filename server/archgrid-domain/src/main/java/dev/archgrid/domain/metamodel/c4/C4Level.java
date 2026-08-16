package dev.archgrid.domain.metamodel.c4;

import dev.archgrid.domain.metamodel.Layer;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;

/**
 * A C4 level and the ArchiMate layers it admits.
 *
 * <p>The layer filter is what keeps a context diagram a context diagram. Technology elements
 * on a system context view are the most common way these diagrams stop being useful, so the
 * restriction is data rather than convention.
 *
 * @param depth how far below the view's focus this level looks: 0 for context, 1 for the
 *     focus's children, 2 for their children. This is the whole of the C4 level model.
 */
public record C4Level(String code, String name, int depth, Set<Layer> layers) {

    public C4Level {
        Objects.requireNonNull(code, "code must not be null");
        Objects.requireNonNull(name, "name must not be null");
        Objects.requireNonNull(layers, "layers must not be null");
        if (depth < 0) {
            throw new IllegalArgumentException("depth must not be negative");
        }
        if (layers.isEmpty()) {
            throw new IllegalArgumentException("a C4 level that admits no layer would always be empty: " + code);
        }
        layers = Collections.unmodifiableSet(new LinkedHashSet<>(layers));
    }

    public boolean admits(Layer layer) {
        return layers.contains(layer);
    }
}
