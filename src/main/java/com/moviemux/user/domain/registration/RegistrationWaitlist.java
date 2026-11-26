package com.moviemux.user.domain.registration;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public interface RegistrationWaitlist {
  enum Status {
    PENDING, SENDING, GATEWAY_ACCEPTED, DELIVERY_FAILED, DELIVERY_UNKNOWN, DELIVERY_UNAVAILABLE, REVOKED
  }
  record Item(UUID requestId, String email, OffsetDateTime requestedAt, Status status) {}
  record Page(List<Item> items, UUID nextCursor) {}
  record Invitation(UUID requestId, Status status, String inviteCode) {}
  RegistrationMode mode();
  RegistrationMode adminMode(UUID actor);
  RegistrationMode changeMode(UUID actor, RegistrationMode mode);
  void request(String email);
  Page list(UUID actor, UUID after, int limit);
  Invitation invite(UUID actor, UUID requestId);
}
