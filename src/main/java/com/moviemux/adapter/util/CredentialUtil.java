package com.moviemux.adapter.util;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.moviemux.adapter.core.controller.dto.UserTokenDto;
import com.moviemux.adapter.util.exception.TokenInvalidException;

/** Legacy claim projection only. Signature, current identity and authority remain filter concerns. */
public class CredentialUtil {

  public static UserTokenDto getUserFromToken(String token) throws TokenInvalidException {
    try {
      if (token == null || !token.startsWith("Bearer ")) throw new TokenInvalidException();
      String[] parts = token.substring(7).split("\\.", -1);
      if (parts.length != 3 || parts[0].isEmpty() || parts[1].isEmpty() || parts[2].isEmpty())
        throw new TokenInvalidException();
      String payload = new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8);
      UserTokenDto user = new ObjectMapper().readValue(payload, UserTokenDto.class);
      if (user == null || user.getLogin() == null || user.getAudience() == null || user.getExpiration() == null
          || user.getName() == null || user.getId() == null) throw new TokenInvalidException();
      return user;
    } catch (Exception invalid) {
      // Parser messages may contain decoded claims. Never log or return them or their cause.
      throw new TokenInvalidException();
    }
  }
}
