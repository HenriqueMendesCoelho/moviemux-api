package com.moviemux.user.adapter.security;

import com.moviemux.user.domain.repository.PasswordHasher;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class BCryptPasswordHasher implements PasswordHasher {
  private final BCryptPasswordEncoder encoder;

  public String encode(String value) {
    return encoder.encode(value);
  }

  public boolean matches(String plain, String encoded) {
    return encoder.matches(plain, encoded);
  }
}
