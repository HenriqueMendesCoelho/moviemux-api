-- Empty additive foundation only: no legacy assignment, unlinking or account deletion.
create table ranking_author_identity (
    ranking_id uuid not null references ranking (id),
    author_id uuid not null unique,
    account_id uuid references "user" (id),
    deleted_public_code uuid not null unique,
    primary key (ranking_id, author_id),
    unique (ranking_id, account_id),
    foreign key (ranking_id, account_id) references ranking_membership (ranking_id, user_id),
    check (author_id <> deleted_public_code),
    check (account_id is null or (account_id <> author_id and account_id <> deleted_public_code))
);

-- Identity and its contextual public code never change. Future unlinking needs a separate workflow.
create function protect_ranking_author_identity() returns trigger language plpgsql as $$
begin
    if NEW.ranking_id is distinct from OLD.ranking_id
       or NEW.author_id is distinct from OLD.author_id
       or NEW.deleted_public_code is distinct from OLD.deleted_public_code then
        raise exception 'Ranking author identity is immutable' using errcode = '23514';
    end if;
    return NEW;
end;
$$;
create trigger ranking_author_identity_is_immutable
before update on ranking_author_identity
for each row execute function protect_ranking_author_identity();
