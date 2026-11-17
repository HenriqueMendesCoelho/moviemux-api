-- Empty additive contextual storage only. No legacy backfill or change of existing read paths.
-- Nullable reveal and score preserve legacy states without imposing an unapproved rating scale.
create table ranking_movie_entry (
    ranking_id uuid not null references ranking (id),
    entry_id uuid not null,
    movie_id uuid not null references movie (id),
    added_by_author_id uuid,
    created_at timestamptz not null,
    updated_at timestamptz,
    primary key (ranking_id, entry_id),
    unique (ranking_id, movie_id),
    foreign key (ranking_id, added_by_author_id)
        references ranking_author_identity (ranking_id, author_id)
);

create table ranking_movie_round (
    ranking_id uuid not null,
    entry_id uuid not null,
    round_id uuid not null,
    round_number bigint not null check (round_number > 0),
    description text,
    created_at timestamptz not null,
    updated_at timestamptz,
    revealed boolean,
    primary key (ranking_id, round_id),
    unique (ranking_id, entry_id, round_number),
    foreign key (ranking_id, entry_id) references ranking_movie_entry (ranking_id, entry_id)
);

create table ranking_movie_rating (
    ranking_id uuid not null,
    round_id uuid not null,
    author_id uuid not null,
    score integer,
    created_at timestamptz not null,
    updated_at timestamptz,
    primary key (ranking_id, round_id, author_id),
    foreign key (ranking_id, round_id) references ranking_movie_round (ranking_id, round_id),
    foreign key (ranking_id, author_id) references ranking_author_identity (ranking_id, author_id)
);
