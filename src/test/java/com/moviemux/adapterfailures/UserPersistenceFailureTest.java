package com.moviemux.adapterfailures;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

import com.moviemux.user.adapter.repository.UserRepository;
import com.moviemux.user.adapter.repository.postgres.UserPostgresRepository;
import com.moviemux.user.adapter.repository.postgres.entity.UserEntity;
import com.moviemux.user.adapter.repository.postgres.mapper.UserEntityMapper;
import java.sql.SQLException;
import java.util.Optional;
import java.util.UUID;
import org.hibernate.LazyInitializationException;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.dao.DataAccessResourceFailureException;

class UserPersistenceFailureTest {
  private static final UUID USER_ID = UUID.fromString("00000000-0000-0000-0000-000000000042");
  private static final String EMAIL = "synthetic@example.test";
  private final UserRepository persistence = mock(UserRepository.class);

  enum Lookup {
    EMAIL,
    ID;

    Object read(UserPostgresRepository adapter) {
      return this == EMAIL
          ? adapter.findByEmail(UserPersistenceFailureTest.EMAIL)
          : adapter.findById(USER_ID);
    }
  }

  @ParameterizedTest
  @EnumSource(Lookup.class)
  void databaseFailureRemainsTechnicalAndNeverBecomesSuccessfulAbsence(Lookup lookup) {
    // A repository seam tests propagation, not an actual PostgreSQL connection interruption.
    var failure =
        new DataAccessResourceFailureException(
            "synthetic database unavailable",
            new SQLException("synthetic connection refused", "08006"));
    if (lookup == Lookup.EMAIL) {
      when(persistence.findByEmail(EMAIL)).thenThrow(failure);
    } else {
      when(persistence.findById(USER_ID)).thenThrow(failure);
    }
    var mapper = mock(UserEntityMapper.class);
    var adapter = new UserPostgresRepository(persistence, mapper);

    assertThatThrownBy(() -> lookup.read(adapter)).isSameAs(failure);

    verifyNoInteractions(mapper);
    verifySingleRead(lookup);
  }

  @ParameterizedTest
  @EnumSource(Lookup.class)
  void failureWhileMaterializingEntityRemainsTechnicalRatherThanAbsent(Lookup lookup) {
    var entity = mock(UserEntity.class);
    var failure = new LazyInitializationException("synthetic uninitialized roles");
    when(entity.getRoles()).thenThrow(failure);
    if (lookup == Lookup.EMAIL) {
      when(persistence.findByEmail(EMAIL)).thenReturn(entity);
    } else {
      when(persistence.findById(USER_ID)).thenReturn(Optional.of(entity));
    }
    var adapter = new UserPostgresRepository(persistence, new UserEntityMapper());

    assertThatThrownBy(() -> lookup.read(adapter)).isSameAs(failure);

    verify(entity, times(1)).getRoles();
    verifySingleRead(lookup);
  }

  @ParameterizedTest
  @EnumSource(Lookup.class)
  void genuineAbsenceRetainsEachPortMethodsExistingContract(Lookup lookup) {
    if (lookup == Lookup.EMAIL) {
      when(persistence.findByEmail(EMAIL)).thenReturn(null);
    } else {
      when(persistence.findById(USER_ID)).thenReturn(Optional.empty());
    }
    var adapter = new UserPostgresRepository(persistence, new UserEntityMapper());

    if (lookup == Lookup.EMAIL) {
      assertThat(adapter.findByEmail(EMAIL)).isNull();
    } else {
      assertThat(adapter.findById(USER_ID)).isEmpty();
    }
    verifySingleRead(lookup);
  }

  private void verifySingleRead(Lookup lookup) {
    if (lookup == Lookup.EMAIL) {
      verify(persistence, times(1)).findByEmail(EMAIL);
    } else {
      verify(persistence, times(1)).findById(USER_ID);
    }
    verifyNoMoreInteractions(persistence);
  }
}
