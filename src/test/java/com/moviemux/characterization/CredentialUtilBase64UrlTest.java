package com.moviemux.characterization;

import static org.assertj.core.api.Assertions.*;
import com.moviemux.adapter.util.CredentialUtil;
import com.moviemux.adapter.util.exception.TokenInvalidException;
import com.moviemux.application.spring.security.util.JWTUtil;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class CredentialUtilBase64UrlTest {
  static final UUID ID = UUID.fromString("10000000-0000-0000-0000-000000000001");
  static final String EMAIL = "synthetic@example.invalid";
  static JWTUtil issuer() {
    var jwt = new JWTUtil(); jwt.secret="synthetic-base64url-test-secret-not-for-production-0123456789";
    jwt.expiration=60000; return jwt;
  }
  static String unicodeToken(JWTUtil jwt, char requiredCharacter) {
    // Three consecutive ASCII '>' and '?' guarantee both Base64URL-only characters,
    // independently of JSON field offsets or whether the issuer escapes Unicode.
    String token=jwt.generateToken(ID,EMAIL,"João 😀 >>>???",Set.of("ROLE_USER"));
    if (token.split("\\.")[1].indexOf(requiredCharacter)<0)
      throw new AssertionError("Synthetic issuer fixture must exercise Base64URL alphabet");
    return token;
  }
  @Test void actualIssuerUnicodeTokensDecodeBothUrlAlphabetCharacters() throws Exception {
    var jwt=issuer();
    for (char marker : new char[]{'-','_'}) {
      String token=unicodeToken(jwt,marker),payload=token.split("\\.")[1];
      assertThatThrownBy(()->Base64.getDecoder().decode(payload)).isInstanceOf(IllegalArgumentException.class);
      assertThat(jwt.getVerifiedIdentity(token).userId()).isEqualTo(ID);
      var claims=CredentialUtil.getUserFromToken("Bearer "+token);
      var raw=new com.fasterxml.jackson.databind.ObjectMapper().readTree(Base64.getUrlDecoder().decode(payload));
      assertThat(claims.getName()).isEqualTo(raw.required("name").asText());
      assertThat(claims.getId()).isEqualTo(ID);assertThat(claims.getLogin()).isEqualTo(EMAIL);
      assertThat(claims.getRoles()).containsExactly("USER");
    }
  }
  @Test void asciiIssuerClaimsRemainCompatible() {
    var token=issuer().generateToken(ID,EMAIL,"Synthetic ASCII",Set.of("ROLE_USER"));
    var claims=CredentialUtil.getUserFromToken("Bearer "+token);
    assertThat(claims.getName()).isEqualTo("Synthetic ASCII");assertThat(claims.getId()).isEqualTo(ID);
    assertThat(claims.getAudience()).containsExactly("https://www.moviemux.com/api");
  }
  @Test void malformedInputAlwaysHasGenericErrorWithoutSensitiveCause() {
    String privateJson="{\"name\":\"SYNTHETIC_PRIVATE_MARKER\",broken}";
    String payload=Base64.getUrlEncoder().withoutPadding().encodeToString(privateJson.getBytes(StandardCharsets.UTF_8));
    for(String input:new String[]{null,"","Bearer","Basic abc","Bearer a","Bearer a.b","Bearer a.b.c.d",
        "Bearer .e30.sig","Bearer head..sig","Bearer head.e30.","Bearer head.%%%.sig",
        "Bearer head."+payload+".sig","Bearer head.bnVsbA.sig","Bearer head.e30.sig"}) {
      assertThatThrownBy(()->CredentialUtil.getUserFromToken(input)).isInstanceOf(TokenInvalidException.class)
          .hasMessage("token invalid").hasNoCause();
    }
  }
}
