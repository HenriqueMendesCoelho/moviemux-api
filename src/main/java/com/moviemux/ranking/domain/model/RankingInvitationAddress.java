package com.moviemux.ranking.domain.model;

import java.util.Base64;
import java.util.regex.Pattern;

/**
 * Encoding for exactly 128 bits. This is an address, not an authentication credential.
 * It never normalizes input or includes an invalid value in an error. Entropy comes from
 * the caller's CSPRNG; neither checking nor encoding generates a new address.
 */
public final class RankingInvitationAddress {
  private static final int ENTROPY_BYTES = 16;
  // Only the high two bits of the 22nd character carry data; its low four bits must be zero.
  private static final Pattern CANONICAL = Pattern.compile("[A-Za-z0-9_-]{21}[AQgw]");

  private RankingInvitationAddress() {}

  public static boolean isCanonical(String code) {
    return code != null && code.length() == 22 && CANONICAL.matcher(code).matches();
  }

  public static String encode(byte[] entropy) {
    if (entropy == null || entropy.length != ENTROPY_BYTES) {
      throw new IllegalArgumentException("Invitation address requires exactly 128 bits");
    }
    return Base64.getUrlEncoder().withoutPadding().encodeToString(entropy);
  }
}
