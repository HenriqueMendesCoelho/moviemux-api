package com.moviemux.ranking.adapter.repository.postgres;

import static org.assertj.core.api.Assertions.*;
import com.moviemux.ranking.domain.exception.RankingInvitationAddressUnavailableException;
import java.util.Base64;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class RankingInvitationAddressProtectionTest {
  private static final UUID ACCOUNT = UUID.fromString("54929736-d1ce-4689-b2e8-22f79d65292e");
  private static final String CODE = "AAAAAAAAAAAAAAAAAAAAAA";
  private static final String MAC = key((byte) 1);
  private static final String AES = key((byte) 2);

  @Test void encryptionBindsTheEnvelopeToAccountAndRejectsTampering() {
    var protection = new RankingInvitationAddressProtection(MAC, AES);
    byte[] envelope = protection.encrypt(ACCOUNT, CODE);
    assertThat(envelope).hasSize(50);
    assertThat(protection.encrypt(ACCOUNT, CODE)).isNotEqualTo(envelope);
    assertThat(protection.decrypt(ACCOUNT, envelope)).isEqualTo(CODE);
    assertThatThrownBy(() -> protection.decrypt(UUID.randomUUID(), envelope))
        .isInstanceOf(RankingInvitationAddressUnavailableException.class).hasNoCause();
    envelope[13] ^= 1;
    assertThatThrownBy(() -> protection.decrypt(ACCOUNT, envelope))
        .isInstanceOf(RankingInvitationAddressUnavailableException.class).hasNoCause();
    assertThatThrownBy(() -> protection.decrypt(ACCOUNT, new byte[32]))
        .isInstanceOf(RankingInvitationAddressUnavailableException.class);
    assertThatThrownBy(() -> protection.decrypt(ACCOUNT, null))
        .isInstanceOf(RankingInvitationAddressUnavailableException.class);
  }

  @Test void digestIsExactAndBothIndependentKeysMustBeConfigured() {
    var one = new RankingInvitationAddressProtection(MAC, AES);
    var two = new RankingInvitationAddressProtection(MAC, AES);
    assertThat(one.digest(CODE)).hasSize(32).isEqualTo(two.digest(CODE));
    assertThat(one.digest(CODE.toLowerCase())).isNotEqualTo(one.digest(CODE));
    for (var bad : java.util.List.of(new RankingInvitationAddressProtection("", AES),
        new RankingInvitationAddressProtection(MAC, ""), new RankingInvitationAddressProtection(MAC, MAC),
        new RankingInvitationAddressProtection(MAC, "!invalid"))) {
      assertThatThrownBy(() -> bad.digest(CODE)).isInstanceOf(RankingInvitationAddressUnavailableException.class)
          .hasNoCause();
    }
  }

  private static String key(byte value) {
    byte[] bytes = new byte[32];
    java.util.Arrays.fill(bytes, value);
    return Base64.getEncoder().encodeToString(bytes);
  }
}
