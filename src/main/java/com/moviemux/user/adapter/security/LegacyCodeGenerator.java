package com.moviemux.user.adapter.security;

import com.moviemux.user.domain.repository.CodeGenerator;
import java.util.Random;
import org.springframework.stereotype.Component;

/** Preserves the existing generation policy; security changes require an explicit decision. */
@Component
public class LegacyCodeGenerator implements CodeGenerator {
  public String invitationCode() {
    return "KB-" + (new Random().nextInt(999999999 - 10000000) + 10000000);
  }

  public String passwordResetKey() {
    String alphabet = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ1234567890";
    Random random = new Random();
    StringBuilder result = new StringBuilder(200);
    for (int i = 0; i < 200; i++) result.append(alphabet.charAt(random.nextInt(alphabet.length())));
    return result.toString();
  }
}
