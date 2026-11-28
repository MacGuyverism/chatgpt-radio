create table if not exists public.briefsona_reporter_libraries (
  owner uuid primary key references auth.users(id) on delete cascade,
  revision bigint not null,
  library jsonb not null
);
alter table public.briefsona_reporter_libraries enable row level security;
revoke all on public.briefsona_reporter_libraries from public, anon, authenticated;
create or replace function public.sync_briefsona_reporters(local_revision bigint, local_library jsonb)
returns jsonb language plpgsql security definer set search_path = '' as $$
declare result jsonb;
begin
  if auth.uid() is null then raise exception 'Authentication required'; end if;
  if local_revision > 0 then
    if jsonb_typeof(local_library) is distinct from 'object' then raise exception 'Invalid library'; end if;
    insert into public.briefsona_reporter_libraries values (auth.uid(), local_revision, local_library)
    on conflict (owner) do update set revision = excluded.revision, library = excluded.library
    where public.briefsona_reporter_libraries.revision < excluded.revision;
  end if;
  select jsonb_build_object('revision', revision, 'library', library) into result
    from public.briefsona_reporter_libraries where owner = auth.uid();
  return coalesce(result, '{}'::jsonb);
end;
$$;
revoke all on function public.sync_briefsona_reporters(bigint,jsonb) from public, anon;
grant execute on function public.sync_briefsona_reporters(bigint,jsonb) to authenticated;
