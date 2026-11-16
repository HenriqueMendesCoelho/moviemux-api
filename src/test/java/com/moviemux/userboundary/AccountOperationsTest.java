package com.moviemux.userboundary;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.moviemux.user.adapter.security.BCryptPasswordHasher;
import com.moviemux.user.domain.model.Role;
import com.moviemux.user.domain.command.BlockUserCommand;
import com.moviemux.user.domain.command.DemoteUserCommand;
import com.moviemux.user.domain.command.PromoteUserCommand;
import com.moviemux.user.domain.command.RedeemPasswordResetCommand;
import com.moviemux.user.domain.command.UpdateProfileCommand;
import com.moviemux.user.domain.exception.UserNotFoundException;
import com.moviemux.user.domain.exception.UserRedefinePasswordKeyInvalid;
import com.moviemux.user.domain.model.Preferences;
import com.moviemux.user.domain.model.Statistics;
import com.moviemux.user.domain.model.User;
import com.moviemux.user.domain.model.UserIdentity;
import com.moviemux.user.domain.repository.PasswordHasher;
import com.moviemux.user.domain.repository.UserRepository;
import com.moviemux.user.domain.usecase.BlockUserUseCase;
import com.moviemux.user.domain.usecase.DemoteUserUseCase;
import com.moviemux.user.domain.usecase.PromoteUserUseCase;
import com.moviemux.user.domain.usecase.RedeemPasswordResetUseCase;
import com.moviemux.user.domain.usecase.UpdateProfileUseCase;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

class AccountOperationsTest {
  private final UserRepository users = mock(UserRepository.class);

  private User account() {
    User user =
        User.builder()
            .id(UUID.fromString("00000000-0000-0000-0000-000000000001"))
            .name("Before")
            .email("before@example.test")
            .password("old")
            .build();
    user.setPreferences(Preferences.builder().notify(false).user(user).build());
    user.setStatistics(Statistics.builder().user(user).build());
    when(users.findById(user.getId())).thenReturn(Optional.of(user));
    when(users.save(user)).thenReturn(user);
    return user;
  }

  @Test
  void profileUpdatesOnlyRequestedFields() throws Exception {
    var user = account();
    var id = user.getId();
    var roles = user.getRoles();
    var result =
        new UpdateProfileUseCase(users)
            .execute(new UpdateProfileCommand(id, "After", "after@example.test", true));
    assertThat(result.getName()).isEqualTo("After");
    assertThat(result.getEmail()).isEqualTo("after@example.test");
    assertThat(result.getPreferences().isNotify()).isTrue();
    assertThat(result.getId()).isEqualTo(id);
    assertThat(result.getRoles()).isEqualTo(roles);
    assertThat(result.getPassword()).isEqualTo("old");
    verify(users).save(user);
  }

  @Test
  void missingProfileDoesNotWrite() {
    assertThatThrownBy(
            () ->
                new UpdateProfileUseCase(users)
                    .execute(
                        new UpdateProfileCommand(UUID.randomUUID(), "x", "x@example.test", false)))
        .isInstanceOf(UserNotFoundException.class);
    verify(users, never()).save(any());
  }

  @Test
  void promotionAddsAdminWithoutRemovingUserRole() throws Exception {
    var user = account();
    new PromoteUserUseCase(users).execute(new PromoteUserCommand(user.getId()));
    assertThat(user.getRoles()).containsExactlyInAnyOrder(Role.USER, Role.ADM);
    verify(users).save(user);
  }

  @Test
  void demotionRemovesOnlyAdmin() throws Exception {
    var user = account();
    user.addRole(Role.ADM);
    new DemoteUserUseCase(users).execute(new DemoteUserCommand(user.getId()));
    assertThat(user.getRoles()).containsExactly(Role.USER);
    verify(users).save(user);
  }

  @Test
  void demotingNonAdministratorDoesNotWrite() throws Exception {
    var user = account();
    new DemoteUserUseCase(users).execute(new DemoteUserCommand(user.getId()));
    verify(users, never()).save(any());
  }

  @Test
  void blockingSetsExistingThresholdExactly() throws Exception {
    var user = account();
    user.getStatistics().setConsecutiveFailedLoginAttempts(2);
    new BlockUserUseCase(users).execute(new BlockUserCommand(user.getId()));
    assertThat(user.getStatistics().getConsecutiveFailedLoginAttempts()).isEqualTo(10);
    verify(users).save(user);
  }

  @ParameterizedTest
  @ValueSource(ints = {3599, 3600, 3601})
  void resetExpirationUsesInjectedClockAtExactBoundary(int seconds) throws Exception {
    var now = Instant.parse("2025-01-01T12:00:00Z");
    Clock clock = Clock.fixed(now, ZoneOffset.UTC);
    var user = account();
    user.setRedefinePasswordKey("key");
    user.setRedefinePasswordKeyCreatedAt(
        OffsetDateTime.ofInstant(now.minusSeconds(seconds), ZoneOffset.UTC));
    when(users.findByRedefinePasswordKey("key")).thenReturn(user);
    PasswordHasher hash = mock(PasswordHasher.class);
    when(hash.encode("new")).thenReturn("new-hash");
    var operation = new RedeemPasswordResetUseCase(users, clock, hash, mock(com.moviemux.user.domain.repository.UserMailSender.class));
    var command = new RedeemPasswordResetCommand("key", user.getEmail(), "new");
    if (seconds < 3600) {
      assertThat(operation.execute(command).getPassword()).isEqualTo("new-hash");
    } else {
      assertThatThrownBy(() -> operation.execute(command))
          .isInstanceOf(UserRedefinePasswordKeyInvalid.class);
      verify(hash, never()).encode(any());
    }
    assertThat(user.getRedefinePasswordKey()).isNull();
    assertThat(user.getRedefinePasswordKeyCreatedAt()).isNull();
    verify(users).save(user);
  }

  @Test
  void bcryptAdapterPreservesMatchingPolicy() {
    var adapter = new BCryptPasswordHasher(new BCryptPasswordEncoder());
    String hash = adapter.encode("SyntheticPassword1!");
    assertThat(hash).startsWith("$2a$");
    assertThat(adapter.matches("SyntheticPassword1!", hash)).isTrue();
    assertThat(adapter.matches("different", hash)).isFalse();
  }

  @Test
  void identityProjectionHasOnlyPublicFieldsAndDefensiveRoles() {
    var roles = new HashSet<>(Set.of(Role.USER));
    var id = new UserIdentity(UUID.randomUUID(), "Name", "a@example.test", roles);
    roles.clear();
    assertThat(id.roles()).containsExactly(Role.USER);
    assertThat(Arrays.stream(UserIdentity.class.getRecordComponents()).map(c -> c.getName()))
        .containsExactly("id", "name", "email", "roles");
  }
}
