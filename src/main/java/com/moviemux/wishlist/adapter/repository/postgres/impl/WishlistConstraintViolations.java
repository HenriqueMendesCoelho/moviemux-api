package com.moviemux.wishlist.adapter.repository.postgres.impl;

import org.hibernate.exception.ConstraintViolationException;

/** PostgreSQL uniqueness is recognized by structured SQL state and the exact V1 constraint. */
final class WishlistConstraintViolations {
  private WishlistConstraintViolations() {}

  static boolean isUniqueViolation(Throwable failure, String expectedConstraint) {
    for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
      if (cause instanceof ConstraintViolationException violation
          && "23505".equals(violation.getSQLState())
          && expectedConstraint.equals(violation.getConstraintName())) {
        return true;
      }
    }
    return false;
  }
}
