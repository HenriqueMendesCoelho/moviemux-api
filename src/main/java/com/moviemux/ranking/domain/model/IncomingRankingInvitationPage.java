package com.moviemux.ranking.domain.model;

import java.util.List;
import java.util.UUID;

public record IncomingRankingInvitationPage(List<IncomingRankingInvitation> items, UUID nextCursor) {
  public IncomingRankingInvitationPage { items = List.copyOf(items); }
}
