create table public.briefsona_station_backups (
    owner_id uuid not null references auth.users(id) on delete cascade default auth.uid(),
    id uuid not null,
    revision bigint not null check (revision > 0),
    profile jsonb not null check (jsonb_typeof(profile) = 'object' and profile->>'id' is not distinct from id::text),
    primary key (owner_id, id)
);

alter table public.briefsona_station_backups enable row level security;
revoke all on public.briefsona_station_backups from public, anon, authenticated;
grant select, insert, update on public.briefsona_station_backups to authenticated;

create policy "Owners read station backups"
on public.briefsona_station_backups for select to authenticated
using (owner_id = (select auth.uid()));

create policy "Owners create station backups"
on public.briefsona_station_backups for insert to authenticated
with check (owner_id = (select auth.uid()));

create policy "Owners update station backups"
on public.briefsona_station_backups for update to authenticated
using (owner_id = (select auth.uid()))
with check (owner_id = (select auth.uid()));

create function public.save_briefsona_station(station_id uuid, station_revision bigint, station_profile jsonb)
returns void language sql security invoker set search_path = '' as $$
    insert into public.briefsona_station_backups as backup (id, revision, profile)
    values (station_id, station_revision, station_profile)
    on conflict (owner_id, id) do update
        set revision = excluded.revision, profile = excluded.profile
        where backup.revision < excluded.revision;
$$;

revoke all on function public.save_briefsona_station(uuid, bigint, jsonb) from public, anon;
grant execute on function public.save_briefsona_station(uuid, bigint, jsonb) to authenticated;

insert into storage.buckets (id, name, public, file_size_limit)
values ('briefsona-station-cues', 'briefsona-station-cues', false, 47185920);

create policy "Owners read station cues"
on storage.objects for select to authenticated
using (bucket_id = 'briefsona-station-cues' and (storage.foldername(name))[1] = (select auth.uid())::text);

create policy "Owners upload station cues"
on storage.objects for insert to authenticated
with check (bucket_id = 'briefsona-station-cues' and (storage.foldername(name))[1] = (select auth.uid())::text);

create policy "Owners retry station cue uploads"
on storage.objects for update to authenticated
using (bucket_id = 'briefsona-station-cues' and (storage.foldername(name))[1] = (select auth.uid())::text)
with check (bucket_id = 'briefsona-station-cues' and (storage.foldername(name))[1] = (select auth.uid())::text);
