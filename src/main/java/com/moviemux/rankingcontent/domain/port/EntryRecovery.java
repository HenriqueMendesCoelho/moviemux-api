package com.moviemux.rankingcontent.domain.port;
import com.moviemux.rankingcontent.domain.model.EntryRecoveryState;
import java.util.UUID;
/** Recheck current actor, authoritative entry creator and removal grants without requiring READ.
 * Mutations serialize on ranking/entry, reject archives, enforce version/token and exact 24h expiry.
 * Restore clears removal fields and increments lifecycle_version. Replays conflict, never extend TTL.
 */
public interface EntryRecovery {
  EntryRecoveryState preview(UUID rankingId,UUID entryId,UUID actorUserId);
  EntryRecoveryState remove(UUID rankingId,UUID entryId,UUID actorUserId,long expectedVersion);
  EntryRecoveryState restore(UUID rankingId,UUID entryId,UUID actorUserId,UUID removalToken);
}
