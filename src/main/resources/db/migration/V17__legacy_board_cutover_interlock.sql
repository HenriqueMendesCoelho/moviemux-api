-- Default is intentionally fully compatible. No board data is copied by Flyway.
create table legacy_board_cutover (
    singleton boolean primary key check (singleton),
    phase varchar(8) not null check (phase in ('LEGACY', 'FENCED', 'ACTIVE')),
    owner_user_id uuid,
    ranking_id uuid,
    runtime_roles_digest varchar(64),
    fenced_at timestamptz,
    activated_at timestamptz,
    check ((phase = 'LEGACY' and owner_user_id is null and ranking_id is null
                and runtime_roles_digest is null and fenced_at is null and activated_at is null)
        or (phase in ('FENCED', 'ACTIVE') and owner_user_id is not null and ranking_id is not null
                and runtime_roles_digest is not null and runtime_roles_digest ~ '^[a-f0-9]{64}$'
                and fenced_at is not null
                and ((phase = 'FENCED' and activated_at is null)
                    or (phase = 'ACTIVE' and activated_at is not null and activated_at >= fenced_at))))
);

insert into legacy_board_cutover(singleton,phase) values (true,'LEGACY');
revoke all on legacy_board_cutover from public;

-- SQL-standard body binds the relation at definition, not through the caller's search path.
-- Return only phase. Runtime needs EXECUTE, not the table UPDATE grant normally needed for FOR SHARE.
create function lock_legacy_board_phase() returns text
language sql security definer set search_path = pg_catalog
begin atomic
    select phase::text from legacy_board_cutover where singleton for share nowait;
end;

-- Pure handoff query reads phase in the same MVCC snapshot as mapping and current authorization.
create function current_legacy_board_phase() returns text
language sql stable security definer set search_path = pg_catalog
begin atomic
    select phase::text from legacy_board_cutover where singleton;
end;

-- A legacy mapping also has to point at the reserved, reconciled destination, not merely any active scope.
create function is_active_legacy_board_destination(candidate uuid) returns boolean
language sql stable security definer set search_path = pg_catalog
begin atomic
    select exists (select 1 from legacy_board_cutover where singleton and phase='ACTIVE' and ranking_id=candidate);
end;

create function guard_legacy_board_phase_transition() returns trigger
language plpgsql set search_path = pg_catalog as $$
begin
    if (old.phase = 'LEGACY' and new.phase = 'FENCED')
       or (old.phase = 'FENCED' and new.phase = 'ACTIVE'
           and new.owner_user_id = old.owner_user_id and new.ranking_id = old.ranking_id
           and new.runtime_roles_digest = old.runtime_roles_digest and new.fenced_at = old.fenced_at) then
        return new;
    end if;
    raise exception 'Invalid legacy board phase transition' using errcode = '23514';
end;
$$;
create trigger legacy_board_cutover_monotonic
before update on legacy_board_cutover
for each row execute function guard_legacy_board_phase_transition();

create function assert_legacy_board_activation_ledger() returns trigger
language plpgsql set search_path = pg_catalog as $$
declare
    reconciled boolean;
begin
    execute format(
      'select exists (select 1 from %1$I.legacy_ranking_migration l join %1$I.ranking r on r.id=l.ranking_id
        where l.migration_key=''legacy-movies-v1'' and l.ranking_id=$1 and l.owner_user_id=$2
          and l.algorithm_version=1 and l.target_fingerprint<>repeat(''0'',64)
          and r.owner_user_id=$2 and r.visibility=''PRIVATE'' and r.admission=''INVITATION'' and r.archived_at is null)',
      tg_table_schema) into reconciled using new.ranking_id,new.owner_user_id;
    if not reconciled then
        raise exception 'Unreconciled legacy board activation' using errcode = '23514';
    end if;
    return null;
end;
$$;
create constraint trigger legacy_board_activation_ledger
after update on legacy_board_cutover deferrable initially deferred
for each row when (new.phase = 'ACTIVE') execute function assert_legacy_board_activation_ledger();

create function require_open_legacy_board_write() returns trigger
language plpgsql set search_path = pg_catalog as $$
declare
    board_phase text;
begin
    execute format('select %I.lock_legacy_board_phase()', tg_table_schema) into board_phase;
    if board_phase is distinct from 'LEGACY' then
        raise exception 'Legacy board write unavailable' using errcode = '55000';
    end if;
    return null;
end;
$$;
create trigger legacy_board_votes_fence
before insert or update or delete or truncate on movie_note
for each statement execute function require_open_legacy_board_write();
create trigger legacy_board_discord_fence
before insert or update or delete or truncate on movie_discord
for each statement execute function require_open_legacy_board_write();
create trigger legacy_board_movie_truncate_fence
before truncate on movie
for each statement execute function require_open_legacy_board_write();

create function guard_legacy_movie_board_row() returns trigger
language plpgsql set search_path = pg_catalog as $$
declare
    board_phase text;
begin
    -- Contextual canonical-only rows are not source rows. V12 separately makes eligibility immutable.
    if tg_op = 'INSERT' and not new.legacy_eligible then return new; end if;
    if tg_op = 'DELETE' and not old.legacy_eligible then return old; end if;
    if tg_op = 'UPDATE' and not old.legacy_eligible and not new.legacy_eligible then return new; end if;

    execute format('select %I.lock_legacy_board_phase()', tg_table_schema) into board_phase;
    if board_phase = 'LEGACY' then
        if tg_op = 'DELETE' then return old; end if;
        return new;
    end if;
    -- Only independent canonical metadata may change after ACTIVE; never board provenance or reveal.
    if board_phase = 'ACTIVE' and tg_op = 'UPDATE'
            and new.id = old.id and new.legacy_eligible = old.legacy_eligible
            and new.user_id is not distinct from old.user_id
            and new.created_at is not distinct from old.created_at
            and new.show_notes is not distinct from old.show_notes then return new;
    end if;
    raise exception 'Legacy movie board write unavailable' using errcode = '55000';
end;
$$;
create trigger legacy_board_movie_rows_fence
before insert or update or delete on movie
for each row execute function guard_legacy_movie_board_row();

create function guard_legacy_board_source_cohort() returns trigger
language plpgsql set search_path = pg_catalog as $$
declare
    board_phase text;
begin
    execute format('select %I.lock_legacy_board_phase()', tg_table_schema) into board_phase;
    if board_phase is null or board_phase = 'FENCED' then
        raise exception 'Legacy snapshot cohort is locked' using errcode = '55000';
    end if;
    return null;
end;
$$;
create trigger legacy_board_source_cohort_fence
before insert or delete or update of id,created_at on "user"
for each statement execute function guard_legacy_board_source_cohort();
create trigger legacy_board_cohort_truncate_fence
before truncate on "user"
for each statement execute function require_open_legacy_board_write();

-- Take the same lock even for unrelated ID inserts: fence checks the reservation after contenders commit.
create function guard_legacy_board_reserved_destination() returns trigger
language plpgsql security definer set search_path = pg_catalog as $$
declare
    board_phase text;
    reserved_id uuid;
begin
    execute format('select phase,ranking_id from %I.legacy_board_cutover where singleton for share nowait',tg_table_schema)
        into board_phase,reserved_id;
    if board_phase is null or (board_phase = 'FENCED' and new.id = reserved_id) then
        raise exception 'Collection destination unavailable' using errcode = '55000';
    end if;
    return new;
end;
$$;
create trigger legacy_board_reserve_destination
before insert or update of id on ranking
for each row execute function guard_legacy_board_reserved_destination();
