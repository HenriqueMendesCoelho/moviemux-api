package com.moviemux.userboundary;

import static org.assertj.core.api.Assertions.assertThat;

import com.moviemux.user.domain.model.Role;
import com.moviemux.user.domain.model.Preferences;
import com.moviemux.user.domain.model.Statistics;
import com.moviemux.user.domain.model.User;
import com.moviemux.user.domain.repository.UserRepository;
import java.time.OffsetDateTime;
import java.util.Comparator;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@org.springframework.test.context.ActiveProfiles("test")
@SpringBootTest
class UserPersistenceIT {
  @Autowired UserRepository users;
  @Autowired com.moviemux.user.adapter.repository.UserRepository legacy;
  private UUID createdId;

  @AfterEach
  void cleanupSyntheticAccount() {
    if (createdId != null) legacy.deleteById(createdId);
  }

  @Test
  void roundTripIsCompleteAndDetachedAfterAdapterTransactionEnds() {
    User user =
        User.builder()
            .name("Synthetic mapper test")
            .email("mapper-" + UUID.randomUUID() + "@example.test")
            .password("synthetic-hash")
            .build();
    user.setRoles(Set.of(Role.ADM));
    user.setPreferences(Preferences.builder().user(user).notify(true).build());
    user.setStatistics(Statistics.builder().user(user).build());
    user.getStatistics().setConsecutiveFailedLoginAttempts(4);
    user.getStatistics().setLastLoginAt(OffsetDateTime.parse("2020-01-02T03:04:05Z"));
    user.setRedefinePasswordKey("synthetic-reset-key");
    user.setRedefinePasswordKeyCreatedAt(OffsetDateTime.parse("2020-01-02T03:04:05Z"));
    User saved = users.save(user);
    createdId = saved.getId();
    assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
    User loaded = users.findById(createdId).orElseThrow();
    assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
    assertThat(loaded.getClass()).isEqualTo(User.class);
    // PostgreSQL timestamptz preserves the instant, not the original offset representation.
    assertThat(loaded)
        .usingRecursiveComparison()
        .withComparatorForType(
            Comparator.comparing(OffsetDateTime::toInstant), OffsetDateTime.class)
        .isEqualTo(saved);
    assertThat(loaded.getRoles()).containsExactly(Role.ADM);
    assertThat(loaded.getCreatedAt()).isNotNull();
    assertThat(loaded.getUpdatedAt()).isNotNull();
    assertThat(loaded.getCreatedAt().toInstant()).isEqualTo(saved.getCreatedAt().toInstant());
    assertThat(loaded.getUpdatedAt().toInstant()).isEqualTo(saved.getUpdatedAt().toInstant());
    assertThat(loaded.getStatistics().getLastLoginAt().toInstant())
        .isEqualTo(user.getStatistics().getLastLoginAt().toInstant());
    assertThat(loaded.getRedefinePasswordKeyCreatedAt().toInstant())
        .isEqualTo(user.getRedefinePasswordKeyCreatedAt().toInstant());
    assertThat(loaded.getPreferences().isNotify()).isTrue();
    assertThat(loaded.getPreferences().getId()).isEqualTo(createdId);
    assertThat(loaded.getStatistics().getId()).isEqualTo(createdId);
    assertThat(loaded.getStatistics().getConsecutiveFailedLoginAttempts()).isEqualTo(4);
    assertThat(loaded.getRedefinePasswordKey()).isEqualTo("synthetic-reset-key");
  }
}
