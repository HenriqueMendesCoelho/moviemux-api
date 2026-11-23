package com.moviemux.legacyboard.adapter.repository.postgres;

import com.moviemux.legacyboard.domain.exception.LegacyBoardRetiredException;
import com.moviemux.legacyboard.domain.exception.LegacyBoardUnavailableException;
import com.moviemux.legacyboard.domain.repository.LegacyBoardInterlock;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Repository
public class LegacyBoardInterlockPostgresAdapter implements LegacyBoardInterlock {
  private final JdbcTemplate jdbc;

  public LegacyBoardInterlockPostgresAdapter(JdbcTemplate jdbc) { this.jdbc = jdbc; }

  @Override
  public void requireLegacy() {
    // Outside an actual transaction any lock acquired by the statement would release immediately.
    if (!TransactionSynchronizationManager.isActualTransactionActive()
        || TransactionSynchronizationManager.isCurrentTransactionReadOnly())
      throw new LegacyBoardUnavailableException();
    String phase;
    try {
      phase = jdbc.queryForObject("select lock_legacy_board_phase()", String.class);
    } catch (DataAccessException unavailable) {
      throw new LegacyBoardUnavailableException();
    }
    if ("LEGACY".equals(phase)) return;
    if ("FENCED".equals(phase) || "ACTIVE".equals(phase)) throw new LegacyBoardRetiredException();
    throw new LegacyBoardUnavailableException();
  }
}
