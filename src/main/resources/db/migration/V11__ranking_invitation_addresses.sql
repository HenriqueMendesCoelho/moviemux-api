-- No prefill or plaintext: searchable HMAC and account-bound encrypted envelope only.
create table ranking_invitation_address (
    account_id uuid primary key references "user" (id),
    lookup_digest bytea not null unique check (octet_length(lookup_digest) = 32),
    encrypted_code bytea not null check (octet_length(encrypted_code) = 50)
);
