package com.moviemux.ranking.domain.usecase.impl;

import com.moviemux.ranking.domain.model.*;
import com.moviemux.ranking.domain.port.*;
import com.moviemux.ranking.domain.exception.*;
import com.moviemux.ranking.domain.usecase.CreateRankingUseCase;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

@Service
@Transactional(isolation=Isolation.READ_COMMITTED)
public class CreateRankingUseCaseImpl implements CreateRankingUseCase {
  private final CreateRanking rankings;
  private final CurrentRankingCreationQuota quotas;
  public CreateRankingUseCaseImpl(CreateRanking rankings, CurrentRankingCreationQuota quotas) {
    this.rankings=Objects.requireNonNull(rankings);
    this.quotas=Objects.requireNonNull(quotas);
  }
  @Override public RankingSummary create(RankingActor actor, String name, RankingVisibility visibility, RankingAdmission admission) {
    if(actor==null || !actor.accountEnabled()) throw new RankingAccessDeniedException();
    String normalized=normalize(name);
    if(visibility==null || admission==null) throw new InvalidRankingCreationException();
    var configured=quotas.find();
    if(configured==null || configured.isEmpty() || configured.get().maxOwned()<1)
      throw new RankingCreationUnavailableException();
    var result=rankings.create(actor.userId(),normalized,visibility,admission,configured.get());
    if(result==null || result.rankingId()==null || !normalized.equals(result.name())
        || result.visibility()!=visibility || result.admission()!=admission
        || result.capabilities()==null || !result.capabilities().equals(EnumSet.allOf(RankingCapability.class)))
      throw new RankingAccessDeniedException();
    return result;
  }
  private String normalize(String name) {
    if(name==null) throw new InvalidRankingCreationException();
    for(int i=0;i<name.length();i++) {
      char c=name.charAt(i);
      if(Character.isISOControl(c)) throw new InvalidRankingCreationException();
      if(Character.isHighSurrogate(c)) {
        if(i+1>=name.length() || !Character.isLowSurrogate(name.charAt(++i)))
          throw new InvalidRankingCreationException();
      } else if(Character.isLowSurrogate(c)) throw new InvalidRankingCreationException();
    }
    int start=0,end=name.length();
    while(start<end && whitespace(name.codePointAt(start))) start+=Character.charCount(name.codePointAt(start));
    while(end>start && whitespace(name.codePointBefore(end))) end-=Character.charCount(name.codePointBefore(end));
    String value=name.substring(start,end);
    int length=value.codePointCount(0,value.length());
    if(length<1 || length>120) throw new InvalidRankingCreationException();
    return value;
  }
  private boolean whitespace(int codepoint) {
    return Character.isWhitespace(codepoint) || Character.isSpaceChar(codepoint);
  }
}
