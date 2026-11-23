package com.moviemux.rankingcontent;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.moviemux.ranking.domain.model.RankingActor;
import com.moviemux.rankingcontent.domain.exception.*;
import com.moviemux.rankingcontent.domain.model.RepeatedRoundReceipt;
import com.moviemux.rankingcontent.domain.port.RepeatedRoundOperations;
import com.moviemux.rankingcontent.domain.usecase.impl.ManageRepeatedRoundsUseCaseImpl;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ManageRepeatedRoundsUseCaseTest {
  private static final UUID USER=new UUID(0,1), RANK=new UUID(0,2), ENTRY=new UUID(0,3),
      COMMAND=new UUID(0,4), ROUND=new UUID(0,5);
  private static final RankingActor ACTOR=new RankingActor(USER,true);
  private final RepeatedRoundOperations rounds=mock(RepeatedRoundOperations.class);
  private final ManageRepeatedRoundsUseCaseImpl useCase=new ManageRepeatedRoundsUseCaseImpl(rounds);

  @Test void deniesMissingOrDisabledActorAndMissingResourceBeforeStorage(){
    for(var actor:Arrays.asList(null,new RankingActor(USER,false))){
      assertThatThrownBy(()->useCase.settings(actor,RANK)).isInstanceOf(RankingEntriesDeniedException.class);
      assertThatThrownBy(()->useCase.setSettings(actor,RANK,true)).isInstanceOf(RankingEntriesDeniedException.class);
      assertThatThrownBy(()->useCase.create(actor,RANK,ENTRY,COMMAND,null)).isInstanceOf(RankingEntriesDeniedException.class);
    }
    assertThatThrownBy(()->useCase.settings(ACTOR,null)).isInstanceOf(RankingEntriesDeniedException.class);
    assertThatThrownBy(()->useCase.setSettings(ACTOR,null,true)).isInstanceOf(RankingEntriesDeniedException.class);
    assertThatThrownBy(()->useCase.create(ACTOR,RANK,null,COMMAND,null)).isInstanceOf(RankingEntriesDeniedException.class);
    verifyNoInteractions(rounds);
  }

  @Test void settingsUseCurrentActorAndReturnAuthoritativeState(){
    when(rounds.settings(RANK,USER)).thenReturn(false);
    when(rounds.setSettings(RANK,USER,true)).thenReturn(true);
    when(rounds.setSettings(RANK,USER,false)).thenReturn(false);
    assertThat(useCase.settings(ACTOR,RANK)).isFalse();
    assertThat(useCase.setSettings(ACTOR,RANK,true)).isTrue();
    assertThat(useCase.setSettings(ACTOR,RANK,false)).isFalse();
    verify(rounds).settings(RANK,USER);
    verify(rounds).setSettings(RANK,USER,true);
    verify(rounds).setSettings(RANK,USER,false);
    verifyNoMoreInteractions(rounds);
  }

  @Test void canonicalizesOnlyOuterUnicodeWhitespaceAndUsesScalarCodePointLimit(){
    String prose="🎬".repeat(235)+"A\n  B";
    var receipt=new RepeatedRoundReceipt(ROUND,Long.MAX_VALUE,true);
    when(rounds.create(RANK,ENTRY,USER,COMMAND,prose)).thenReturn(receipt);
    assertThat(useCase.create(ACTOR,RANK,ENTRY,COMMAND,"\u00a0\u0085"+prose+"\u2003\t")).isSameAs(receipt);
    verify(rounds).create(RANK,ENTRY,USER,COMMAND,prose);
    verifyNoMoreInteractions(rounds);
  }

  @Test void blankOmittedAndUnicodeEmptyMapToTheSameCanonicalCommand(){
    var created=new RepeatedRoundReceipt(ROUND,2,true);
    var replay=new RepeatedRoundReceipt(ROUND,2,false);
    when(rounds.create(RANK,ENTRY,USER,COMMAND,null)).thenReturn(created,replay);
    assertThat(useCase.create(ACTOR,RANK,ENTRY,COMMAND,null)).isEqualTo(created);
    assertThat(useCase.create(ACTOR,RANK,ENTRY,COMMAND,"")).isEqualTo(replay);
    assertThat(useCase.create(ACTOR,RANK,ENTRY,COMMAND," \u00a0\u0085\u2003\n")).isEqualTo(replay);
    verify(rounds,times(3)).create(RANK,ENTRY,USER,COMMAND,null);
    verifyNoMoreInteractions(rounds);
  }

  @Test void invalidCommandIdOverlengthNulAndSurrogatesNeverReachStorage(){
    assertThatThrownBy(()->useCase.create(ACTOR,RANK,ENTRY,null,null)).isInstanceOf(InvalidRoundCommandException.class);
    for(String invalid:List.of("🎬".repeat(241),"text"+(char)0,"text"+(char)0xd800,(char)0xdc00+"text"))
      assertThatThrownBy(()->useCase.create(ACTOR,RANK,ENTRY,COMMAND,invalid)).isInstanceOf(InvalidRoundCommandException.class);
    verifyNoInteractions(rounds);
  }

  @Test void malformedReceiptAndCommandErrorsFailClosedWithoutRetry(){
    when(rounds.create(RANK,ENTRY,USER,COMMAND,null)).thenReturn(null,new RepeatedRoundReceipt(null,2,true),
        new RepeatedRoundReceipt(ROUND,0,false)).thenThrow(new RepeatRoundsDisabledException(),new RoundCommandConflictException());
    for(int i=0;i<3;i++)
      assertThatThrownBy(()->useCase.create(ACTOR,RANK,ENTRY,COMMAND,null)).isInstanceOf(RankingEntriesDeniedException.class);
    assertThatThrownBy(()->useCase.create(ACTOR,RANK,ENTRY,COMMAND,null)).isInstanceOf(RepeatRoundsDisabledException.class);
    assertThatThrownBy(()->useCase.create(ACTOR,RANK,ENTRY,COMMAND,null)).isInstanceOf(RoundCommandConflictException.class);
    verify(rounds,times(5)).create(RANK,ENTRY,USER,COMMAND,null);
    verifyNoMoreInteractions(rounds);
  }
}
