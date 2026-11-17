package com.moviemux.rankingaccess;

import static org.assertj.core.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.exc.InvalidDefinitionException;
import com.moviemux.ranking.adapter.controller.dto.DeletedRankingAuthorView;
import com.moviemux.ranking.domain.model.RankingAuthorIdentity;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class RankingAuthorProjectionTest {
  private final ObjectMapper json = new ObjectMapper();

  @Test void deletedProjectionHasOnlyKindAndOpaqueCode() throws Exception {
    UUID rank = UUID.randomUUID(), author = UUID.randomUUID(), code = UUID.randomUUID();
    var internal = new RankingAuthorIdentity(rank, author, null, code);
    var tree = json.readTree(json.writeValueAsString(DeletedRankingAuthorView.from(internal)));
    assertThat(tree.size()).isEqualTo(2);
    assertThat(tree.get("kind").asText()).isEqualTo("DELETED");
    assertThat(tree.get("code").asText()).isEqualTo(code.toString());
    assertThat(tree.toString()).doesNotContain(rank.toString(), author.toString(), "account", "email", "name");
  }

  @Test void preparedPublicCodeDoesNotMeanAccountWasDeleted() {
    var linked = new RankingAuthorIdentity(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
    assertThatThrownBy(() -> DeletedRankingAuthorView.from(linked)).isInstanceOf(IllegalStateException.class);
  }

  @Test void internalObjectIsNotAnImplicitJsonPayloadOrSensitiveLogString() {
    UUID account = UUID.randomUUID();
    var internal = new RankingAuthorIdentity(UUID.randomUUID(), UUID.randomUUID(), account, UUID.randomUUID());
    assertThatThrownBy(() -> json.writeValueAsString(internal)).isInstanceOf(InvalidDefinitionException.class);
    assertThat(internal.toString()).doesNotContain(account.toString(), internal.rankingId().toString(),
        internal.authorId().toString(), internal.deletedPublicCode().toString());
  }

  @Test void identifierCollisionsAreRejectedBeforeProjection() {
    UUID rank = UUID.randomUUID(), account = UUID.randomUUID(), other = UUID.randomUUID();
    assertThatThrownBy(() -> new RankingAuthorIdentity(rank, account, account, other)).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new RankingAuthorIdentity(rank, other, account, account)).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new RankingAuthorIdentity(rank, other, account, other)).isInstanceOf(IllegalArgumentException.class);
  }
}
