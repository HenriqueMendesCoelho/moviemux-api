package com.moviemux.rankingcontent.adapter.security;

import com.moviemux.rankingcontent.domain.exception.RatingCopyUnavailableException;
import com.moviemux.rankingcontent.domain.model.IssuedRatingCopyPreview;
import com.moviemux.rankingcontent.domain.model.RatingCopyContext;
import com.moviemux.rankingcontent.domain.model.RatingCopyPair;
import com.moviemux.rankingcontent.domain.model.RatingCopySourceVersion;
import com.moviemux.rankingcontent.domain.port.SealOwnRatingCopyPreview;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.Objects;
import java.util.UUID;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/** Issues confidential V1 previews only. No apply or replay decoding is exposed by this checkpoint. */
public final class AesGcmRatingCopyPreviewSealer implements SealOwnRatingCopyPreview {
  private static final byte VERSION = 1;
  private static final byte[] PURPOSE = "moviemux:own-ranking-rating-copy-preview".getBytes(StandardCharsets.US_ASCII);
  private static final int NONCE_BYTES = 12;
  private static final int TIME_BYTES = Long.BYTES + Integer.BYTES;
  private static final int PAYLOAD_BYTES = 16 * 8 + TIME_BYTES * 3 + Integer.BYTES + 1;
  private final SecretKeySpec key;
  private final int ttlSeconds;
  private final Clock clock;
  private final SecureRandom random = new SecureRandom();

  public AesGcmRatingCopyPreviewSealer(String configuredKey, String configuredTtl, Clock clock) {
    this.key=decodeKey(configuredKey);
    this.ttlSeconds=decodeTtl(configuredTtl);
    this.clock=Objects.requireNonNull(clock);
  }

  @Override public void requireConfigured() {
    if (key==null || ttlSeconds==0) throw new RatingCopyUnavailableException();
  }

  @Override public IssuedRatingCopyPreview seal(UUID actor, RatingCopyPair pair, RatingCopySourceVersion source) {
    requireConfigured();
    Instant expires=clock.instant().plusSeconds(ttlSeconds);
    var payload=ByteBuffer.allocate(PAYLOAD_BYTES);
    putId(payload,UUID.randomUUID());
    putId(payload,actor);
    putContext(payload,pair.source());
    putContext(payload,pair.destination());
    putTime(payload,expires);
    payload.putInt(source.score());
    putTime(payload,source.createdAt());
    boolean wasUpdated=source.updatedAt()!=null;
    payload.put((byte)(wasUpdated ? 1 : 0));
    putTime(payload,wasUpdated ? source.updatedAt() : Instant.EPOCH);
    // V1 tokens are defined only for destinations with no actor rating. No other preview shape is sealed.
    byte[] nonce=new byte[NONCE_BYTES];
    random.nextBytes(nonce);
    try {
      Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");
      cipher.init(Cipher.ENCRYPT_MODE,key,new GCMParameterSpec(128,nonce));
      cipher.updateAAD(PURPOSE);
      cipher.updateAAD(new byte[]{VERSION});
      byte[] encrypted=cipher.doFinal(payload.array());
      var envelope=ByteBuffer.allocate(1+nonce.length+encrypted.length).put(VERSION).put(nonce).put(encrypted);
      return new IssuedRatingCopyPreview(Base64.getUrlEncoder().withoutPadding().encodeToString(envelope.array()),expires);
    } catch (GeneralSecurityException unavailable) {
      throw new RatingCopyUnavailableException();
    } finally {
      Arrays.fill(payload.array(),(byte)0);
    }
  }

  private static void putContext(ByteBuffer target, RatingCopyContext context) {
    putId(target,context.rankingId());
    putId(target,context.entryId());
    putId(target,context.roundId());
  }

  private static void putId(ByteBuffer target, UUID id) {
    target.putLong(id.getMostSignificantBits()).putLong(id.getLeastSignificantBits());
  }

  private static void putTime(ByteBuffer target, Instant value) {
    target.putLong(value.getEpochSecond()).putInt(value.getNano());
  }

  private static SecretKeySpec decodeKey(String input) {
    if (input==null) return null;
    byte[] decoded;
    try { decoded=Base64.getDecoder().decode(input); }
    catch (IllegalArgumentException ignored) { return null; }
    try { return decoded.length==32 ? new SecretKeySpec(decoded,"AES") : null; }
    finally { Arrays.fill(decoded,(byte)0); }
  }

  private static int decodeTtl(String input) {
    if (input==null || !input.matches("[0-9]{1,4}")) return 0;
    int parsed=Integer.parseInt(input);
    return parsed>=1 && parsed<=3600 ? parsed : 0;
  }
}
