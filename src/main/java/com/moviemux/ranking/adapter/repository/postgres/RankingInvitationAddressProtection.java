package com.moviemux.ranking.adapter.repository.postgres;

import com.moviemux.ranking.domain.exception.RankingInvitationAddressUnavailableException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import java.util.UUID;
import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Local secrets only. Neither clear address values nor configured keys are logged or persisted. */
@Component
class RankingInvitationAddressProtection {
  private final SecretKeySpec digestKey;
  private final SecretKeySpec encryptionKey;
  private final SecureRandom random = new SecureRandom();

  RankingInvitationAddressProtection(@Value("${ranking.invitation.address.digest-key:}") String digest,
      @Value("${ranking.invitation.address.encryption-key:}") String encryption) {
    byte[] hmac = decode(digest), aes = decode(encryption);
    boolean configured = hmac != null && aes != null && !MessageDigest.isEqual(hmac, aes);
    digestKey = configured ? new SecretKeySpec(hmac, "HmacSHA256") : null;
    encryptionKey = configured ? new SecretKeySpec(aes, "AES") : null;
    if (hmac != null) Arrays.fill(hmac, (byte) 0);
    if (aes != null) Arrays.fill(aes, (byte) 0);
  }

  void requireConfigured() {
    if (digestKey == null || encryptionKey == null) throw unavailable();
  }

  byte[] digest(String code) {
    requireConfigured();
    try {
      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(digestKey);
      return mac.doFinal(code.getBytes(StandardCharsets.US_ASCII));
    } catch (GeneralSecurityException failure) {
      throw unavailable();
    }
  }

  byte[] encrypt(UUID account, String code) {
    byte[] nonce = new byte[12];
    random.nextBytes(nonce);
    byte[] ciphertext = crypt(Cipher.ENCRYPT_MODE, account, nonce, code.getBytes(StandardCharsets.US_ASCII));
    return ByteBuffer.allocate(nonce.length + ciphertext.length).put(nonce).put(ciphertext).array();
  }

  String decrypt(UUID account, byte[] envelope) {
    if (envelope == null || envelope.length != 50) throw unavailable();
    return new String(crypt(Cipher.DECRYPT_MODE, account, Arrays.copyOf(envelope, 12),
        Arrays.copyOfRange(envelope, 12, envelope.length)), StandardCharsets.US_ASCII);
  }

  private byte[] crypt(int mode, UUID account, byte[] nonce, byte[] value) {
    requireConfigured();
    try {
      Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
      cipher.init(mode, encryptionKey, new GCMParameterSpec(128, nonce));
      cipher.updateAAD(ByteBuffer.allocate(16).putLong(account.getMostSignificantBits())
          .putLong(account.getLeastSignificantBits()).array());
      return cipher.doFinal(value);
    } catch (GeneralSecurityException failure) {
      throw unavailable();
    }
  }

  private static byte[] decode(String input) {
    try {
      byte[] value = Base64.getDecoder().decode(input);
      return value.length == 32 ? value : null;
    } catch (IllegalArgumentException failure) {
      return null;
    }
  }

  private static RankingInvitationAddressUnavailableException unavailable() {
    return new RankingInvitationAddressUnavailableException();
  }
}
