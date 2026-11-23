-- Contextual-only movies may omit provider metadata. Default/legacy movies still require it.
alter table movie
    alter column portuguese_title drop not null,
    alter column english_title drop not null,
    alter column director drop not null,
    alter column description drop not null,
    add constraint movie_legacy_required_metadata_ck check (
        not legacy_eligible or (
            portuguese_title is not null
            and english_title is not null
            and director is not null
            and description is not null
        )
    );
