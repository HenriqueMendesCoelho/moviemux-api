package com.moviemux.wishlist.adapter.repository.postgres.impl;

import org.hibernate.exception.ConstraintViolationException;

/** PostgreSQL uniqueness is recognized by structured SQL state and the exact known schema constraint names. */
final class WishlistConstraintViolations {
  private WishlistConstraintViolations() {}

  static boolean isUniqueViolation(Throwable failure, String expectedConstraint) {
    for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
      if (cause instanceof ConstraintViolationException violation
          && "23505".equals(violation.getSQLState())
          && matchesConstraint(expectedConstraint, violation.getConstraintName())) {
        return true;
      }
    }
    return false;
  }

  private static boolean matchesConstraint(String expected, String actual) {
    return expected.equals(actual)
        || ("user_wishlist_user_id_name_key".equals(expected)
            && "ukt52x8vytfwq3vic32in8tb8y8".equals(actual));
  }
}
