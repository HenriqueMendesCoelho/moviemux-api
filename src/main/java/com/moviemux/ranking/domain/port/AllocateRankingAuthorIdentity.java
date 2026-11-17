package com.moviemux.ranking.domain.port;

import com.moviemux.ranking.domain.repository.RankingAuthorIdentities;

/** Internal port retaining the public allocation contract without exposing persistence details. */
@FunctionalInterface
public interface AllocateRankingAuthorIdentity extends RankingAuthorIdentities {}
