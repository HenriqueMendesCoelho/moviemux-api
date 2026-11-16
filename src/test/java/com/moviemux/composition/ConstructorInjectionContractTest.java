package com.moviemux.composition;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noFields;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.moviemux.application.spring.configuration.SecurityConfig;
import com.moviemux.movie.adapter.repository.jpa.MovieGenreJpaRepository;
import com.moviemux.movie.adapter.repository.jpa.MovieJpaRepository;
import com.moviemux.movie.adapter.repository.jpa.specification.impl.MovieGenreSpecificationImpl;
import com.moviemux.movie.adapter.repository.jpa.specification.impl.MovieSpecificationImpl;
import com.moviemux.wishlist.adapter.controller.impl.WishlistControllerImpl;
import com.moviemux.wishlist.application.spring.controller.SpringUserWishlistController;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import java.lang.reflect.Modifier;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class ConstructorInjectionContractTest {
  private static final ArchRule NO_FIELD_INJECTION =
      noFields()
          .should()
          .beAnnotatedWith(Autowired.class)
          .because("bean dependencies must be explicit constructor parameters");

  @Test
  void productionCodeHasNoAutowiredFields() {
    NO_FIELD_INJECTION.check(
        new ClassFileImporter()
            .withImportOption(new ImportOption.DoNotIncludeTests())
            .importPackages("com.moviemux"));
  }

  @Test
  void ruleRejectsFieldInjectionAndAcceptsConstructorInjection() {
    assertThatThrownBy(
            () ->
                NO_FIELD_INJECTION.check(
                    new ClassFileImporter().importClasses(UnsafeFieldInjection.class)))
        .isInstanceOf(AssertionError.class)
        .hasMessageContaining("UnsafeFieldInjection");
    NO_FIELD_INJECTION.check(
        new ClassFileImporter().importClasses(SafeConstructorInjection.class));
  }

  @Test
  void reconciledWiringHasOneConstructorAndFinalDependencies() {
    for (Class<?> type :
        List.of(
            SecurityConfig.class,
            WishlistControllerImpl.class,
            SpringUserWishlistController.class,
            MovieSpecificationImpl.class,
            MovieGenreSpecificationImpl.class)) {
      assertThat(type.getDeclaredConstructors()).as(type.getName()).hasSize(1);
      assertThat(type.getDeclaredConstructors()[0].getParameterCount()).isPositive();
      for (var field : type.getDeclaredFields()) {
        if (!Modifier.isStatic(field.getModifiers())) {
          assertThat(Modifier.isFinal(field.getModifiers()))
              .as("%s.%s is an immutable dependency", type.getName(), field.getName())
              .isTrue();
        }
      }
    }
  }

  @Test
  void specificationConstructorsDependOnBaseRepositoriesNotTheirOwnFragments() {
    assertThat(MovieSpecificationImpl.class.getDeclaredConstructors()[0].getParameterTypes())
        .containsExactly(MovieJpaRepository.class);
    assertThat(MovieGenreSpecificationImpl.class.getDeclaredConstructors()[0].getParameterTypes())
        .containsExactly(MovieGenreJpaRepository.class);
  }

  static class UnsafeFieldInjection {
    @Autowired Runnable dependency;
  }

  static class SafeConstructorInjection {
    private final Runnable dependency;

    SafeConstructorInjection(Runnable dependency) {
      this.dependency = dependency;
    }
  }
}
