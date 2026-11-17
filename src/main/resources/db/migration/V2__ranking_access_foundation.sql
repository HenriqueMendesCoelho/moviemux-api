-- Additive authorization storage only. No legacy backfill or catalog/rating mutation.
-- Ownership has one source; its matching active membership is checked at commit.
create table ranking (
    id uuid primary key,
    owner_user_id uuid not null references "user" (id),
    visibility varchar(7) not null check (visibility in ('PUBLIC', 'PRIVATE')),
    admission varchar(10) not null check (admission in ('OPEN', 'APPROVAL', 'INVITATION')),
    owner_membership_active boolean not null default true check (owner_membership_active),
    created_at timestamp with time zone not null
);
create table ranking_membership (
    ranking_id uuid not null references ranking (id),
    user_id uuid not null references "user" (id),
    active boolean not null,
    created_at timestamp with time zone not null,
    primary key (ranking_id, user_id),
    unique (ranking_id, user_id, active)
);
alter table ranking add constraint ranking_active_owner_membership_fk
    foreign key (id, owner_user_id, owner_membership_active)
    references ranking_membership (ranking_id, user_id, active)
    deferrable initially deferred;
create table ranking_membership_grant (
    ranking_id uuid not null,
    user_id uuid not null,
    capability varchar(32) not null check (capability in
        ('READ', 'RATE_OWN', 'ADD_ENTRY', 'REMOVE_ENTRY', 'INVITE_MEMBER', 'MANAGE_MEMBERS')),
    primary key (ranking_id, user_id, capability),
    foreign key (ranking_id, user_id) references ranking_membership (ranking_id, user_id)
);
create index ranking_owner_idx on ranking (owner_user_id);
create index ranking_membership_user_active_idx on ranking_membership (user_id, active, ranking_id);
create index ranking_membership_ranking_active_idx on ranking_membership (ranking_id, active);
