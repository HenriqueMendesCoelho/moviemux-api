package com.moviemux.user.domain.exception;

public final class AccountDeletionRequiresHistoryMigrationException extends RuntimeException {
  public AccountDeletionRequiresHistoryMigrationException() { super("Account deletion requires history migration"); }
}
