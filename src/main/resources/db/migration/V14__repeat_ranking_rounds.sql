-- Opt in per ranking. Historical rounds have no explicit creation command; leave them unchanged.
alter table ranking add column repeat_rounds_enabled boolean not null default false;

alter table ranking_movie_round
    add column creation_command_id uuid,
    add column creation_author_id uuid,
    add column creation_input_fingerprint bytea,
    add constraint ranking_movie_round_creation_author_fk
        foreign key (ranking_id, creation_author_id) references ranking_author_identity (ranking_id, author_id),
    add constraint ranking_movie_round_creation_metadata_ck check (
        (creation_command_id is null and creation_author_id is null and creation_input_fingerprint is null)
        or (round_number > 1 and creation_command_id is not null and creation_author_id is not null
            and creation_input_fingerprint is not null and octet_length(creation_input_fingerprint) = 32)
    );

-- The command identity is per entry, not per author. Reusing it for another author must also collide.
create unique index ranking_movie_round_creation_command_uk
    on ranking_movie_round (ranking_id, entry_id, creation_command_id)
    where creation_command_id is not null;
