-- Existing account-bound keys and foreign keys remain unchanged.
alter table ranking_membership add column membership_id uuid;
alter table ranking_membership alter column membership_id set default gen_random_uuid();

update ranking_membership set membership_id = gen_random_uuid()
    where membership_id is null;

alter table ranking_membership
    alter column membership_id set not null,
    add constraint ranking_membership_membership_id_uk unique (membership_id);

create index ranking_membership_active_membership_idx
    on ranking_membership (ranking_id, membership_id)
    where active;
