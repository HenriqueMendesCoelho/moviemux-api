package com.moviemux.ranking.domain.model;

import java.util.List;
import java.util.UUID;

public record MembershipRosterPage(List<RosterMember> items, UUID nextCursor) {
  public MembershipRosterPage {
    items = List.copyOf(items);
  }
}
