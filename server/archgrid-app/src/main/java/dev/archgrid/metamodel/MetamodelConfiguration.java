package dev.archgrid.metamodel;

import dev.archgrid.domain.metamodel.Metamodel;
import dev.archgrid.domain.metamodel.c4.C4Mapping;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;

/**
 * Loads the metamodel once, at startup, from the classpath.
 *
 * <p>Failure here is fatal by design. A running instance whose metamodel did not load is one
 * that validates nothing while looking perfectly healthy, and the whole point of the product is
 * that its answers can be trusted.
 */
@Configuration(proxyBeanMethods = false)
class MetamodelConfiguration {

    private static final Logger log = LoggerFactory.getLogger(MetamodelConfiguration.class);

    private static final String METAMODEL = "metamodel/archimate-3.2.yaml";
    private static final String C4_MAPPING = "metamodel/c4-mapping.yaml";

    @Bean
    Metamodel metamodel() {
        try (InputStream source = open(METAMODEL)) {
            Metamodel metamodel = MetamodelLoader.load(source);
            log.info(
                    "Loaded {} {} metamodel: {} element types, {} relationship types, {} viewpoints",
                    metamodel.language(),
                    metamodel.version(),
                    metamodel.elementTypes().size(),
                    metamodel.relationshipTypes().size(),
                    metamodel.viewpoints().size());
            return metamodel;
        } catch (IOException ex) {
            throw new UncheckedIOException("could not read " + METAMODEL, ex);
        }
    }

    @Bean
    C4Mapping c4Mapping(Metamodel metamodel) {
        try (InputStream source = open(C4_MAPPING)) {
            return C4MappingLoader.load(source, metamodel);
        } catch (IOException ex) {
            throw new UncheckedIOException("could not read " + C4_MAPPING, ex);
        }
    }

    private static InputStream open(String path) throws IOException {
        ClassPathResource resource = new ClassPathResource(path);
        if (!resource.exists()) {
            throw new IllegalStateException(path + " is missing from the classpath; the build did not copy it");
        }
        return resource.getInputStream();
    }
}
