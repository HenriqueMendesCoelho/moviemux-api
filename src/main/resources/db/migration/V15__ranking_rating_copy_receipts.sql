-- Empty additive storage for bounded, destination-scoped copy operation receipts.
create table ranking_rating_copy_receipt (
    operation_id uuid primary key,
    ranking_id uuid not null,
    round_id uuid not null,
    author_id uuid not null,
    expires_at timestamptz not null,
    constraint ranking_rating_copy_receipt_destination_uk unique (ranking_id, round_id, author_id),
    constraint ranking_rating_copy_receipt_destination_fk foreign key (ranking_id, round_id, author_id)
        references ranking_movie_rating (ranking_id, round_id, author_id) on delete cascade,
    constraint ranking_rating_copy_receipt_expiry_finite_ck check (isfinite(expires_at))
);

create index ranking_rating_copy_receipt_expiry_idx
    on ranking_rating_copy_receipt (expires_at, operation_id);
