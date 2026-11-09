package com.moviemux.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;

class ArchitectureBoundaryTest {
  private static final JavaClasses CLASSES =
      new ClassFileImporter()
          .withImportOption(new ImportOption.DoNotIncludeTests())
          .importPackages("com.moviemux");

  static boolean forbidden(String source, String target) {
    boolean model = source.contains(".domain.") && !source.contains(".domain.usecase.");
    boolean useCase = source.contains(".usecase.");
    if (!model && !useCase) return false;
    if (target.startsWith("com.moviemux.")
        && (target.contains(".adapter.") || target.contains(".application."))) return true;
    if (target.startsWith("jakarta.persistence.")
        || target.startsWith("org.hibernate.")
        || target.startsWith("com.fasterxml.jackson.")
        || target.startsWith("jakarta.servlet.")) return true;
    if (target.startsWith("org.springframework.")) {
      return !(useCase
          && Set.of(
                  "org.springframework.stereotype.Service",
                  "org.springframework.stereotype.Component",
                  "org.springframework.transaction.annotation.Transactional",
                  "org.springframework.transaction.annotation.Isolation",
                  "org.springframework.transaction.annotation.Propagation")
              .contains(target));
    }
    return false;
  }

  @Test
  void coreMustHaveNoFrameworkOrImplementationLeaks() {
    Set<String> actual = new TreeSet<>();
    for (JavaClass source : CLASSES)
      source
          .getDirectDependenciesFromSelf()
          .forEach(
              dependency -> {
                String target = dependency.getTargetClass().getName();
                if (forbidden(source.getName(), target))
                  actual.add(source.getName() + " -> " + target);
              });
    assertThat(actual).as("Pure core has no temporary dependency exceptions").isEmpty();
  }

  @Test
  void persistenceEntitiesMustLiveInPostgresAdapters() {
    for (JavaClass source : CLASSES) {
      if (source.isAnnotatedWith(jakarta.persistence.Entity.class)
          || source.isAnnotatedWith(jakarta.persistence.Embeddable.class)) {
        assertThat(source.getPackageName()).endsWith(".adapter.repository.postgres.entity");
      }
    }
  }

  @Test
  void compositionBridgesUsePublicDomainContractsOnly() {
    Set<String> violations = new TreeSet<>();
    for (JavaClass source : CLASSES)
      if (source.getPackageName().contains(".application.spring.bridge")) {
        source
            .getDirectDependenciesFromSelf()
            .forEach(
                dependency -> {
                  String target = dependency.getTargetClass().getName();
                  if (target.startsWith("com.moviemux.") && target.contains(".adapter."))
                    violations.add(source.getName() + " -> " + target);
                });
      }
    assertThat(violations).isEmpty();
  }

  @Test
  void businessModuleGraphMustBeAcyclicAndUsePublicContracts() {
    java.util.Map<String, Set<String>> graph = new java.util.TreeMap<>();
    for (JavaClass source : CLASSES)
      if (source.getName().contains(".domain.")) {
        String module = source.getName().split("\\.")[2];
        source
            .getDirectDependenciesFromSelf()
            .forEach(
                dependency -> {
                  String target = dependency.getTargetClass().getName();
                  if (!target.startsWith("com.moviemux.")) return;
                  String targetModule = target.split("\\.")[2];
                  if (module.equals(targetModule)) return;
                  assertThat(target)
                      .as("Cross-module business dependencies use public contracts")
                      .matches(
                          "com\\.moviemux\\.[^.]+\\.domain\\.(model|repository|exception)\\..+");
                  graph.computeIfAbsent(module, ignored -> new TreeSet<>()).add(targetModule);
                });
      }
    assertThat(hasCycle(graph)).as("Business module dependencies have no cycles").isFalse();
  }

  static boolean hasCycle(java.util.Map<String, Set<String>> graph) {
    for (String source : graph.keySet())
      if (reaches(graph, source, source, new java.util.HashSet<>())) return true;
    return false;
  }

  private static boolean reaches(
      java.util.Map<String, Set<String>> graph, String source, String target, Set<String> visited) {
    if (!visited.add(source)) return false;
    for (String next : graph.getOrDefault(source, Set.of())) {
      if (next.equals(target) || reaches(graph, next, target, visited)) return true;
    }
    return false;
  }

  @Test
  void completeModuleGraphIncludingAdaptersMustBeAcyclic() {
    java.util.Map<String, Set<String>> graph = new java.util.TreeMap<>();
    for (JavaClass source : CLASSES) {
      if (!source.getName().startsWith("com.moviemux.")) continue;
      String module = source.getName().split("\\.")[2];
      source
          .getDirectDependenciesFromSelf()
          .forEach(
              dependency -> {
                String target = dependency.getTargetClass().getName();
                if (!target.startsWith("com.moviemux.")) return;
                String targetModule = target.split("\\.")[2];
                if (module.equals(targetModule)) return;
                graph.computeIfAbsent(module, ignored -> new TreeSet<>()).add(targetModule);
                if (Set.of(
                        "user",
                        "movie",
                        "wishlist",
                        "statistic",
                        "discord",
                        "kronusintegrationtool")
                    .contains(targetModule)) {
                  assertThat(target)
                      .as("Other business modules expose only public domain contracts")
                      .matches(
                          "com\\.moviemux\\.[^.]+\\.domain\\.(model|repository|exception|usecase)\\..+")
                      .doesNotContain(".usecase.impl.");
                }
              });
    }
    assertThat(hasCycle(graph)).as("Complete module graph: %s", graph).isFalse();
  }

  @Test
  void cycleDetectorRejectsCyclesAndAcceptsAcyclicGraphs() {
    assertThat(hasCycle(java.util.Map.of("user", Set.of("movie"), "movie", Set.of("user"))))
        .isTrue();
    assertThat(hasCycle(java.util.Map.of("movie", Set.of("user"), "wishlist", Set.of("movie"))))
        .isFalse();
  }

  @Test
  void detectorRejectsFrameworkAndAdapterLeaksButAllowsPragmaticUseCaseAnnotations() {
    assertThat(forbidden("com.moviemux.user.domain.model.UnsafeUser", "jakarta.persistence.Entity"))
        .isTrue();
    assertThat(
            forbidden(
                "com.moviemux.user.domain.model.User", "org.springframework.stereotype.Component"))
        .isTrue();
    assertThat(
            forbidden(
                "com.moviemux.user.domain.repository.Users",
                "org.springframework.data.domain.Page"))
        .isTrue();
    assertThat(
            forbidden(
                "com.moviemux.movie.usecase.Search",
                "com.moviemux.movie.adapter.repository.MovieRepository"))
        .isTrue();
    assertThat(
            forbidden(
                "com.moviemux.movie.usecase.Search",
                "org.springframework.scheduling.annotation.Scheduled"))
        .isTrue();
    assertThat(
            forbidden(
                "com.moviemux.movie.domain.usecase.Save",
                "org.springframework.transaction.annotation.Transactional"))
        .isFalse();
    assertThat(
            forbidden(
                "com.moviemux.movie.usecase.Search", "org.springframework.stereotype.Component"))
        .isFalse();
    assertThat(
            forbidden(
                "com.moviemux.movie.adapter.repository.postgres.entity.MovieEntity",
                "java.util.UUID"))
        .isFalse();
    assertThat(
            forbidden(
                "com.moviemux.movie.domain.usecase.Save",
                "org.springframework.transaction.annotation.Propagation"))
        .isFalse();
    assertThat(
            forbidden(
                "com.moviemux.movie.domain.usecase.Save",
                "org.springframework.transaction.PlatformTransactionManager"))
        .isTrue();
  }
}
