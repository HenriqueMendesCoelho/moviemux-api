package com.moviemux.ranking;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.moviemux.ranking.domain.exception.RankingAccessDeniedException;
import com.moviemux.ranking.domain.exception.RankingInvitationAddressUnavailableException;
import com.moviemux.ranking.domain.model.RankingActor;
import com.moviemux.ranking.domain.model.RankingInvitationAddress;
import com.moviemux.ranking.domain.port.CurrentRankingInvitationAddress;
import com.moviemux.ranking.domain.usecase.impl.ManageRankingInvitationAddressUseCaseImpl;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ManageRankingInvitationAddressUseCaseTest {
  private static final UUID ACCOUNT = new UUID(0, 1);
  private static final RankingActor ACTOR = new RankingActor(ACCOUNT, true);
  private static final String CODE = "Aa-_" + "0".repeat(17) + "Q";
  private final CurrentRankingInvitationAddress addresses = mock(CurrentRankingInvitationAddress.class);
  private final ManageRankingInvitationAddressUseCaseImpl useCase = new ManageRankingInvitationAddressUseCaseImpl(addresses);

  @Test
  void getDoesNotGenerateOrRotateAbsentOrExistingAddress() {
    when(addresses.find(ACCOUNT)).thenReturn(Optional.empty(), Optional.of(CODE));
    assertThat(useCase.get(ACTOR)).isEmpty();
    assertThat(useCase.get(ACTOR)).contains(CODE);
    verify(addresses, times(2)).find(ACCOUNT);
    verifyNoMoreInteractions(addresses);
  }

  @Test
  void rotateDelegatesOnceWithoutReadingOrNormalizing() {
    when(addresses.rotate(ACCOUNT)).thenReturn(CODE);
    assertThat(useCase.rotate(ACTOR)).isEqualTo(CODE);
    verify(addresses).rotate(ACCOUNT);
    verifyNoMoreInteractions(addresses);
  }

  @Test
  void disabledAndMissingActorNeverConsultAddresses() {
    for (RankingActor actor : Arrays.asList(null, new RankingActor(ACCOUNT, false))) {
      assertThatThrownBy(() -> useCase.get(actor)).isInstanceOf(RankingAccessDeniedException.class);
      assertThatThrownBy(() -> useCase.rotate(actor)).isInstanceOf(RankingAccessDeniedException.class);
    }
    verifyNoInteractions(addresses);
  }

  @Test
  void invalidAdapterStateIsNotAnEmptyAddressAndIsRedacted() {
    String invalid = "private malformed data";
    when(addresses.find(ACCOUNT)).thenReturn(null, Optional.of(invalid));
    when(addresses.rotate(ACCOUNT)).thenReturn(null, invalid);
    for (int i = 0; i < 2; i++) {
      assertThatThrownBy(() -> useCase.get(ACTOR))
          .isInstanceOf(RankingInvitationAddressUnavailableException.class).hasMessage(null).hasNoCause();
      assertThatThrownBy(() -> useCase.rotate(ACTOR))
          .isInstanceOf(RankingInvitationAddressUnavailableException.class).hasMessage(null).hasNoCause();
    }
    verify(addresses, times(2)).find(ACCOUNT);
    verify(addresses, times(2)).rotate(ACCOUNT);
    verifyNoMoreInteractions(addresses);
  }

  @Test
  void allFinalByteValuesEncodeToExactlySixteenBytesAndCanonicalPaddingBits() {
    for (int i = 0; i < 256; i++) {
      byte[] entropy = new byte[16];
      Arrays.fill(entropy, (byte) 0xff);
      entropy[15] = (byte) i;
      String code = RankingInvitationAddress.encode(entropy);
      assertThat(code).hasSize(22);
      assertThat(RankingInvitationAddress.isCanonical(code)).isTrue();
      assertThat(Base64.getUrlDecoder().decode(code)).containsExactly(entropy);
      assertThat(code.charAt(21)).isEqualTo("AQgw".charAt(i & 3));
    }
  }

  @Test
  void rejectsPaddingAliasesWrongAlphabetAndWhitespaceWithoutNormalizing() {
    for (String invalid : Arrays.asList(null, "", CODE + "=", CODE.substring(1), " " + CODE,
        CODE + "\n", "+" + CODE.substring(1), "/" + CODE.substring(1), "é" + CODE.substring(1),
        "!" + CODE.substring(1), CODE.substring(0, 21) + "R", "A".repeat(21) + "B",
        "A".repeat(21) + "-", "A".repeat(21) + "_")) {
      assertThat(RankingInvitationAddress.isCanonical(invalid)).isFalse();
    }
    assertThat(RankingInvitationAddress.isCanonical("Aa" + CODE.substring(2))).isTrue();
    assertThat(RankingInvitationAddress.isCanonical("aA" + CODE.substring(2))).isTrue();
    assertThatThrownBy(() -> RankingInvitationAddress.encode(null)).isInstanceOf(IllegalArgumentException.class);
    for (int size : List.of(0, 15, 17)) {
      assertThatThrownBy(() -> RankingInvitationAddress.encode(new byte[size]))
          .isInstanceOf(IllegalArgumentException.class);
    }
  }
}
