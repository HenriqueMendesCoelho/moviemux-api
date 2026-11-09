package com.moviemux.userboundary;

import static org.assertj.core.api.Assertions.*;

import com.moviemux.user.adapter.repository.postgres.mapper.UserEntityMapper;
import com.moviemux.user.domain.Role;
import com.moviemux.user.domain.model.Preferences;
import com.moviemux.user.domain.model.Statistics;
import com.moviemux.user.domain.model.User;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class UserEntityMapperTest {
  private final UserEntityMapper mapper = new UserEntityMapper();

  private User account() {
    User user =
        User.builder()
            .id(UUID.fromString("00000000-0000-0000-0000-000000000001"))
            .name("Synthetic")
            .email("account@example.test")
            .password("synthetic-hash")
            .build();
    user.setRoles(Set.of(Role.ADM));
    user.setCreatedAt(OffsetDateTime.parse("2020-01-02T03:04:05Z"));
    user.setUpdatedAt(OffsetDateTime.parse("2021-02-03T04:05:06Z"));
    user.setRedefinePasswordKey("synthetic-reset-key");
    user.setRedefinePasswordKeyCreatedAt(user.getUpdatedAt());
    user.setPreferences(Preferences.builder().notify(true).user(user).build());
    user.setStatistics(Statistics.builder().user(user).build());
    user.getStatistics().setConsecutiveFailedLoginAttempts(7);
    user.getStatistics().setLastLoginAt(user.getCreatedAt());
    return user;
  }

  @Test
  void reconstitutionPreservesExactRolesCredentialsIdentifiersAndDates() {
    User original = account();
    var entity = mapper.updateEntity(original, new com.moviemux.user.domain.User());
    User restored = mapper.toDomain(entity);
    assertThat(restored).usingRecursiveComparison().isEqualTo(original);
    assertThat(restored.getRoles()).containsExactly(Role.ADM);
    assertThat(restored).isNotSameAs(entity);
    assertThat(restored.getPreferences()).isNotSameAs(entity.getPreferences());
    assertThat(restored.getStatistics()).isNotSameAs(entity.getStatistics());
    assertThat(entity.getStatistics().getUser()).isSameAs(entity);
    assertThat(entity.getPreferences().getUser()).isSameAs(entity);
  }

  @Test
  void roleInputsAndOutputsCannotAliasInternalState() {
    User user = account();
    Set<Role> roles = new HashSet<>(Set.of(Role.ADM));
    user.setRoles(roles);
    roles.clear();
    assertThat(user.getRoles()).containsExactly(Role.ADM);
    assertThatThrownBy(() -> user.getRoles().clear())
        .isInstanceOf(UnsupportedOperationException.class);
  }

  @Test
  void mapperDoesNotReadLegacyActivityGraphsDuringAuthentication() {
    var entity = new com.moviemux.user.domain.User();
    entity.setId(UUID.randomUUID());
    var statistics =
        new com.moviemux.user.domain.Statistics() {
          @Override
          public Integer getRatingsGiven() {
            throw new AssertionError("activity must be read only through profile projection");
          }

          @Override
          public Integer getDisplayTime() {
            throw new AssertionError("activity must not be fetched at login");
          }
        };
    statistics.setConsecutiveFailedLoginAttempts(3);
    entity.setStatistics(statistics);
    assertThat(mapper.toDomain(entity).getStatistics().getConsecutiveFailedLoginAttempts())
        .isEqualTo(3);
  }

  @Test
  void updatesDoNotReplaceOwnedLegacyMovieCollections() {
    User user = account();
    var entity = new com.moviemux.user.domain.User();
    var movies = new ArrayList<com.moviemux.movie.domain.Movie>();
    entity.setMovies(movies);
    mapper.updateEntity(user, entity);
    assertThat(entity.getMovies()).isSameAs(movies);
  }

  @Test
  void absentOptionalStateRemainsAbsentOnRead() {
    var entity = new com.moviemux.user.domain.User();
    var user = mapper.toDomain(entity);
    assertThat(user.getId()).isNull();
    assertThat(user.getPreferences()).isNull();
    assertThat(user.getStatistics()).isNull();
    assertThat(user.getRedefinePasswordKey()).isNull();
    assertThat(mapper.toDomain(null)).isNull();
  }
}
