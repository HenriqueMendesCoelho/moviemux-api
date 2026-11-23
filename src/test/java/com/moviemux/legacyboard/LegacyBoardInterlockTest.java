package com.moviemux.legacyboard;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.moviemux.legacyboard.adapter.repository.postgres.LegacyBoardInterlockPostgresAdapter;
import com.moviemux.legacyboard.domain.exception.LegacyBoardRetiredException;
import com.moviemux.legacyboard.domain.exception.LegacyBoardUnavailableException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class LegacyBoardInterlockTest {
  private static final String QUERY = "select lock_legacy_board_phase()";
  private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
  private final LegacyBoardInterlockPostgresAdapter interlock = new LegacyBoardInterlockPostgresAdapter(jdbc);

  @AfterEach void cleanup() { TransactionSynchronizationManager.clear(); }

  @Test void onlyActualWriteCapableTransactionMayAcquireSharedPhaseLock() {
    assertThatThrownBy(interlock::requireLegacy).isInstanceOf(LegacyBoardUnavailableException.class);
    TransactionSynchronizationManager.setActualTransactionActive(true);
    TransactionSynchronizationManager.setCurrentTransactionReadOnly(true);
    assertThatThrownBy(interlock::requireLegacy).isInstanceOf(LegacyBoardUnavailableException.class);
    verifyNoInteractions(jdbc);
    TransactionSynchronizationManager.setCurrentTransactionReadOnly(false);
    when(jdbc.queryForObject(QUERY,String.class)).thenReturn("LEGACY");
    assertThatCode(interlock::requireLegacy).doesNotThrowAnyException();
    verify(jdbc).queryForObject(QUERY,String.class);verifyNoMoreInteractions(jdbc);
  }

  @Test void eachObservationUsesAuthoritativeCurrentPhaseAndNeverDefaultsToLegacy() {
    TransactionSynchronizationManager.setActualTransactionActive(true);
    when(jdbc.queryForObject(QUERY,String.class)).thenReturn("LEGACY","FENCED","ACTIVE",null,"legacy","UNEXPECTED","");
    assertThatCode(interlock::requireLegacy).doesNotThrowAnyException();
    for(int i=0;i<2;i++) assertThatThrownBy(interlock::requireLegacy).isInstanceOf(LegacyBoardRetiredException.class);
    for(int i=0;i<4;i++) assertThatThrownBy(interlock::requireLegacy).isInstanceOf(LegacyBoardUnavailableException.class);
    verify(jdbc,times(7)).queryForObject(QUERY,String.class);verifyNoMoreInteractions(jdbc);
  }

  @Test void lockContentionAndUnavailableStorageHaveNoCauseAndNeverRetry() {
    TransactionSynchronizationManager.setActualTransactionActive(true);
    when(jdbc.queryForObject(QUERY,String.class)).thenThrow(new CannotAcquireLockException("private lock"),
        new DataAccessResourceFailureException("private database"));
    for(int i=0;i<2;i++) assertThatThrownBy(interlock::requireLegacy)
        .isInstanceOf(LegacyBoardUnavailableException.class).hasMessage(null).hasNoCause();
    verify(jdbc,times(2)).queryForObject(QUERY,String.class);verifyNoMoreInteractions(jdbc);
  }
}
