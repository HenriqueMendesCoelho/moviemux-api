-- Add empty invitation storage only. Do not change membership or legacy data.
create table ranking_invitation (
    invite_id uuid primary key,
    ranking_id uuid not null references ranking (id),
    invited_by_user_id uuid not null references "user" (id),
    invitee_user_id uuid not null references "user" (id),
    issued_at timestamptz not null,
    expires_at timestamptz not null,
    consumed_at timestamptz,
    revoked_at timestamptz,
    constraint ranking_invitation_expiry_ck
        check (isfinite(issued_at) and isfinite(expires_at)
            and expires_at = issued_at + interval '168 hours'),
    constraint ranking_invitation_terminal_state_ck
        check (consumed_at is null or revoked_at is null)
);

-- An expired but unclosed row still owns this slot; issuing a replacement must close it first.
create unique index ranking_invitation_pending_unique_idx
    on ranking_invitation (ranking_id, invitee_user_id)
    where consumed_at is null and revoked_at is null;
create index ranking_invitation_pending_inbox_idx
    on ranking_invitation (invitee_user_id, invite_id)
    where consumed_at is null and revoked_at is null;
create index ranking_invitation_issuer_idx
    on ranking_invitation (invited_by_user_id);
