-- Schema only: no removal, purge, scheduler or legacy import is run here.
alter table ranking_movie_entry add column removed_at timestamptz;
alter table ranking_movie_entry add column removal_token uuid;
alter table ranking_movie_entry add column lifecycle_version bigint not null default 0
 constraint ranking_movie_entry_lifecycle_version_ck check(lifecycle_version >= 0);
alter table ranking_movie_entry add constraint ranking_movie_entry_removal_pair_ck
 check ((removed_at is null) = (removal_token is null));
create index ranking_movie_entry_removed_idx on ranking_movie_entry(removed_at,ranking_id,entry_id) where removed_at is not null;
alter table legacy_ranking_migration add column replay_closed_at timestamptz;
alter table legacy_ranking_movie_map add column live_entry_id uuid;
alter table legacy_ranking_movie_map add column live_round_id uuid;
alter table legacy_ranking_movie_map add column purged_at timestamptz;
update legacy_ranking_movie_map set live_entry_id=entry_id,live_round_id=round_id;
-- Resolve original target FKs by referenced relation and exact local column identity.
do $$
declare fk record; found_count integer := 0;
begin
 for fk in select c.conname from pg_constraint c
 where c.conrelid='legacy_ranking_movie_map'::regclass and c.contype='f'
 and ((c.confrelid='ranking_movie_entry'::regclass and c.conkey=array[
 (select attnum from pg_attribute where attrelid=c.conrelid and attname='ranking_id'),
 (select attnum from pg_attribute where attrelid=c.conrelid and attname='entry_id')]::smallint[])
 or (c.confrelid='ranking_movie_round'::regclass and c.conkey=array[
 (select attnum from pg_attribute where attrelid=c.conrelid and attname='ranking_id'),
 (select attnum from pg_attribute where attrelid=c.conrelid and attname='round_id')]::smallint[]))
 loop
 execute format('alter table legacy_ranking_movie_map drop constraint %I',fk.conname);
 found_count := found_count+1;
 end loop;
 if found_count <> 2 then raise exception 'Expected exactly two historical target foreign keys'; end if;
end;
$$;
alter table legacy_ranking_movie_map add constraint legacy_ranking_movie_map_live_entry_fk
 foreign key(ranking_id,live_entry_id) references ranking_movie_entry(ranking_id,entry_id);
alter table legacy_ranking_movie_map add constraint legacy_ranking_movie_map_live_round_fk
 foreign key(ranking_id,live_round_id) references ranking_movie_round(ranking_id,round_id);
alter table legacy_ranking_movie_map add constraint legacy_ranking_movie_map_live_state_ck check (
 (purged_at is null and live_entry_id is not null and live_round_id is not null and live_entry_id=entry_id and live_round_id=round_id)
 or (purged_at is not null and live_entry_id is null and live_round_id is null));
create function protect_legacy_ranking_map_provenance() returns trigger language plpgsql as $$
begin
 if TG_OP='DELETE' then raise exception 'Migration provenance cannot be deleted' using errcode='23514'; end if;
 if NEW.migration_key is distinct from OLD.migration_key or NEW.ranking_id is distinct from OLD.ranking_id
 or NEW.legacy_movie_id is distinct from OLD.legacy_movie_id or NEW.entry_id is distinct from OLD.entry_id
 or NEW.round_id is distinct from OLD.round_id
 or (OLD.purged_at is not null and NEW.purged_at is distinct from OLD.purged_at) then
 raise exception 'Migration provenance is immutable' using errcode='23514';
 end if;
 return NEW;
end;
$$;
create trigger legacy_ranking_map_provenance_immutable before update or delete on legacy_ranking_movie_map
 for each row execute function protect_legacy_ranking_map_provenance();
create function protect_legacy_ranking_replay_closure() returns trigger language plpgsql as $$
begin
 if TG_OP='DELETE' then raise exception 'Migration ledger cannot be deleted' using errcode='23514'; end if;
 if (to_jsonb(NEW)-'replay_closed_at'-'target_fingerprint') is distinct from
    (to_jsonb(OLD)-'replay_closed_at'-'target_fingerprint')
 or (NEW.target_fingerprint is distinct from OLD.target_fingerprint
     and (OLD.target_fingerprint <> repeat('0',64) or OLD.replay_closed_at is not null))
 or (OLD.replay_closed_at is not null and NEW is distinct from OLD) then
 raise exception 'Migration ledger evidence and terminal closure are immutable' using errcode='23514';
 end if;
 return NEW;
end;
$$;
create trigger legacy_ranking_replay_closure_terminal before update or delete on legacy_ranking_migration
 for each row execute function protect_legacy_ranking_replay_closure();
create function require_closed_legacy_ranking_replay() returns trigger language plpgsql as $$
begin
 if exists(select 1 from legacy_ranking_movie_map m join legacy_ranking_migration l
 on l.migration_key=m.migration_key and l.ranking_id=m.ranking_id
 where m.migration_key=NEW.migration_key and m.legacy_movie_id=NEW.legacy_movie_id
 and m.purged_at is not null and l.replay_closed_at is null) then
 raise exception 'Purged provenance requires terminal replay ledger' using errcode='23514';
 end if;
 return null;
end;
$$;
create constraint trigger legacy_ranking_map_requires_closed_replay after insert or update on legacy_ranking_movie_map
 deferrable initially deferred for each row execute function require_closed_legacy_ranking_replay();
