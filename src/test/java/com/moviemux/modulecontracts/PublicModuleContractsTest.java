package com.moviemux.modulecontracts;

import static org.assertj.core.api.Assertions.*;

import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import java.util.ArrayList;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class PublicModuleContractsTest {
  private static final Map<String, Set<String>> ALLOWED =
      Map.of(
          "user", Set.of("kronusintegrationtool"),
          "movie", Set.of("user", "kronusintegrationtool"),
          "wishlist", Set.of("user", "movie", "kronusintegrationtool"),
          "statistic", Set.of("movie"),
          "discord", Set.of(),
          "kronusintegrationtool", Set.of());

  static boolean allowed(String source, String target) {
    return source.equals(target) || ALLOWED.getOrDefault(source, Set.of()).contains(target);
  }

  @Test
  void extractedKernelsDependOnlyOnApprovedPublicDomainContracts() {
    var classes =
        new ClassFileImporter()
            .withImportOption(new ImportOption.DoNotIncludeTests())
            .importPackages("com.moviemux");
    var violations = new ArrayList<String>();
    for (var source : classes) {
      String name = source.getName();
      String[] parts = name.split("\\.");
      if (parts.length < 4 || !ALLOWED.containsKey(parts[2])) continue;
      // Legacy ORM is checked by the separate shrinking dependency baseline until relocation.
      if (!(name.contains(".domain.model.")
          || name.contains(".domain.usecase.")
          || name.contains(".domain.repository."))) continue;
      source
          .getDirectDependenciesFromSelf()
          .forEach(
              dependency -> {
                String target = dependency.getTargetClass().getName();
                String[] targetParts = target.split("\\.");
                if (!target.startsWith("com.moviemux.")
                    || targetParts.length < 4
                    || !ALLOWED.containsKey(targetParts[2])
                    || parts[2].equals(targetParts[2])) return;
                if (!allowed(parts[2], targetParts[2]) || !target.contains(".domain."))
                  violations.add(name + " -> " + target);
              });
    }
    assertThat(violations)
        .as("No reverse module edge or private implementation dependency")
        .isEmpty();
  }

  @Test
  void allowedContractGraphCannotIntroduceAReverseBusinessDependency() {
    assertThat(allowed("user", "movie")).isFalse();
    assertThat(allowed("user", "wishlist")).isFalse();
    assertThat(allowed("kronusintegrationtool", "movie")).isFalse();
    assertThat(allowed("movie", "wishlist")).isFalse();
    assertThat(allowed("wishlist", "movie")).isTrue();
    assertThat(allowed("movie", "user")).isTrue();
    assertThat(allowed("statistic", "movie")).isTrue();
  }
}
