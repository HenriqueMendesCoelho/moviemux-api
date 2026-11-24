-- A nullable idempotency marker for new manual canonical films only; no historical backfill.
alter table movie
    add column manual_command_id uuid,
    add column manual_command_version smallint,
    add column manual_command_fingerprint varchar(64),
    add constraint movie_manual_command_id_uk unique (manual_command_id),
    add constraint movie_manual_command_metadata_ck check (
        (manual_command_id is null and manual_command_version is null and manual_command_fingerprint is null)
        or (not legacy_eligible and manual_command_id is not null
            and manual_command_version is not null and manual_command_version = 1
            and manual_command_fingerprint is not null and manual_command_fingerprint ~ '^[0-9a-f]{64}$')
    );

create function reject_manual_movie_command_change() returns trigger
language plpgsql set search_path = pg_catalog as $$
begin
    raise exception 'Movie command marker cannot be changed'
        using errcode = '23514', constraint = 'movie_manual_command_immutable';
end;
$$;

create trigger movie_manual_command_immutable
before update on movie
for each row when (
    old.manual_command_id is distinct from new.manual_command_id
    or old.manual_command_version is distinct from new.manual_command_version
    or old.manual_command_fingerprint is distinct from new.manual_command_fingerprint
)
execute function reject_manual_movie_command_change();
