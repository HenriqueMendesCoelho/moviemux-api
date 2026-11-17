-- Additive terminal archive. Existing rankings remain live; no historical data is rewritten.
alter table ranking add column archived_at timestamptz;

create function prevent_ranking_archive_reversal() returns trigger language plpgsql as $$
begin
  if OLD.archived_at is not null and NEW.archived_at is distinct from OLD.archived_at then
    raise exception 'Ranking archive timestamp is immutable' using errcode = '23514';
  end if;
  return NEW;
end;
$$;

create trigger ranking_archive_is_terminal
before update of archived_at on ranking
for each row execute function prevent_ranking_archive_reversal();
