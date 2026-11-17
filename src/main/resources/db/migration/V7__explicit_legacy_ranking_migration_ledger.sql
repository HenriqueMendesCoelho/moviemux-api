-- Empty bookkeeping only. Backfill is never run by Flyway or application startup.
create table legacy_ranking_migration (
    migration_key varchar(40) primary key check (migration_key = 'legacy-movies-v1'),
    ranking_id uuid not null unique references ranking(id),
    owner_user_id uuid not null,
    algorithm_version integer not null check (algorithm_version = 1),
    source_fingerprint varchar(64) not null check (source_fingerprint ~ '^[0-9a-f]{64}$'),
    target_fingerprint varchar(64) not null check (target_fingerprint ~ '^[0-9a-f]{64}$'),
    source_users bigint not null check (source_users > 0),
    source_movies bigint not null check (source_movies >= 0),
    source_ratings bigint not null check (source_ratings >= 0),
    completed_at timestamptz not null,
    unique (migration_key, ranking_id)
);
create table legacy_ranking_movie_map (
    migration_key varchar(40) not null,
    ranking_id uuid not null,
    legacy_movie_id uuid not null references movie(id),
    entry_id uuid not null,
    round_id uuid not null,
    primary key (migration_key, legacy_movie_id),
    unique (ranking_id, entry_id),
    unique (ranking_id, round_id),
    foreign key (migration_key, ranking_id) references legacy_ranking_migration(migration_key, ranking_id),
    foreign key (ranking_id, entry_id) references ranking_movie_entry(ranking_id, entry_id),
    foreign key (ranking_id, round_id) references ranking_movie_round(ranking_id, round_id)
);
