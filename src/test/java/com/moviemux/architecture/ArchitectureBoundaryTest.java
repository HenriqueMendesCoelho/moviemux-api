package com.moviemux.architecture;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

class ArchitectureBoundaryTest {
    private static final JavaClasses CLASSES = new ClassFileImporter()
            .withImportOption(new ImportOption.DoNotIncludeTests()).importPackages("com.moviemux");

    static boolean forbidden(String source, String target) {
        boolean model = source.contains(".domain.") && !source.contains(".domain.usecase.");
        boolean useCase = source.contains(".usecase.");
        if (!model && !useCase) return false;
        if (target.startsWith("com.moviemux.") && (target.contains(".adapter.") || target.contains(".application."))) return true;
        if (target.startsWith("jakarta.persistence.") || target.startsWith("org.hibernate.")
                || target.startsWith("com.fasterxml.jackson.") || target.startsWith("jakarta.servlet.")) return true;
        if (target.startsWith("org.springframework.")) {
            return !(useCase && Set.of("org.springframework.stereotype.Service", "org.springframework.stereotype.Component",
                    "org.springframework.transaction.annotation.Transactional",
                    "org.springframework.transaction.annotation.Isolation",
                    "org.springframework.transaction.annotation.Propagation").contains(target));
        }
        return false;
    }

    @Test
    void coreDependencyViolationsMustNotExceedReviewedBaseline() throws Exception {
        Set<String> actual = new TreeSet<>();
        for (JavaClass source : CLASSES) source.getDirectDependenciesFromSelf().forEach(dependency -> {
            String target = dependency.getTargetClass().getName();
            if (forbidden(source.getName(), target)) actual.add(source.getName() + " -> " + target);
        });
        try (var resource = getClass().getResourceAsStream("/architecture/legacy-dependencies.txt")) {
            assertThat(resource).as("Reviewed migration baseline exists").isNotNull();
            Set<String> baseline = new BufferedReader(new InputStreamReader(resource, StandardCharsets.UTF_8)).lines()
                    .filter(line -> !line.isBlank() && !line.startsWith("#")).collect(Collectors.toSet());
            assertThat(actual).as("No new forbidden dependencies; remove resolved entries from baseline")
                    .containsExactlyInAnyOrderElementsOf(baseline);
        }
    }

    @Test
    void detectorRejectsFrameworkAndAdapterLeaksButAllowsPragmaticUseCaseAnnotations() {
        assertThat(forbidden("com.moviemux.user.domain.User", "jakarta.persistence.Entity")).isTrue();
        assertThat(forbidden("com.moviemux.user.domain.model.User", "org.springframework.stereotype.Component")).isTrue();
        assertThat(forbidden("com.moviemux.user.domain.repository.Users", "org.springframework.data.domain.Page")).isTrue();
        assertThat(forbidden("com.moviemux.movie.usecase.Search", "com.moviemux.movie.adapter.repository.MovieRepository")).isTrue();
        assertThat(forbidden("com.moviemux.movie.usecase.Search", "org.springframework.scheduling.annotation.Scheduled")).isTrue();
        assertThat(forbidden("com.moviemux.movie.domain.usecase.Save", "org.springframework.transaction.annotation.Transactional")).isFalse();
        assertThat(forbidden("com.moviemux.movie.usecase.Search", "org.springframework.stereotype.Component")).isFalse();
        assertThat(forbidden("com.moviemux.movie.domain.Movie", "java.util.UUID")).isFalse();
        assertThat(forbidden("com.moviemux.movie.domain.usecase.Save", "org.springframework.transaction.annotation.Propagation")).isFalse();
        assertThat(forbidden("com.moviemux.movie.domain.usecase.Save", "org.springframework.transaction.PlatformTransactionManager")).isTrue();
    }
}
