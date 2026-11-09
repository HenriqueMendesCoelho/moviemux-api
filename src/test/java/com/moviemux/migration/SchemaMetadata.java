package com.moviemux.migration;

import jakarta.persistence.Entity;
import org.hibernate.boot.Metadata;
import org.hibernate.boot.MetadataSources;
import org.hibernate.boot.registry.StandardServiceRegistry;
import org.hibernate.boot.registry.StandardServiceRegistryBuilder;
import org.hibernate.tool.schema.spi.SchemaManagementToolCoordinator;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AnnotationTypeFilter;

import java.io.StringWriter;
import java.util.HashMap;
import java.util.Map;

/** Exports complete metadata without starting Spring, opening JDBC, or executing DDL. */
final class SchemaMetadata {
    private SchemaMetadata() {}

    static String exportDdl() throws ClassNotFoundException {
        StringWriter output = new StringWriter();
        Map<String, Object> settings = settings();
        settings.put("jakarta.persistence.schema-generation.database.action", "none");
        settings.put("jakarta.persistence.schema-generation.scripts.action", "create");
        settings.put("jakarta.persistence.schema-generation.scripts.create-target", output);
        settings.put("hibernate.hbm2ddl.delimiter", ";");
        try (StandardServiceRegistry registry = new StandardServiceRegistryBuilder().applySettings(settings).build()) {
            SchemaManagementToolCoordinator.process(metadata(registry), registry, settings, action -> {
                throw new IllegalStateException("Offline export must never register a database drop");
            });
        }
        return output.toString();
    }

    static Map<String, Object> settings() {
        Map<String, Object> settings = new HashMap<>();
        settings.put("hibernate.dialect", "org.hibernate.dialect.PostgreSQLDialect");
        settings.put("hibernate.boot.allow_jdbc_metadata_access", false);
        settings.put("jakarta.persistence.database-product-name", "PostgreSQL");
        settings.put("jakarta.persistence.database-major-version", "14");
        settings.put("jakarta.persistence.database-minor-version", "22");
        settings.put("hibernate.physical_naming_strategy", "org.hibernate.boot.model.naming.CamelCaseToUnderscoresNamingStrategy");
        settings.put("hibernate.implicit_naming_strategy", "org.springframework.boot.orm.jpa.hibernate.SpringImplicitNamingStrategy");
        return settings;
    }

    static Metadata metadata(StandardServiceRegistry registry) throws ClassNotFoundException {
        MetadataSources sources = new MetadataSources(registry);
        var scanner = new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(Entity.class));
        for (var candidate : scanner.findCandidateComponents("com.moviemux")) {
            sources.addAnnotatedClass(Class.forName(candidate.getBeanClassName()));
        }
        return sources.buildMetadata();
    }

    public static void main(String[] args) throws Exception {
        java.nio.file.Files.writeString(java.nio.file.Path.of(args[0]), exportDdl());
    }
}
