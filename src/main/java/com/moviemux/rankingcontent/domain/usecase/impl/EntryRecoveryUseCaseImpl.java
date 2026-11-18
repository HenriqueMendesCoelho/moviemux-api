package com.moviemux.rankingcontent.domain.usecase.impl;
import com.moviemux.ranking.domain.model.RankingActor;
import com.moviemux.rankingcontent.domain.exception.*;
import com.moviemux.rankingcontent.domain.model.EntryRecoveryState;
import com.moviemux.rankingcontent.domain.port.EntryRecovery;
import com.moviemux.rankingcontent.domain.usecase.EntryRecoveryUseCase;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
@Service
@Transactional(isolation=Isolation.READ_COMMITTED)
public class EntryRecoveryUseCaseImpl implements EntryRecoveryUseCase {
  private final EntryRecovery recovery;
  public EntryRecoveryUseCaseImpl(EntryRecovery recovery){this.recovery=Objects.requireNonNull(recovery);}
  @Override @Transactional(readOnly=true,isolation=Isolation.READ_COMMITTED)
  public EntryRecoveryState preview(RankingActor actor,UUID rankingId,UUID entryId){
    actor(actor,rankingId,entryId);return checked(recovery.preview(rankingId,entryId,actor.userId()),entryId);
  }
  @Override public EntryRecoveryState remove(RankingActor actor,UUID rankingId,UUID entryId,Long expectedVersion,boolean confirmed){
    actor(actor,rankingId,entryId);
    if(!confirmed || expectedVersion==null || expectedVersion<0 || expectedVersion==Long.MAX_VALUE)throw new InvalidEntryRecoveryException();
    var state=checked(recovery.remove(rankingId,entryId,actor.userId(),expectedVersion),entryId);
    if(state.removedAt()==null || state.version()!=expectedVersion+1)throw new RankingEntriesDeniedException();
    return state;
  }
  @Override public EntryRecoveryState restore(RankingActor actor,UUID rankingId,UUID entryId,UUID removalToken){
    actor(actor,rankingId,entryId);if(removalToken==null)throw new InvalidEntryRecoveryException();
    var state=checked(recovery.restore(rankingId,entryId,actor.userId(),removalToken),entryId);
    if(state.removedAt()!=null || state.version()==0)throw new RankingEntriesDeniedException();
    return state;
  }
  private void actor(RankingActor actor,UUID rankingId,UUID entryId){
    if(actor==null || !actor.accountEnabled() || rankingId==null || entryId==null)throw new RankingEntriesDeniedException();
  }
  private EntryRecoveryState checked(EntryRecoveryState state,UUID entryId){
    if(state==null || !entryId.equals(state.entryId()) || state.version()<0)throw new RankingEntriesDeniedException();
    if(state.removedAt()==null){
      if(state.expiresAt()!=null || state.removalToken()!=null)throw new RankingEntriesDeniedException();
    }else if(state.expiresAt()==null || state.removalToken()==null
        || !state.removedAt().plusHours(24).isEqual(state.expiresAt()))throw new RankingEntriesDeniedException();
    return state;
  }
}
