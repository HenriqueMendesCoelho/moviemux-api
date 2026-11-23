-- Keep existing movies and older INSERTs eligible. Contextual-only rows require an explicit false.
alter table movie add column legacy_eligible boolean not null default true;

create function reject_legacy_movie_eligibility_change() returns trigger
language plpgsql set search_path = pg_catalog as $$
begin
    raise exception 'Movie eligibility cannot be changed' using errcode = '23514';
end;
$$;

create trigger movie_legacy_eligibility_immutable
before update of legacy_eligible on movie
for each row when (old.legacy_eligible is distinct from new.legacy_eligible)
execute function reject_legacy_movie_eligibility_change();

create function require_legacy_eligible_movie() returns trigger
language plpgsql set search_path = pg_catalog as $$
declare
    eligible boolean;
begin
    -- Let the existing NOT NULL constraint handle null, and keep the physical FKs in force.
    if new.movie_id is null then
        return new;
    end if;
    execute format('select exists (select 1 from %I.movie where id = $1 and legacy_eligible)', tg_table_schema)
        into eligible using new.movie_id;
    -- An invisible or missing parent must also fail closed, even if it appears before the FK check.
    if not eligible then
        raise exception 'Movie reference is unavailable' using errcode = '23503';
    end if;
    return new;
end;
$$;

create trigger movie_note_requires_legacy_movie
before insert or update of movie_id on movie_note
for each row execute function require_legacy_eligible_movie();

create trigger movie_discord_requires_legacy_movie
before insert or update of movie_id on movie_discord
for each row execute function require_legacy_eligible_movie();
