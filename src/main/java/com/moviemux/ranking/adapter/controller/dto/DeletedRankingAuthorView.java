package com.moviemux.ranking.adapter.controller.dto;

import com.moviemux.ranking.domain.model.RankingAuthorIdentity;
import java.util.Objects;
import java.util.UUID;

/** Allowlisted future response projection, not attached to any endpoint. No account metadata. */
public record DeletedRankingAuthorView(Kind kind, UUID code) {
  public enum Kind { DELETED }
  public DeletedRankingAuthorView {
    Objects.requireNonNull(kind, "kind");
    Objects.requireNonNull(code, "code");
  }
  public static DeletedRankingAuthorView from(RankingAuthorIdentity identity) {
    Objects.requireNonNull(identity, "identity");
    if (identity.accountId() != null)
      throw new IllegalStateException("Linked account cannot be projected as deleted");
    return new DeletedRankingAuthorView(Kind.DELETED, identity.deletedPublicCode());
  }
}
