package com.moviemux.user.domain.repository;

public interface PasswordHasher {
  String encode(String value);

  boolean matches(String plain, String encoded);
}
