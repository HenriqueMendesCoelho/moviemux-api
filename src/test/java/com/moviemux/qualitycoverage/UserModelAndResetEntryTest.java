package com.moviemux.qualitycoverage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

import com.moviemux.user.application.spring.AsyncPasswordReset;
import com.moviemux.user.domain.model.Invite;
import com.moviemux.user.domain.model.Role;
import com.moviemux.user.domain.model.Statistics;
import com.moviemux.user.domain.model.User;
import com.moviemux.user.domain.usecase.RequestPasswordResetUseCase;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class UserModelAndResetEntryTest {
  @Test
  void restoredInvitationKeepsStoredIdentityAndCode() {
    Invite invitation = new Invite(42L, "KB-stored-synthetic");
    assertThat(invitation.getId()).isEqualTo(42L);
    assertThat(invitation.getCode()).isEqualTo("KB-stored-synthetic");
    assertThat(invitation).isEqualTo(new Invite(42L, "KB-stored-synthetic"));
  }

  @Test
  void statisticsCanRepresentUnassociatedDefaultsAndAssociatedIdentity() {
    Statistics empty = Statistics.builder().ratingsGiven(3).registeredMovies(2).build();
    assertThat(empty.getId()).isNull();
    assertThat(empty.getRatingsGiven()).isEqualTo(3);
    assertThat(empty.getRegisteredMovies()).isEqualTo(2);
    assertThat(empty.getConsecutiveFailedLoginAttempts()).isZero();
    assertThat(empty.getDisplayTime()).isZero();
    assertThat(empty.getAverageRatingMovies()).isZero();
    User owner = User.builder().id(UUID.fromString("00000000-0000-0000-0000-000000000042")).build();
    Statistics associated = Statistics.builder().user(owner).build();
    assertThat(associated.getId()).isEqualTo(owner.getId());
    assertThat(associated.getRatingsGiven()).isZero();
    assertThat(associated.getRegisteredMovies()).isZero();
  }

  @Test
  void restoredRolesAreDefensiveAndDoNotReintroduceDefaultUserRole() {
    var persisted = new HashSet<>(Set.of(Role.ADM));
    User user = new User();
    assertThat(user.getRoles()).containsExactly(Role.USER);
    user.setRoles(persisted);
    persisted.add(Role.USER);
    assertThat(user.getRoles()).containsExactly(Role.ADM);
    assertThatThrownBy(() -> user.getRoles().clear())
        .isInstanceOf(UnsupportedOperationException.class);
    user.addRole(Role.USER);
    assertThat(user.getRoles()).containsExactlyInAnyOrder(Role.ADM, Role.USER);
    user.removeRole(Role.ADM);
    assertThat(user.getRoles()).containsExactly(Role.USER);
  }

  @Test
  void roleCodesPreserveKnownMappingsAndRejectUnknownValues() {
    assertThat(Role.toEnum(1)).isEqualTo(Role.ADM);
    assertThat(Role.toEnum(2)).isEqualTo(Role.USER);
    assertThat(Role.toEnum(null)).isNull();
    assertThat(Role.ADM.getDescription()).isEqualTo("ROLE_ADM");
    assertThat(Role.USER.getDescription()).isEqualTo("ROLE_USER");
    assertThatThrownBy(() -> Role.toEnum(99))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("User type invalid 99");
  }

  @Test
  void asynchronousEntryDelegatesUnchangedEmailToTheSingleResetOperation() {
    RequestPasswordResetUseCase operation = mock(RequestPasswordResetUseCase.class);
    new AsyncPasswordReset(operation).request("reset@example.test");
    verify(operation).execute("reset@example.test");
    verifyNoMoreInteractions(operation);
  }

  @Test
  void resetEntryDoesNotRetryWhenItsOperationFails() {
    RequestPasswordResetUseCase operation = mock(RequestPasswordResetUseCase.class);
    doThrow(new IllegalStateException("synthetic failure"))
        .when(operation)
        .execute("reset@example.test");
    assertThatThrownBy(() -> new AsyncPasswordReset(operation).request("reset@example.test"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("synthetic failure");
    verify(operation).execute("reset@example.test");
    verifyNoMoreInteractions(operation);
  }
}
