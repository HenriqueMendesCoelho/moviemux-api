package com.moviemux.rankingcontent.adapter.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.moviemux.rankingcontent.domain.exception.RatingCopyUnavailableException;
import com.moviemux.rankingcontent.domain.model.RatingCopyContext;
import com.moviemux.rankingcontent.domain.model.RatingCopyPair;
import com.moviemux.rankingcontent.domain.model.RatingCopySourceVersion;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.Base64;
import java.util.UUID;
import javax.crypto.AEADBadTagException;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;

class AesGcmRatingCopyPreviewSealerTest {
  private static final byte[] KEY = "A synthetic key for preview test".getBytes(StandardCharsets.US_ASCII);
  private static final String CONFIGURED_KEY = Base64.getEncoder().encodeToString(KEY);
  private static final String LABEL = "moviemux:own-ranking-rating-copy-preview";
  private static final Instant NOW = Instant.parse("2026-11-17T22:00:00.123456Z");
  private static final Instant SOURCE_CREATED = NOW.minusSeconds(60);
  private static final Instant SOURCE_UPDATED = NOW.minusSeconds(20);
  private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
  private static final UUID ACTOR = UUID.randomUUID();
  private static final RatingCopyPair PAIR = new RatingCopyPair(context(),context());

  @Test void tokenAuthenticatesPurposeActorExactPairSourceVersionAndExpiryWithoutPlaintext() throws Exception {
    var sealer = new AesGcmRatingCopyPreviewSealer(CONFIGURED_KEY,"600",CLOCK);
    var version = new RatingCopySourceVersion(0,SOURCE_CREATED,SOURCE_UPDATED);
    var first = sealer.seal(ACTOR,PAIR,version);
    var second = sealer.seal(ACTOR,PAIR,version);
    assertThat(second.token()).isNotEqualTo(first.token());
    assertThat(first.token()).matches("[A-Za-z0-9_-]{264}");
    assertThat(first.expiresAt()).isEqualTo(NOW.plusSeconds(600));
    assertThat(first.toString()).doesNotContain(first.token());
    assertThat(version.toString()).doesNotContain(SOURCE_CREATED.toString());

    var sealed = Base64.getUrlDecoder().decode(first.token());
    assertThat(sealed[0]).isEqualTo((byte)1);
    var plain = decrypt(sealed,LABEL);
    UUID operation=id(plain);
    assertThat(operation.version()).isEqualTo(4);
    assertThat(id(plain)).isEqualTo(ACTOR);
    for (var context : java.util.List.of(PAIR.source(),PAIR.destination())) {
      assertThat(id(plain)).isEqualTo(context.rankingId());
      assertThat(id(plain)).isEqualTo(context.entryId());
      assertThat(id(plain)).isEqualTo(context.roundId());
    }
    assertThat(instant(plain)).isEqualTo(first.expiresAt());
    assertThat(plain.getInt()).isZero();
    assertThat(instant(plain)).isEqualTo(SOURCE_CREATED);
    assertThat(plain.get()).isEqualTo((byte)1);
    assertThat(instant(plain)).isEqualTo(SOURCE_UPDATED);
    assertThat(plain.hasRemaining()).isFalse();
    assertThat(id(decrypt(Base64.getUrlDecoder().decode(second.token()),LABEL))).isNotEqualTo(operation);

    assertThatThrownBy(()->decrypt(sealed,"unrelated-purpose")).isInstanceOf(AEADBadTagException.class);
    sealed[sealed.length-1]^=1;
    assertThatThrownBy(()->decrypt(sealed,LABEL)).isInstanceOf(AEADBadTagException.class);
  }

  @Test void noImplicitKeyOrTtlAndBothTechnicalBoundsAreEnforced() throws Exception {
    var version=new RatingCopySourceVersion(10,SOURCE_CREATED,null);
    for (String badKey : Arrays.asList(null,"","not base64!",Base64.getEncoder().encodeToString(new byte[31]))) {
      var sealer = new AesGcmRatingCopyPreviewSealer(badKey,"600",CLOCK);
      assertThatThrownBy(sealer::requireConfigured).isInstanceOf(RatingCopyUnavailableException.class);
    }
    for (String badTtl : Arrays.asList(null,"","-1","0","3601"," 600","60.0","99999")) {
      var sealer = new AesGcmRatingCopyPreviewSealer(CONFIGURED_KEY,badTtl,CLOCK);
      assertThatThrownBy(()->sealer.seal(ACTOR,PAIR,version)).isInstanceOf(RatingCopyUnavailableException.class);
    }
    for (int ttl : new int[]{1,3600}) {
      var issued=new AesGcmRatingCopyPreviewSealer(CONFIGURED_KEY,Integer.toString(ttl),CLOCK).seal(ACTOR,PAIR,version);
      assertThat(issued.expiresAt()).isEqualTo(NOW.plusSeconds(ttl));
      var plain=decrypt(Base64.getUrlDecoder().decode(issued.token()),LABEL);
      plain.position(plain.limit() - 13);
      assertThat(plain.get()).isZero();
      assertThat(instant(plain)).isEqualTo(Instant.EPOCH);
    }
  }

  @Test void authenticatesExactExistingPreviewAndRejectsAlterationWithoutDetails() {
    var sealer=new AesGcmRatingCopyPreviewSealer(CONFIGURED_KEY,"600",CLOCK);
    for(var version:java.util.List.of(new RatingCopySourceVersion(0,SOURCE_CREATED,null),
        new RatingCopySourceVersion(10,SOURCE_CREATED,SOURCE_UPDATED))) {
      var token=sealer.seal(ACTOR,PAIR,version);
      var opened=sealer.open(token.token());
      assertThat(opened.actorId()).isEqualTo(ACTOR);
      assertThat(opened.pair()).isEqualTo(PAIR);
      assertThat(opened.source()).isEqualTo(version);
      assertThat(opened.expiresAt()).isEqualTo(token.expiresAt());
      assertThat(opened.toString()).isEqualTo("OpenedRatingCopyPreview[redacted]");
      var bytes=Base64.getUrlDecoder().decode(token.token());
      bytes[bytes.length-1]^=1;
      assertThatThrownBy(()->sealer.open(Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)))
          .isInstanceOf(com.moviemux.rankingcontent.domain.exception.InvalidRatingCopyException.class).hasNoCause();
      var disabled=new AesGcmRatingCopyPreviewSealer("","600",CLOCK);
      assertThatThrownBy(()->disabled.open(token.token())).isInstanceOf(RatingCopyUnavailableException.class);
    }
    for(String malformed:java.util.Arrays.asList(null,"","!".repeat(264),"A".repeat(264))) {
      assertThatThrownBy(()->sealer.open(malformed))
          .isInstanceOf(com.moviemux.rankingcontent.domain.exception.InvalidRatingCopyException.class).hasNoCause();
    }
  }

  private static ByteBuffer decrypt(byte[] envelope,String label) throws Exception {
    var cipher=Cipher.getInstance("AES/GCM/NoPadding");
    cipher.init(Cipher.DECRYPT_MODE,new SecretKeySpec(KEY,"AES"),new GCMParameterSpec(128,Arrays.copyOfRange(envelope,1,13)));
    cipher.updateAAD(label.getBytes(StandardCharsets.US_ASCII));
    cipher.updateAAD(new byte[]{envelope[0]});
    return ByteBuffer.wrap(cipher.doFinal(Arrays.copyOfRange(envelope,13,envelope.length)));
  }

  private static RatingCopyContext context() { return new RatingCopyContext(UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID()); }
  private static UUID id(ByteBuffer input) { return new UUID(input.getLong(),input.getLong()); }
  private static Instant instant(ByteBuffer input) { return Instant.ofEpochSecond(input.getLong(),input.getInt()); }
}
